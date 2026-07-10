package com.chat.video.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig - 视频会话 STOMP 配置.
 *
 * STOMP 频道:
 *   - /app/video/{sid}/offer    客户端 -> 服务端: 发送 SDP offer
 *   - /app/video/{sid}/answer   客户端 -> 服务端: 发送 SDP answer
 *   - /app/video/{sid}/ice      客户端 -> 服务端: 发送 ICE candidate
 *   - /app/video/{sid}/hangup   客户端 -> 服务端: 挂断
 *   - /user/queue/video/...     服务端 -> 客户端: 信令中转
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/queue", "/topic", "/user");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 注册一个独立的 STOMP 端点 (供前端 WebRTC 信令通道)
        registry.addEndpoint("/api/video/ws")
                .setAllowedOriginPatterns("*");
    }
}