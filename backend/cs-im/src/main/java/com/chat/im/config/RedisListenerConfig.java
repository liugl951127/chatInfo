package com.chat.im.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * RedisListenerConfig - Redis 消息监听容器配置.
 * ----------------------------------------------------------------------------
 * WsPushService 实现 MessageListener 接口 (用于跨实例 Redis pub/sub 推送),
 * 需要 RedisMessageListenerContainer Bean 来注册监听器.
 *
 * Spring Boot 自动配置会创建 connectionFactory, 但不会自动创建 listener container,
 * 需要手动声明.
 */
@Configuration
public class RedisListenerConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory cf) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        return container;
    }
}
