package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.security.UserContext;
import com.chat.im.entity.ChatSession;
import com.chat.im.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final ChatSessionMapper sessionMapper;
    private final StringRedisTemplate redis;
    private final PresenceService presenceService;
    private final WsPushService wsPushService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 客户创建会话: 若有在线坐席则直接分配, 否则进入等待队列。
     */
    @Transactional
    public ApiResponse<ChatSession> create() {
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
        s.setLastMessage("等待客服接入...");
        sessionMapper.insert(s);

        // 尝试立即分配
        Long agentId = presenceService.pickIdleAgent();
        if (agentId != null) {
            assignAgent(s.getId(), agentId);
            s.setAgentId(agentId);
            s.setStatus(CommonConstants.SESSION_ACTIVE);
            s.setLastMessage("客服已接入");
            sessionMapper.updateById(s);
        } else {
            // 入队等待
            redis.opsForList().rightPush(CommonConstants.REDIS_SESSION_QUEUE, String.valueOf(s.getId()));
            redis.opsForValue().set(
                    CommonConstants.REDIS_CUSTOMER_SESSION + uid, String.valueOf(s.getId()));
            // 通知坐席端有新会话
            wsPushService.notifyAgentNewWaiting(s.getId());
        }
        return ApiResponse.ok(s);
    }

    /**
     * 坐席主动领取一个等待中的会话 (抢单)。
     */
    @Transactional
    public ApiResponse<ChatSession> claim() {
        Long agentId = UserContext.userId();
        if (agentId == null) return ApiResponse.fail(401, "未登录");

        // 抢占队列头
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
        return ApiResponse.ok(s);
    }

    private void assignAgent(Long sessionId, Long agentId) {
        // 记录坐席-会话 映射
        redis.opsForValue().set(CommonConstants.REDIS_AGENT_SESSION + agentId, String.valueOf(sessionId));
        // 客户-会话
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            redis.opsForValue().set(CommonConstants.REDIS_CUSTOMER_SESSION + s.getCustomerId(),
                    String.valueOf(sessionId));
        }
    }

    /**
     * 查询我的会话 (客户/坐席都可用)。
     */
    public ApiResponse<List<ChatSession>> mySessions() {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        LambdaQueryWrapper<ChatSession> q = new LambdaQueryWrapper<>();
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            q.eq(ChatSession::getAgentId, uid);
        } else {
            q.eq(ChatSession::getCustomerId, uid);
        }
        q.orderByDesc(ChatSession::getUpdatedAt).last("LIMIT 50");
        return ApiResponse.ok(sessionMapper.selectList(q));
    }

    /** 坐席查看当前等待队列 (只读视图, 不抢单) */
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

        // 清理 Redis
        if (s.getAgentId() != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + s.getAgentId());
        }
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + s.getCustomerId());
        return ApiResponse.ok();
    }

    private String generateSessionNo() {
        return "S" + LocalDate.now().format(NO_FMT) + System.currentTimeMillis() % 100000;
    }
}