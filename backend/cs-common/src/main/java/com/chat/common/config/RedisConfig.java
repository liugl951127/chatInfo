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
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(cf);
        return c;
    }
}