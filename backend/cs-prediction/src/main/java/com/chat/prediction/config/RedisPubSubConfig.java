package com.chat.prediction.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * RedisPubSubConfig - 占位配置.
 *
 * 阶段 1: cs-prediction 改用 Redis pub/sub 推 PREDICTION 事件
 *   (依赖 cs-common 的 StringRedisTemplate, 由 Spring Boot 自动装配).
 *
 * 这里不再额外定义 Bean, 避免循环引用.
 * 推送通过 StringRedisTemplate.convertAndSend(channel, json) 完成.
 * cs-im 端的 WsPushService 监听同一 channel, 收到后用 SimpMessagingTemplate
 * 推给本实例的 STOMP 客户端.
 */
@Slf4j
@Configuration
public class RedisPubSubConfig {
    // 不定义 bean, StringRedisTemplate 由 Spring Boot 自动提供
}
