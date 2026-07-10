package com.chat.common.config;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 公共 Bean: 仅暴露 RedisMessageListenerContainer (供跨实例 Pub/Sub 使用)。
 * <p>
 * 业务侧统一使用 Spring Boot 默认提供的 StringRedisTemplate, 不再自定义 RedisTemplate,
 * 避免与 RedisAutoConfiguration 默认 Bean 冲突。
 * <p>
 * 只在存在阻塞式 RedisConnectionFactory 的环境加载 (cs-gateway 用 reactive, 不需要)。
 * <p>
 * 依赖 RedisAutoConfiguration 先执行, 这样 {@link RedisConnectionFactory} bean 已经存在,
 * {@link ConditionalOnBean} 才会为 true。
 */
@Configuration
@ConditionalOnClass(RedisConnectionFactory.class)
@ConditionalOnBean(RedisConnectionFactory.class)
@AutoConfigureAfter(RedisAutoConfiguration.class)
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory cf) {
        // 匿名子类: 重写 isAutoStartup 返 false, 避免 Spring 生命周期调用 start()
        // 业务 (WsPushService) 需要时手动调 start()
        org.springframework.data.redis.listener.RedisMessageListenerContainer c =
            new org.springframework.data.redis.listener.RedisMessageListenerContainer() {
                @Override
                public boolean isAutoStartup() {
                    return false;  // 关键: 不让 Spring 启动时调 start()
                }
            };
        c.setConnectionFactory(cf);
        c.setRecoveryBackoff(new org.springframework.util.backoff.FixedBackOff(5000L, Long.MAX_VALUE));
        c.setRecoveryInterval(10000L);
        return c;
    }
}