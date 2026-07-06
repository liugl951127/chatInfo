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

    /** 标记用户在线 (按角色分桶) */
    public void online(Long userId, String role) {
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            redis.opsForSet().add(CommonConstants.REDIS_AGENT_ONLINE, String.valueOf(userId));
        }
        redis.opsForValue().set("chat:user:online:" + userId, "1", java.time.Duration.ofMinutes(30));
    }

    /** 标记下线 */
    public void offline(Long userId, String role) {
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            redis.opsForSet().remove(CommonConstants.REDIS_AGENT_ONLINE, String.valueOf(userId));
        }
        redis.delete("chat:user:online:" + userId);
    }

    public boolean isOnline(Long userId) {
        return Boolean.TRUE.equals(redis.hasKey("chat:user:online:" + userId));
    }

    public Set<String> onlineAgents() {
        return redis.opsForSet().members(CommonConstants.REDIS_AGENT_ONLINE);
    }

    public Long pickIdleAgent() {
        Set<String> ids = onlineAgents();
        if (ids == null || ids.isEmpty()) return null;
        // 简单: 取 hash 最小的那个, 保证均衡
        return ids.stream().map(Long::parseLong).sorted().findFirst().orElse(null);
    }
}