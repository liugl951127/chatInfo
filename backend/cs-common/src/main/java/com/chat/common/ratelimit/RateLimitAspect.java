package com.chat.common.ratelimit;

import com.chat.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * RateLimitAspect - 限流切面 (Redis 滑动窗口实现).
 * ----------------------------------------------------------------------------
 * 算法: 每次请求用 INCR + EXPIRE 计数, 超限抛异常.
 *   - key = "ratelimit:{@RateLimit.key}:{userId or ip}"
 *   - 窗口内第 N+1 次 → 抛 RateLimitException
 *
 * 优点: 简单, 分布式友好, 0 内存开销.
 * 缺点: 滑动不严格 (固定窗口, 临界可能双倍), 高并发时 Redis QPS 高.
 * 阶段 2: 升级为令牌桶 (Lua 脚本).
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate redis;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        // 1. 计算 key
        String key = "ratelimit:" + rateLimit.key() + ":" + currentKey();
        // 2. INCR + EXPIRE
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(rateLimit.window()));
        }
        // 3. 判定
        if (count != null && count > rateLimit.permits()) {
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            log.warn("[RateLimit] {} hit limit {} > {} (retry {}s)", key, count, rateLimit.permits(), ttl);
            throw new RateLimitException("请求过于频繁, 请稍后再试", ttl == null ? rateLimit.window() : ttl);
        }
        return pjp.proceed();
    }

    /** 优先 userId, 兜底 IP */
    private String currentKey() {
        try {
            Long uid = UserContext.userId();
            if (uid != null) return "u" + uid;
        } catch (Exception ignored) {}
        return "anon";
    }
}