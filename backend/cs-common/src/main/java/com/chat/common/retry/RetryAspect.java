package com.chat.common.retry;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * RetryAspect - 重试切面 (轻量级, 0 依赖).
 * ----------------------------------------------------------------------------
 * 算法: 捕获 retryFor 中的异常, 等待 delay * (backoff^attempt) 后重试.
 *   第 1 次失败: 等待 200ms
 *   第 2 次失败: 等待 400ms
 *   第 3 次失败: 抛出原异常
 */
@Slf4j
@Aspect
@Component
public class RetryAspect {

    @Around("@annotation(retry)")
    public Object around(ProceedingJoinPoint pjp, Retryable retry) throws Throwable {
        long delay = retry.delayMs();
        Throwable lastError = null;
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                lastError = t;
                // 只重试 retryFor 指定的异常
                boolean shouldRetry = false;
                for (Class<? extends Throwable> c : retry.retryFor()) {
                    if (c.isInstance(t)) { shouldRetry = true; break; }
                }
                if (!shouldRetry || attempt >= retry.maxAttempts()) {
                    throw t;
                }
                log.warn("[Retry] {} attempt {}/{} failed: {} - retry in {}ms",
                        pjp.getSignature(), attempt, retry.maxAttempts(), t.getMessage(), delay);
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
                delay = (long) (delay * retry.backoff());
            }
        }
        throw lastError;
    }
}