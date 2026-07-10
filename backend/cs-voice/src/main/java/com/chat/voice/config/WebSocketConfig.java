package com.chat.voice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig - 智能电话 STOMP 配置.
 *
 * STOMP 频道:
 *   - /app/voice/{callId}/asr-stream      客户说话 -> ASR -> AI
 *   - /app/voice/{callId}/hangup          挂断
 *   - /user/queue/voice/{callId}/tts      AI 说话 -> TTS 推送给客户
 *   - /user/queue/voice/{callId}/transcript 实时转写推送
 *   - /user/queue/voice/{callId}/status   通话状态 (RINGING/CONNECTED/ENDED)
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
        registry.addEndpoint("/api/voice/ws").setAllowedOriginPatterns("*");
    }
}