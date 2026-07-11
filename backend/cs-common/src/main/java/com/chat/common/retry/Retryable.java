package com.chat.common.retry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Retryable - 错误重试注解 (轻量级, 0 依赖).
 * ----------------------------------------------------------------------------
 * 用法: @Retryable(maxAttempts = 3, delayMs = 200, backoff = 2.0,
 *                retryFor = {IOException.class, TimeoutException.class})
 *   - maxAttempts: 最大重试次数 (含首次)
 *   - delayMs:     首次重试延迟 (ms)
 *   - backoff:     退避倍数 (2.0 = 200ms, 400ms, 800ms)
 *   - retryFor:    触发重试的异常类型
 *
 * 不引入 spring-retry, 纯 AOP + Thread.sleep, 0 外部依赖.
 * 阶段 2: 替换为 Resilience4j (含熔断 + 限流 + 监控).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Retryable {
    int maxAttempts() default 3;
    long delayMs() default 200;
    double backoff() default 2.0;
    Class<? extends Throwable>[] retryFor() default {Exception.class};
}