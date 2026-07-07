package com.chat.im.service;

import com.chat.common.constant.CommonConstants;
import com.chat.im.entity.ChatSession;
import com.chat.im.event.PresenceChangedEvent;
import com.chat.im.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线状态服务:
 *  - Redis 存权威在线状态 (TTL 30min) + 坐席状态集
 *  - 本地 ConcurrentHashMap 跟踪"当前 cs-im 实例上哪些用户已注册", 用于判断本次连接是
 *    "首次上线" 还是 "重连"
 *  - 状态变化时发布 PresenceChangedEvent, 由 WsPushService 监听后转发给对端
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final StringRedisTemplate redis;
    private final AgentStatusService agentStatusService;
    private final ChatSessionMapper sessionMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 跟踪当前实例上 "已标记 online 的 userId 集合". */
    private final Set<Long> localRegistered = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 标记用户上线. 首次调用 (本实例从未注册过该 userId) 时, 会向其会话对端广播 PRESENCE=ON.
     */
    public void online(Long userId, String role) {
        boolean firstOnThisInstance = localRegistered.add(userId);
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            redis.opsForSet().add(CommonConstants.REDIS_AGENT_ONLINE, String.valueOf(userId));
            if (firstOnThisInstance) {
                agentStatusService.setStatus(userId, CommonConstants.AGENT_ONLINE);
            }
        }
        redis.opsForValue().set("chat:user:online:" + userId, "1", java.time.Duration.ofMinutes(30));

        if (firstOnThisInstance) {
            eventPublisher.publishEvent(new PresenceChangedEvent(this, userId, role, true));
        }
    }

    /**
     * 标记下线. 仅当本实例确实之前是 online 状态时才广播.
     */
    public void offline(Long userId, String role) {
        boolean wasOnlineOnThisInstance = localRegistered.remove(userId);
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            redis.opsForSet().remove(CommonConstants.REDIS_AGENT_ONLINE, String.valueOf(userId));
            redis.delete(CommonConstants.REDIS_AGENT_STATUS + userId);
        }
        redis.delete("chat:user:online:" + userId);

        if (wasOnlineOnThisInstance) {
            eventPublisher.publishEvent(new PresenceChangedEvent(this, userId, role, false));
        }
    }

    public boolean isOnline(Long userId) {
        return Boolean.TRUE.equals(redis.hasKey("chat:user:online:" + userId));
    }

    public Set<String> onlineAgents() {
        return redis.opsForSet().members(CommonConstants.REDIS_AGENT_ONLINE);
    }

    /**
     * 智能选坐席:
     *   1) 优先选 ONLINE + 技能匹配 + 当前未分配会话的坐席
     *   2) 否则选 ONLINE 的任意坐席
     *   3) 没有返回 null
     */
    public Long pickAgent(String requiredSkill, java.util.function.Predicate<Long> isBusy) {
        Set<String> ids = onlineAgents();
        if (ids == null || ids.isEmpty()) return null;
        Long fallback = null;
        for (String sid : ids) {
            Long aid = Long.parseLong(sid);
            if (!agentStatusService.isAssignable(aid)) continue;
            if (isBusy.test(aid)) continue;
            if (requiredSkill == null || requiredSkill.isEmpty()) {
                return aid;
            }
            if (fallback == null) fallback = aid;
        }
        return fallback;
    }

    /**
     * 取该用户当前的活跃会话对端 id 列表 (用于 WsPushService 处理 PresenceChangedEvent 时推送).
     */
    public java.util.List<ChatSession> findActiveSessionsOf(Long userId, String role) {
        QueryWrapper<ChatSession> qw = new QueryWrapper<>();
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            qw.eq("agent_id", userId).in("status", "WAITING", "ACTIVE");
        } else {
            qw.eq("customer_id", userId).in("status", "WAITING", "ACTIVE");
        }
        return sessionMapper.selectList(qw);
    }
}