package com.chat.common.ratelimit;

/**
 * RateLimitException - 限流异常.
 * 触发场景: 同一 key 在窗口内超过 permits 上限.
 * 业务层: 捕获后返回 HTTP 429 (Too Many Requests).
 */
public class RateLimitException extends RuntimeException {
    private final long retryAfterSeconds;

    public RateLimitException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}