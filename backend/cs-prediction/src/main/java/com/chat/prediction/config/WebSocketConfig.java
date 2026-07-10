package com.chat.prediction.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig - STOMP 配置 (推送预见式事件).
 * 与 cs-im 共享同一套 STOMP 频道: /user/{uid}/queue/events
 *
 * 注: cs-prediction 是 push-only, 不接收 send 消息, 无需 StompAuthChannelInterceptor.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 简单内存 broker (阶段 1)
        // 阶段 2 升级: redis relay (与 cs-im 跨实例推送共用)
        config.enableSimpleBroker("/queue", "/topic");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // cs-prediction 不暴露 STOMP 端点 (只做后端推送, 前端连 cs-im)
        // 保留空配置以启用 @EnableWebSocketMessageBroker
    }
}