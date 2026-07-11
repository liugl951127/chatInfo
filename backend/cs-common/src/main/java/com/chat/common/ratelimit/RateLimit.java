package com.chat.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RateLimit - 限流注解 (基于 Redis 滑动窗口).
 * ----------------------------------------------------------------------------
 * 用法: @RateLimit(key = "login", permits = 5, window = 60)
 *   - key: 限流维度, 通常是接口名或用户ID
 *   - permits: 窗口内最大次数
 *   - window: 窗口秒数
 *   - 超过限流 → 抛 RateLimitException → 429
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    String key() default "";
    int permits() default 10;
    int window() default 60;  // 秒
}