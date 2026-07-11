package com.chat.common.ratelimit;

import com.chat.common.constant.CommonConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * RateLimitRedis - 滑动窗口限流 (Redis 实现, V3.1 升级).
 * ----------------------------------------------------------------------------
 * 替代之前的固定窗口 (漏桶不够灵活).
 *
 * 算法 (Sliding Window):
 *   - 每次请求记一个时间戳到 Redis ZSET
 *   - 窗口内计数 > permits → 拒绝
 *   - 清理窗口外时间戳
 *
 * 性能: Redis 单实例可支撑 10K+ QPS
 *
 * 使用:
 *   boolean ok = rateLimitRedis.tryAcquire("login:" + ip, 5, 60);
 *   if (!ok) return ApiResponse.fail(429, "请求过于频繁");
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitRedis {

    private final StringRedisTemplate redis;

    /**
     * 滑动窗口: 限流检查.
     * @param key     限流 key (e.g. "login:192.168.1.1")
     * @param permits 窗口内允许的次数
     * @param window  窗口大小 (秒)
     * @return true 允许, false 拒绝
     */
    public boolean tryAcquire(String key, int permits, int window) {
        try {
            String fullKey = CommonConstants.REDIS_RATE_LIMIT_PREFIX + key;
            long now = System.currentTimeMillis();
            long windowStart = now - window * 1000L;
            // 1) 删除窗口外
            redis.opsForZSet().removeRangeByScore(fullKey, 0, windowStart);
            // 2) 当前窗口计数
            Long count = redis.opsForZSet().zCard(fullKey);
            if (count != null && count >= permits) {
                log.info("[ratelimit] denied key={} count={} permits={}", key, count, permits);
                return false;
            }
            // 3) 添加当前时间戳
            redis.opsForZSet().add(fullKey, String.valueOf(now) + ":" + Math.random(), now);
            // 4) 设置 key 过期 (避免内存泄漏)
            redis.expire(fullKey, Duration.ofSeconds(window + 10));
            return true;
        } catch (Exception e) {
            // Redis 异常降级: 放行
            log.warn("[ratelimit] tryAcquire failed, fallback to allow: key={}", key, e);
            return true;
        }
    }

    /**
     * 固定窗口 (简单计数, 用于 1s 内的瞬时限流).
     */
    public boolean tryAcquireFixedWindow(String key, int permits, int window) {
        try {
            String fullKey = CommonConstants.REDIS_RATE_LIMIT_PREFIX + key;
            Long count = redis.opsForValue().increment(fullKey);
            if (count != null && count == 1L) {
                redis.expire(fullKey, window, TimeUnit.SECONDS);
            }
            return count == null || count <= permits;
        } catch (Exception e) {
            return true;
        }
    }
}
