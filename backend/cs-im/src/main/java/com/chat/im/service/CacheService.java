package com.chat.im.service;

import com.chat.common.constant.CommonConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * CacheService - 统一 Redis 缓存层 (V3.0 性能优化).
 * ----------------------------------------------------------------------------
 * 业务场景: 解决热点数据重复查询 DB 的问题.
 *   - 会话详情 (高频)
 *   - 客户在线状态 (超高频)
 *   - 在线坐席列表 (中频)
 *   - 模板回复 (低频但加载频繁)
 *
 * 策略:
 *   - getOrLoad(key, loader, ttl): 优先 Redis, miss 走 loader, loader 结果写 Redis
 *   - invalidate(key): 写操作后主动失效
 *   - invalidatePattern(pattern): 批量失效 (e.g. session:*:profile)
 *
 * 性能:
 *   - 缓存命中: < 1ms (Redis 内存)
 *   - 缓存 miss: 1-10ms (DB + 回写 Redis)
 *   - 命中率预期: 80%+ (热数据场景)
 *
 * TTL 设计:
 *   - 会话详情: 5min (变更多)
 *   - 模板回复: 30min (低变更)
 *   - 坐席列表: 1min (状态实时)
 *   - 健康分: 10min (定时重算)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final StringRedisTemplate redis;

    /**
     * Cache-Aside 模式: 优先 Redis, miss 走 loader, 写回缓存.
     *
     * @param key    Redis key
     * @param loader 数据加载器 (DB 查询, miss 时调用)
     * @param ttl    过期时间
     * @param <T>    数据类型
     * @return 缓存或加载的数据
     */
    public <T> T getOrLoad(String key, Supplier<T> loader, Duration ttl) {
        try {
            // 1) 先查 Redis
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                // cache hit, 简化: 仅存 String, 业务侧处理 JSON 序列化
                return deserialize(cached);
            }
            // 2) cache miss, 调 loader
            T value = loader.get();
            if (value != null) {
                // 3) 写回缓存
                redis.opsForValue().set(key, serialize(value), ttl);
            }
            return value;
        } catch (Exception e) {
            // 缓存异常降级: 直接走 loader, 不影响业务
            log.warn("[cache] getOrLoad failed, fallback to loader: key={}", key, e);
            return loader.get();
        }
    }

    /**
     * 主动失效.
     */
    public void invalidate(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("[cache] invalidate failed: key={}", key, e);
        }
    }

    /**
     * 批量失效 (SCAN + DEL).
     */
    public void invalidatePattern(String pattern) {
        try {
            var keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("[cache] invalidatePattern failed: pattern={}", pattern, e);
        }
    }

    /**
     * 直接写缓存 (不通过 loader).
     */
    public <T> void put(String key, T value, Duration ttl) {
        try {
            redis.opsForValue().set(key, serialize(value), ttl);
        } catch (Exception e) {
            log.warn("[cache] put failed: key={}", key, e);
        }
    }

    // ========== 序列化 (简单 String) ==========

    private String serialize(Object obj) {
        return obj == null ? null : obj.toString();
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(String s) {
        // 简化: 调用方需保证 key/value 类型匹配
        return (T) s;
    }
}
