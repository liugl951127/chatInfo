package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.security.UserContext;
import com.chat.im.entity.Agent;
import com.chat.im.entity.ChatSession;
import com.chat.im.mapper.ChatSessionMapper;
import com.chat.im.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final ChatSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final PresenceService presenceService;
    private final AgentStatusService agentStatusService;
    private final WsPushService wsPushService;
    private final AuditLogService auditLogService;
    private final MessageService messageService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 客户创建会话 (带技能路由):
     *   1) 已有 ACTIVE 会话? 直接返回
     *   2) 查找 ONLINE + 技能匹配 + 无会话的坐席, 自动分配
     *   3) 没匹配到 → 入等待队列
     */
    @Transactional
    public ApiResponse<ChatSession> create(String skillTag) {
        Long uid = UserContext.userId();
        if (uid == null) return ApiResponse.fail(401, "未登录");

        // 已有 ACTIVE 会话? 直接返回
        ChatSession active = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getCustomerId, uid)
                .eq(ChatSession::getStatus, CommonConstants.SESSION_ACTIVE)
                .orderByDesc(ChatSession::getUpdatedAt)
                .last("LIMIT 1"));
        if (active != null) return ApiResponse.ok(active);

        ChatSession s = new ChatSession();
        s.setSessionNo(generateSessionNo());
        s.setCustomerId(uid);
        s.setStatus(CommonConstants.SESSION_WAITING);
        s.setSkillTag(skillTag);
        s.setLastMessage("等待客服接入...");
        sessionMapper.insert(s);

        // 技能路由: 找有该技能的 ONLINE 坐席
        Long agentId = pickAgentBySkill(skillTag);

        if (agentId != null) {
            assignAgent(s.getId(), agentId);
            s.setAgentId(agentId);
            s.setStatus(CommonConstants.SESSION_ACTIVE);
            s.setLastMessage("客服已接入");
            sessionMapper.updateById(s);
            // 推送系统消息给客户: 当前坐席为您服务
            Agent agent = userMapper.selectById(agentId);
            String agentName = agent != null ? agent.getNickname() : "#" + agentId;
            String skillPart = skillTag != null && !skillTag.isEmpty()
                    ? " (擅长: " + skillTag + ")" : "";
            messageService.sendSystemMessage(s.getId(),
                    "客服 " + agentName + " 已为您服务" + skillPart);
            auditLogService.log(uid, "CREATE_SESSION", String.valueOf(s.getId()),
                    "auto-assigned to agent=" + agentId + " skill=" + skillTag);
        } else {
            // 入队
            redis.opsForList().rightPush(CommonConstants.REDIS_SESSION_QUEUE, String.valueOf(s.getId()));
            redis.opsForValue().set(
                    CommonConstants.REDIS_CUSTOMER_SESSION + uid, String.valueOf(s.getId()));
            wsPushService.notifyAgentNewWaiting(s.getId(), skillTag);
            auditLogService.log(uid, "CREATE_SESSION", String.valueOf(s.getId()),
                    "queued, skill=" + skillTag);
        }
        return ApiResponse.ok(s);
    }

    /**
     * 智能分配: 按技能筛选 ONLINE + 未在忙的坐席
     */
    private Long pickAgentBySkill(String skillTag) {
        Set<String> onlineIds = presenceService.onlineAgents();
        if (onlineIds == null || onlineIds.isEmpty()) return null;

        for (String sid : onlineIds) {
            Long aid = Long.parseLong(sid);
            if (!agentStatusService.isAssignable(aid)) continue;
            // 检查坐席是否已有 ACTIVE 会话
            Long existing = sessionMapper.selectCount(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getAgentId, aid)
                    .eq(ChatSession::getStatus, CommonConstants.SESSION_ACTIVE));
            if (existing != null && existing > 0) continue;

            Agent agent = userMapper.selectById(aid);
            if (agent == null) continue;
            // 无技能要求 OR 坐席技能命中
            if (skillTag == null || skillTag.isEmpty()) return aid;
            if (agent.getSkillTags() != null) {
                Set<String> skills = new HashSet<>(Arrays.asList(agent.getSkillTags().split(",")));
                if (skills.contains(skillTag)) return aid;
            }
        }
        return null;
    }

    /**
     * 坐席抢单 (LPOP 等待队列头).
     */
    @Transactional
    public ApiResponse<ChatSession> claim() {
        Long agentId = UserContext.userId();
        if (agentId == null) return ApiResponse.fail(401, "未登录");

        String sid = redis.opsForList().leftPop(CommonConstants.REDIS_SESSION_QUEUE);
        if (sid == null) return ApiResponse.fail(404, "暂无等待中的会话");
        Long sessionId = Long.parseLong(sid);

        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null || !CommonConstants.SESSION_WAITING.equals(s.getStatus())) {
            return ApiResponse.fail(409, "会话已被领取");
        }
        assignAgent(sessionId, agentId);
        s.setAgentId(agentId);
        s.setStatus(CommonConstants.SESSION_ACTIVE);
        s.setLastMessage("客服已接入");
        sessionMapper.updateById(s);
        // 推送系统消息给客户: 当前坐席为您服务
        Agent agent = userMapper.selectById(agentId);
        String agentName = agent != null ? agent.getNickname() : "#" + agentId;
        String skillPart = s.getSkillTag() != null && !s.getSkillTag().isEmpty()
                ? " (擅长: " + s.getSkillTag() + ")" : "";
        messageService.sendSystemMessage(sessionId,
                "客服 " + agentName + " 已为您服务" + skillPart);
        auditLogService.log(agentId, "CLAIM", String.valueOf(sessionId), null);
        return ApiResponse.ok(s);
    }

    /**
     * 会话转接: 坐席 A → 坐席 B
     */
    @Transactional
    public ApiResponse<ChatSession> transfer(Long sessionId, Long toAgentId, String reason) {
        Long fromAgentId = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        if (!CommonConstants.SESSION_ACTIVE.equals(s.getStatus())) {
            return ApiResponse.fail(409, "只能转接进行中的会话");
        }
        if (!fromAgentId.equals(s.getAgentId())) {
            return ApiResponse.fail(403, "只能转接自己的会话");
        }
        Agent target = userMapper.selectById(toAgentId);
        if (target == null || !"AGENT".equals(target.getRole())) {
            return ApiResponse.fail(400, "目标坐席不存在");
        }
        s.setTransferredFromAgentId(fromAgentId);
        s.setAgentId(toAgentId);
        s.setTransferReason(reason);
        s.setLastMessage("会话已转接");
        sessionMapper.updateById(s);

        // Redis 映射更新
        redis.delete(CommonConstants.REDIS_AGENT_SESSION + fromAgentId);
        assignAgent(sessionId, toAgentId);

        // 推送系统消息给会话双方: 会话已转接给 XXX
        Agent newAgent = userMapper.selectById(toAgentId);
        String newAgentName = newAgent != null ? newAgent.getNickname() : "#" + toAgentId;
        messageService.sendSystemMessage(sessionId,
                "会话已转接给客服 " + newAgentName + (reason != null && !reason.isEmpty()
                        ? ", 原因: " + reason : ""));
        // 通知双方 (WebSocket 事件)
        wsPushService.notifySessionTransferred(sessionId, fromAgentId, toAgentId, reason);

        auditLogService.log(fromAgentId, "TRANSFER", String.valueOf(sessionId),
                "to=" + toAgentId + " reason=" + reason);
        return ApiResponse.ok(s);
    }

    /**
     * CSAT 评分 (客户关闭会话后).
     */
    @Transactional
    public ApiResponse<Void> rate(Long sessionId, Integer rating, String comment) {
        if (rating == null || rating < 1 || rating > 5) {
            return ApiResponse.fail(400, "评分必须在 1-5 之间");
        }
        Long uid = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        if (!uid.equals(s.getCustomerId())) return ApiResponse.fail(403, "只有客户可以评价");

        s.setRating(rating);
        s.setRatingComment(comment);
        s.setRatedAt(LocalDateTime.now());
        sessionMapper.updateById(s);

        auditLogService.log(uid, "RATE", String.valueOf(sessionId),
                "rating=" + rating + " comment=" + comment);
        return ApiResponse.ok();
    }

    private void assignAgent(Long sessionId, Long agentId) {
        redis.opsForValue().set(CommonConstants.REDIS_AGENT_SESSION + agentId, String.valueOf(sessionId));
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            redis.opsForValue().set(CommonConstants.REDIS_CUSTOMER_SESSION + s.getCustomerId(),
                    String.valueOf(sessionId));
        }
    }

    public ApiResponse<List<com.chat.im.dto.SessionView>> mySessions() {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        LambdaQueryWrapper<ChatSession> q = new LambdaQueryWrapper<>();
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            q.eq(ChatSession::getAgentId, uid);
        } else {
            q.eq(ChatSession::getCustomerId, uid);
        }
        q.orderByDesc(ChatSession::getUpdatedAt).last("LIMIT 50");
        List<ChatSession> sessions = sessionMapper.selectList(q);
        List<com.chat.im.dto.SessionView> views = new java.util.ArrayList<>(sessions.size());
        for (ChatSession s : sessions) {
            Long peerId = CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)
                ? s.getCustomerId()
                : s.getAgentId();
            Boolean peerOnline = peerId == null ? null : presenceService.isOnline(peerId);
            views.add(com.chat.im.dto.SessionView.from(s, peerOnline));
        }
        return ApiResponse.ok(views);
    }

    public ApiResponse<List<Long>> waitingQueue() {
        List<String> ids = redis.opsForList().range(CommonConstants.REDIS_SESSION_QUEUE, 0, -1);
        if (ids == null) return ApiResponse.ok(List.of());
        return ApiResponse.ok(ids.stream().map(Long::parseLong).toList());
    }

    @Transactional
    public ApiResponse<Void> close(Long sessionId) {
        Long uid = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        boolean ok = CommonConstants.ROLE_AGENT.equalsIgnoreCase(UserContext.role())
                ? uid.equals(s.getAgentId())
                : uid.equals(s.getCustomerId());
        if (!ok) return ApiResponse.fail(403, "无权操作");

        s.setStatus(CommonConstants.SESSION_CLOSED);
        s.setClosedAt(LocalDateTime.now());
        sessionMapper.updateById(s);

        if (s.getAgentId() != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + s.getAgentId());
        }
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + s.getCustomerId());
        auditLogService.log(uid, "CLOSE", String.valueOf(sessionId), null);
        return ApiResponse.ok();
    }

    private String generateSessionNo() {
        return "S" + LocalDate.now().format(NO_FMT) + System.currentTimeMillis() % 100000;
    }
}