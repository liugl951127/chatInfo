package com.chat.im.config;

import com.chat.im.interceptor.StompAuthChannelInterceptor;
import com.chat.im.interceptor.HandshakeAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket 配置。
 * <ul>
 *   <li>客户端连接: /ws/customer (客户) / /ws/agent (坐席)</li>
 *   <li>服务端入口: /app/send/{sessionId}</li>
 *   <li>客户端订阅: /user/queue/messages (服务端 convertAndSendToUser)</li>
 *   <li>广播订阅:  /topic/sessions/new  (坐席监听新会话)</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${chat.jwt.secret:change-me-please-use-a-32-byte-secret}")
    private String jwtSecret;

    @Bean
    public TaskScheduler stompHeartbeatScheduler() {
        ThreadPoolTaskScheduler ts = new ThreadPoolTaskScheduler();
        ts.setPoolSize(1);
        ts.setThreadNamePrefix("stomp-heartbeat-");
        ts.initialize();
        return ts;
    }

    @Bean
    public HandshakeAuthInterceptor handshakeAuthInterceptor() {
        return new HandshakeAuthInterceptor(jwtSecret);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        HandshakeAuthInterceptor hs = handshakeAuthInterceptor();
        registry.addEndpoint("/ws/customer")
                .addInterceptors(hs)
                .setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws/agent")
                .addInterceptors(hs)
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 简单内存 broker: 广播用 /topic, 点对点用 /user
        config.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{20_000, 20_000})
                .setTaskScheduler(stompHeartbeatScheduler());
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}