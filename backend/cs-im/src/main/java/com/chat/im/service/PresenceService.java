package com.chat.im.service;

import com.chat.common.constant.CommonConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 在线状态服务 (基于 Redis Set + ttl)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private final StringRedisTemplate redis;
    private final AgentStatusService agentStatusService;

    /** 标记用户在线 (按角色分桶) */
    public void online(Long userId, String role) {
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            redis.opsForSet().add(CommonConstants.REDIS_AGENT_ONLINE, String.valueOf(userId));
            // 默认状态设为 ONLINE (新连接的坐席)
            agentStatusService.setStatus(userId, CommonConstants.AGENT_ONLINE);
        }
        redis.opsForValue().set("chat:user:online:" + userId, "1", java.time.Duration.ofMinutes(30));
    }

    /** 标记下线 */
    public void offline(Long userId, String role) {
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            redis.opsForSet().remove(CommonConstants.REDIS_AGENT_ONLINE, String.valueOf(userId));
            redis.delete(CommonConstants.REDIS_AGENT_STATUS + userId);
        }
        redis.delete("chat:user:online:" + userId);
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
            // skill 匹配在 controller 那边再校验 (这里需要 UserMapper), 暂按 ONLINE 选一个
            if (fallback == null) fallback = aid;
        }
        return fallback;
    }
}