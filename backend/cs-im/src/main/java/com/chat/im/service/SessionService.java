package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;       // MP Lambda 构造器
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;              // MP 普通构造器
import com.chat.common.api.ApiResponse;                                        // 统一响应包装
import com.chat.common.constant.CommonConstants;                               // 公共常量
import com.chat.common.security.UserContext;                                   // 当前用户 ThreadLocal
import com.chat.im.event.TransferToHumanEvent;                                  // 转人工事件
import org.springframework.context.event.EventListener;                        // @EventListener
import org.springframework.scheduling.annotation.Async;                         // @Async 异步
import com.chat.im.entity.Agent;                                                // Agent 实体
import com.chat.im.entity.ChatSession;                                          // chat_session 实体
import com.chat.im.mapper.ChatSessionMapper;                                     // DAO
import com.chat.im.mapper.UserMapper;                                           // user DAO
import lombok.RequiredArgsConstructor;                                          // final 注入
import lombok.extern.slf4j.Slf4j;                                                // 日志
import org.springframework.data.redis.core.StringRedisTemplate;                  // Redis 操作
import org.springframework.stereotype.Service;                                   // Spring Bean
import org.springframework.transaction.annotation.Transactional;                  // 事务

import java.time.LocalDate;                                                      // 日期 (生成 session_no)
import java.time.LocalDateTime;                                                  // 时间戳
import java.time.format.DateTimeFormatter;                                       // 日期格式化
import java.util.Optional;                                                        // Optional 包装
import java.util.Arrays;                                                          // 数组工具
import java.util.HashSet;                                                         // Set
import java.util.List;                                                            // List
import java.util.Set;                                                             // Set

/**
 * SessionService - 会话核心业务服务.
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 创建会话 (客户发起, 支持人/机器人两种模式)
 *   - 抢单 (坐席手动接单, 防串线 CAS)
 *   - 转接 (坐席 A -> 坐席 B)
 *   - 关闭 (客户/坐席主动关闭, 触发 CSAT 评分)
 *   - 评分 (CSAT 1-5 星)
 *   - 转人工 (机器人会话 -> 新人工会话, 通过 ApplicationEvent 异步处理)
 *   - 我的会话 / 等待队列 (查询)
 *
 * 依赖:
 *   - SessionMapper / UserMapper: 持久化
 *   - StringRedisTemplate: 会话队列 (Redis List) + 坐席-会话映射
 *   - PresenceService / AgentStatusService: 在线状态 + 坐席状态
 *   - WsPushService: STOMP 推送 (TRANSFER / CLOSED 事件)
 *   - SystemMessageService: 系统消息写入
 *   - AuditLogService: 操作审计
 *
 * 并发安全:
 *   - claim() 用 MySQL 条件 UPDATE (CAS): 只更新 status=WAITING AND agent_id IS NULL
 *   - 多坐席同时抢单只有 1 个成功, 其他返 409
 *   - 补偿机制: 失败的请求清理 Redis 队列残留 sid
 */
@Slf4j                                                                          // 自动生成 log 字段
@Service                                                                       // 注册 Spring Bean
@RequiredArgsConstructor                                                       // final 字段自动注入
public class SessionService {

    /** chat_session 表 DAO */
    private final ChatSessionMapper sessionMapper;
    /** user 表 DAO (查坐席信息) */
    private final UserMapper userMapper;
    /** Redis 客户端 (会话队列 / 坐席-会话映射) */
    private final StringRedisTemplate redis;
    /** 用户在线状态服务 */
    private final PresenceService presenceService;
    /** 坐席状态服务 (ONLINE/AWAY/BUSY/OFFLINE) */
    private final AgentStatusService agentStatusService;
    /** STOMP 推送服务 */
    private final WsPushService wsPushService;
    /** 审计日志服务 */
    private final AuditLogService auditLogService;
    /** 系统消息服务 (独立, 避免循环依赖) */
    private final SystemMessageService systemMessageService;

    /** session_no 格式: S + yyyyMMdd + 序号 (例: S202607081234) */
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 客户创建会话 (带技能路由):
     *   1) 已有 ACTIVE 会话? 直接返回
     *   2) 查找 ONLINE + 技能匹配 + 无会话的坐席, 自动分配
     *   3) 没匹配到 → 入等待队列
     */
    @Transactional
    public ApiResponse<ChatSession> create(String skillTag) {
        return create(skillTag, false);
    }

    /**
     * 创建会话 (支持机器人模式).
     *  - isBot=true: 客户进入机器人会话, agent_id 留空, is_bot=1, 直接 ACTIVE
     *  - isBot=false: 走智能分配到人工坐席 (默认)
     */
    public ApiResponse<ChatSession> create(String skillTag, boolean isBot) {
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
        s.setSkillTag(skillTag);
        s.setIsBot(isBot ? 1 : 0);

        // 机器人模式: 直接 ACTIVE, 不分配坐席, 推欢迎语
        if (isBot) {
            s.setStatus(CommonConstants.SESSION_ACTIVE);
            s.setLastMessage("智能客服在线, 请描述您的问题");
            sessionMapper.insert(s);
            systemMessageService.sendSystemMessage(s.getId(),
                "我是智能客服小助手, 可以帮您解答常见问题。\n输入 '人工' 可随时转接真人客服。");
            auditLogService.log(uid, "CREATE_BOT_SESSION", String.valueOf(s.getId()),
                "skill=" + skillTag);
            return ApiResponse.ok(s);
        }

        // 人工模式: 等待分配
        s.setStatus(CommonConstants.SESSION_WAITING);
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
            systemMessageService.sendSystemMessage(s.getId(),
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
     * 坐席抢单 (LPOP 等待队列头) - 便捷重载, 等价于 claim(null).
     */
    @Transactional
    public ApiResponse<ChatSession> claim() {
        return claim(null);
    }

    /**
     * 抢单 (原子: 防串线).
     * ----------------------------------------------------------------------------
     * 两种调用:
     *   - 主动接起指定会话 (Long sessionId) — 坐席从 waiting list 选单
     *   - sessionId=null — 从 Redis 队列自动取下一个 WAITING (抢单按钮)
     *
     * 并发安全:
     *   - MySQL 条件 UPDATE (CAS): 只更新 status=WAITING AND agent_id IS NULL 的行
     *   - 多个坐席同时抢同一会话, MySQL 行锁串行化, 只有 1 个 affected=1
     *   - 其他人 affected=0 → 查最新 agentId 报 409 "会话已被 #X 接起"
     *
     * Redis 补偿:
     *   - CAS 失败后从队列移除 sid (避免其他坐席重复拿到)
     *   - 成功后从队列移除 sid (防 Redis 残留导致下次重复推)
     */
    @Transactional
    public ApiResponse<ChatSession> claim(Long sessionId) {
        // 从 ThreadLocal 取当前坐席 ID (JwtAuthInterceptor 已设置)
        Long agentId = UserContext.userId();
        if (agentId == null) return ApiResponse.fail(401, "未登录");

        // 1) 取要抢的 sid: 手动指定 OR 从 Redis 队列 LPOP
        if (sessionId == null) {
            // 原子弹出队头
            String sid = redis.opsForList().leftPop(CommonConstants.REDIS_SESSION_QUEUE);
            if (sid == null) return ApiResponse.fail(404, "暂无等待中的会话");
            sessionId = Long.parseLong(sid);
        }

        // 2) 前置校验
        ChatSession probe = sessionMapper.selectById(sessionId);
        if (probe == null) return ApiResponse.fail(404, "会话不存在");
        if (!CommonConstants.ROLE_AGENT.equalsIgnoreCase(UserContext.role())) {
            return ApiResponse.fail(403, "仅坐席可接单");
        }

        // 3) 原子 CAS: 条件 UPDATE (同时检查 status=WAITING 且 agent_id IS NULL)
        // MySQL 隐式行锁保证原子, affected 行数决定成败
        ChatSession update = new ChatSession();
        update.setAgentId(agentId);
        update.setStatus(CommonConstants.SESSION_ACTIVE);
        update.setLastMessage("客服已接入");
        update.setUpdatedAt(LocalDateTime.now());
        int affected = sessionMapper.update(update, new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getStatus, CommonConstants.SESSION_WAITING)
                .isNull(ChatSession::getAgentId));
        if (affected == 0) {
            // CAS 失败: 被别人抢先
            // 补偿: 从 Redis 队列移除该 sid (避免其他坐席重复拿)
            redis.opsForList().remove(CommonConstants.REDIS_SESSION_QUEUE, 0, String.valueOf(sessionId));
            ChatSession latest = sessionMapper.selectById(sessionId);
            String who = (latest != null && latest.getAgentId() != null) ? "#" + latest.getAgentId() : "其他坐席";
            return ApiResponse.fail(409, "会话已被 " + who + " 接起, 请选择其他会话");
        }

        // 4) CAS 成功: 拿最新 session, 写 Redis 映射, 发系统消息
        ChatSession s = sessionMapper.selectById(sessionId);
        assignAgent(sessionId, agentId);

        // 系统消息 "客服 XXX 已为您服务 (擅长: tech)"
        Agent agent = userMapper.selectById(agentId);
        String agentName = agent != null ? agent.getNickname() : "#" + agentId;
        String skillPart = s.getSkillTag() != null && !s.getSkillTag().isEmpty()
                ? " (擅长: " + s.getSkillTag() + ")" : "";
        systemMessageService.sendSystemMessage(sessionId,
                "客服 " + agentName + " 已为您服务" + skillPart);

        // 补偿: 队列里其他重复 sid 一起清理 (防 Redis 残留)
        redis.opsForList().remove(CommonConstants.REDIS_SESSION_QUEUE, 0, String.valueOf(sessionId));

        // 审计日志
        auditLogService.log(agentId, "CLAIM", String.valueOf(sessionId), null);
        return ApiResponse.ok(s);
    }

    /**
     * 会话转接: 坐席 A → 坐席 B.
     * ----------------------------------------------------------------------------
     * 流程:
     *   1) 校验: 会话存在 + ACTIVE + 当前坐席是 owner
     *   2) 校验: 目标坐席存在 + role=AGENT
     *   3) 更新 session: agent_id=toAgentId, transferred_from_agent_id=fromAgentId
     *   4) 更新 Redis: 清除原坐席映射, 写入新坐席映射
     *   5) 系统消息: "会话已转接给客服 XXX, 原因: ..."
     *   6) STOMP 推送: notifySessionTransferred (前端 TRANSFERRED 事件)
     *   7) 审计日志
     */
    @Transactional
    public ApiResponse<ChatSession> transfer(Long sessionId, Long toAgentId, String reason) {
        Long fromAgentId = UserContext.userId();                            // 当前坐席 (转出方)
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
        systemMessageService.sendSystemMessage(sessionId,
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
        String role = UserContext.role();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        boolean ok = CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)
                ? uid.equals(s.getAgentId())
                : uid.equals(s.getCustomerId());
        if (!ok) return ApiResponse.fail(403, "无权操作");

        // 幂等: 已关闭的会话不重复推
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            return ApiResponse.ok();
        }

        s.setStatus(CommonConstants.SESSION_CLOSED);
        s.setClosedAt(LocalDateTime.now());
        s.setLastMessage(CommonConstants.ROLE_AGENT.equalsIgnoreCase(role) ? "客服已结束会话" : "已结束会话");
        sessionMapper.updateById(s);

        if (s.getAgentId() != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + s.getAgentId());
        }
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + s.getCustomerId());

        // 推送系统消息 (另一方能看到)
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role) && s.getCustomerId() != null) {
            systemMessageService.sendSystemMessage(sessionId, "客服已结束本次会话");
        } else if (CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(role) && s.getAgentId() != null) {
            systemMessageService.sendSystemMessage(sessionId, "客户已结束本次会话");
        }
        // 推 CLOSED 事件 (另一方刷新会话列表 + 清理当前)
        String reason = CommonConstants.ROLE_AGENT.equalsIgnoreCase(role) ? "AGENT_CLOSE" : "CUSTOMER_CLOSE";
        wsPushService.notifySessionClosed(sessionId, s.getCustomerId(), s.getAgentId(), reason);

        auditLogService.log(uid, "CLOSE", String.valueOf(sessionId), "role=" + role);
        return ApiResponse.ok();
    }

    /**
     * 异步处理客户转人工事件 (由 MessageService 发布).
     * ----------------------------------------------------------------------------
     * 不走 UserContext (因为 @Async 异步, 原 ThreadLocal 可能已失效),
     * 事件本身携带 customerId + sessionId + skill.
     *
     * 幂等性: 如果 bot 会话已经被关闭或 customer 不匹配, 直接 return.
     *
     * 流程:
     *   1) 关闭 bot 会话 (status=CLOSED, lastMessage="客户申请转人工")
     *   2) 重新进人工队列 (调 create(skill, false))
     *   3) 推 BOT_TRANSFER 事件给客户前端 (跳转新人工会话)
     *   4) 审计日志
     */
    @EventListener
    @Async
    public void onTransferToHumanEvent(TransferToHumanEvent event) {
        Long customerId = event.getCustomerId();
        Long oldSessionId = event.getOldSessionId();
        String skillTag = event.getSkillTag();
        log.info("[event] TransferToHuman: customer={} session={} skill={}", customerId, oldSessionId, skillTag);

        // 幂等检查: 会话不存在/已关闭/customer 不匹配 直接返
        ChatSession old = sessionMapper.selectById(oldSessionId);
        if (old == null || CommonConstants.SESSION_CLOSED.equals(old.getStatus())) return;
        if (!customerId.equals(old.getCustomerId())) return;

        // 1) 关闭 bot 会话
        old.setStatus(CommonConstants.SESSION_CLOSED);
        old.setClosedAt(LocalDateTime.now());
        old.setLastMessage("客户申请转人工");
        sessionMapper.updateById(old);
        // 清 Redis 客户-会话映射
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + customerId);

        // 2) 重新进人工队列 — 直接调内部 create 逻辑 (isBot=false)
        ApiResponse<ChatSession> fresh = create(skillTag != null ? skillTag : old.getSkillTag(), false);
        Long newId = fresh.getData() != null ? fresh.getData().getId() : null;
        auditLogService.log(customerId, "TRANSFER_BOT_TO_HUMAN", String.valueOf(oldSessionId),
                "newSession=" + newId);

        // 3) 推事件给客户前端: 跳转到新会话
        if (newId != null) {
            wsPushService.pushBotTransferEvent(customerId, oldSessionId, newId);
        }
    }

    /**
     * 客户主动转人工 (REST 版本, 兼容直接调用).
     * 与 onTransferToHumanEvent 区别: 这个走 UserContext 同步调用, 给客户前端按钮触发.
     */
    @Transactional
    public ApiResponse<ChatSession> transferToHuman(Long sessionId, String skillTag) {
        Long uid = UserContext.userId();
        if (!CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(UserContext.role())) {
            return ApiResponse.fail(403, "仅客户可调用");
        }
        ChatSession old = sessionMapper.selectById(sessionId);
        if (old == null) return ApiResponse.fail(404, "会话不存在");
        if (!uid.equals(old.getCustomerId())) return ApiResponse.fail(403, "无权操作");
        if (CommonConstants.SESSION_CLOSED.equals(old.getStatus())) {
            return ApiResponse.fail(409, "会话已关闭");
        }

        // 关闭当前机器人会话
        old.setStatus(CommonConstants.SESSION_CLOSED);
        old.setClosedAt(LocalDateTime.now());
        old.setLastMessage("客户申请转人工");
        sessionMapper.updateById(old);
        if (old.getAgentId() != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + old.getAgentId());
        }
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + old.getCustomerId());

        // 重新进人工队列 (用 create(..., false), 会有新 session id)
        ApiResponse<ChatSession> fresh = create(skillTag != null ? skillTag : old.getSkillTag(), false);
        auditLogService.log(uid, "TRANSFER_BOT_TO_HUMAN", String.valueOf(sessionId),
            "newSession=" + (fresh.getData() != null ? fresh.getData().getId() : "?"));
        return fresh;
    }

    /**
     * 客户主动退出会话.
     * 与 close 的区别:
     *  - 仅客户可调
     *  - 推一条系统消息给坐席 ("客户已退出")
     *  - 推一个 CLOSE 事件给坐席 (前端根据事件刷会话列表)
     */
    @Transactional
    public ApiResponse<Void> customerExit(Long sessionId, String reason) {
        Long uid = UserContext.userId();
        if (!CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(UserContext.role())) {
            return ApiResponse.fail(403, "仅客户可调用");
        }
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        if (!uid.equals(s.getCustomerId())) return ApiResponse.fail(403, "无权操作");
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            return ApiResponse.ok();  // 幂等: 重复点击不报错
        }

        s.setStatus(CommonConstants.SESSION_CLOSED);
        s.setClosedAt(LocalDateTime.now());
        s.setLastMessage("客户主动退出");
        sessionMapper.updateById(s);

        if (s.getAgentId() != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + s.getAgentId());
        }
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + s.getCustomerId());

        // 推系统消息
        String text = reason != null && !reason.isEmpty()
            ? "客户已主动退出 (" + reason + ")"
            : "客户已主动退出会话";
        systemMessageService.sendSystemMessage(sessionId, text);
        // 推事件 (坐席侧 Client.OnEvent 收到 type=CLOSED, 刷新列表)
        wsPushService.notifySessionClosed(sessionId, s.getCustomerId(), s.getAgentId(), "CUSTOMER_EXIT");

        auditLogService.log(uid, "CUSTOMER_EXIT", String.valueOf(sessionId),
            "agentId=" + s.getAgentId() + " reason=" + reason);
        return ApiResponse.ok();
    }

    /**
     * 客户申请转接其他坐席 (会保留会话与聊天记录, 只是换客服).
     *  1) 如同技能尚有其他可分配坐席 -> 选一个 (优先不同于当前坐席)
     *  2) 如无其他坐席, 且不要求换技能 -> 转成 WAITING 重新走分配
     *  3) 都失败则报错
     */
    @Transactional
    public ApiResponse<ChatSession> customerRequestTransfer(Long sessionId, String preferredSkill) {
        Long uid = UserContext.userId();
        if (!CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(UserContext.role())) {
            return ApiResponse.fail(403, "仅客户可调用");
        }
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        if (!uid.equals(s.getCustomerId())) return ApiResponse.fail(403, "无权操作");
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            return ApiResponse.fail(409, "会话已关闭, 不能转接");
        }

        String skill = preferredSkill != null && !preferredSkill.isEmpty()
            ? preferredSkill
            : s.getSkillTag();
        Long currentAgentId = s.getAgentId();

        // 1) 查可分配的坐席 (不含当前 + 同一技能优先).
        // Agent.skillTags 是 JSON 数组字符串 (e.g. '["billing","refund"]'), 用 LIKE 包含匹配.
        QueryWrapper<Agent> q = new QueryWrapper<>();
        q.eq("role", "AGENT").eq("status", 1);
        if (skill != null && !skill.isEmpty()) {
            q.and(w -> w.like("skill_tags", "\"" + skill + "\"").or().like("skill_tags", skill));
        }
        q.orderByAsc("id");
        List<Agent> candidates = userMapper.selectList(q);
        Long newAgentId = null;
        for (Agent a : candidates) {
            if (a.getId().equals(currentAgentId)) continue;
            newAgentId = a.getId();
            break;
        }
        // 2) 没有同技能, 查任意可分配
        if (newAgentId == null) {
            QueryWrapper<Agent> q2 = new QueryWrapper<>();
            q2.eq("role", "AGENT").eq("status", 1).orderByAsc("id");
            List<Agent> allAssignable = userMapper.selectList(q2);
            for (Agent a : allAssignable) {
                if (a.getId().equals(currentAgentId)) continue;
                newAgentId = a.getId();
                break;
            }
        }
        if (newAgentId == null) {
            return ApiResponse.fail(503, "暂无可转接的坐席, 请稍后再试");
        }

        // 3) 更新会话
        Long fromAgentId = currentAgentId;
        s.setAgentId(newAgentId);
        s.setTransferredFromAgentId(fromAgentId);
        s.setTransferReason("客户申请转接");
        s.setLastMessage("会话已转接");
        sessionMapper.updateById(s);

        // Redis 映射
        if (fromAgentId != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + fromAgentId);
        }
        assignAgent(sessionId, newAgentId);

        // 4) 推系统消息 + 事件
        Agent newAgent = userMapper.selectById(newAgentId);
        String newAgentName = newAgent != null ? newAgent.getNickname() : "#" + newAgentId;
        String oldAgentName = fromAgentId != null
            ? (Optional.ofNullable(userMapper.selectById(fromAgentId)).map(Agent::getNickname).orElse("#" + fromAgentId))
            : null;
        String msg = "客户申请转接, 客服已更换为 " + newAgentName +
            (oldAgentName != null ? " (原客服 " + oldAgentName + ")" : "");
        systemMessageService.sendSystemMessage(sessionId, msg);
        wsPushService.notifySessionTransferred(sessionId, fromAgentId, newAgentId, "客户申请转接");

        auditLogService.log(uid, "CUSTOMER_TRANSFER", String.valueOf(sessionId),
            "from=" + fromAgentId + " to=" + newAgentId + " skill=" + skill);
        return ApiResponse.ok(s);
    }

    private String generateSessionNo() {
        return "S" + LocalDate.now().format(NO_FMT) + System.currentTimeMillis() % 100000;
    }
}