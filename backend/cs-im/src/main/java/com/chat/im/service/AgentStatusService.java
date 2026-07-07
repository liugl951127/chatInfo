package com.chat.im.service;

import com.chat.common.constant.CommonConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 坐席状态服务:
 *   ONLINE (在线, 可分配) / BUSY (忙碌, 不分配) / AWAY (离开, 不分配)
 * <p>
 * 用 Redis Hash 存储, 便于批量查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStatusService {

    private final StringRedisTemplate redis;

    public void setStatus(Long agentId, String status) {
        redis.opsForValue().set(CommonConstants.REDIS_AGENT_STATUS + agentId, status);
        // 仅 ONLINE 计入"可分配"在线坐席集合
        if (CommonConstants.AGENT_ONLINE.equalsIgnoreCase(status)) {
            redis.opsForSet().add(CommonConstants.REDIS_AGENT_ONLINE, String.valueOf(agentId));
        } else {
            redis.opsForSet().remove(CommonConstants.REDIS_AGENT_ONLINE, String.valueOf(agentId));
        }
        log.info("[agent-status] agent={} -> {}", agentId, status);
    }

    public String getStatus(Long agentId) {
        String s = redis.opsForValue().get(CommonConstants.REDIS_AGENT_STATUS + agentId);
        return s == null ? CommonConstants.AGENT_ONLINE : s;
    }

    public boolean isAssignable(Long agentId) {
        return CommonConstants.AGENT_ONLINE.equalsIgnoreCase(getStatus(agentId));
    }

    /** 批量查询坐席状态 */
    public Map<Long, String> batchStatus(Set<Long> agentIds) {
        Map<Long, String> result = new HashMap<>();
        if (agentIds == null || agentIds.isEmpty()) return result;
        for (Long id : agentIds) {
            result.put(id, getStatus(id));
        }
        return result;
    }
}