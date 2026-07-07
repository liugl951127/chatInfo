package com.chat.im.service;

import com.chat.common.constant.CommonConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 未读消息计数 (Redis Hash: userId:sessionId -> count).
 * <p>
 * 服务端推送消息给对方时 +1, 对方 ACK 已读后清零。
 */
@Service
@RequiredArgsConstructor
public class UnreadCounterService {

    private final StringRedisTemplate redis;

    public void incr(Long userId, Long sessionId) {
        redis.opsForValue().increment(key(userId, sessionId));
    }

    public long get(Long userId, Long sessionId) {
        String v = redis.opsForValue().get(key(userId, sessionId));
        return v == null ? 0 : Long.parseLong(v);
    }

    public void clear(Long userId, Long sessionId) {
        redis.delete(key(userId, sessionId));
    }

    private String key(Long userId, Long sessionId) {
        return CommonConstants.REDIS_UNREAD + userId + ":" + sessionId;
    }
}