package com.chat.im.service;

import com.chat.common.constant.CommonConstants;
import com.chat.common.dto.MessageDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket 推送服务:
 * <ul>
 *   <li>单实例: 直接 SimpMessagingTemplate.convertAndSendToUser</li>
 *   <li>多实例: 通过 Redis Pub/Sub 广播, 每个实例监听 chat:ws:push:* 推送本地连接</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsPushService implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper mapper;

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new PatternTopic(CommonConstants.REDIS_WS_PUSH_CHANNEL + "*"));
        log.info("[ws] redis pub/sub listener registered for {}", CommonConstants.REDIS_WS_PUSH_CHANNEL + "*");
    }

    /**
     * 推送给指定用户 (跨实例)。
     * @param userId 目标用户 id
     * @param payload 业务载荷 (会被转 JSON)
     */
    public void pushToUser(Long userId, Object payload) {
        String channel = CommonConstants.REDIS_WS_PUSH_CHANNEL + userId;
        try {
            String json = mapper.writeValueAsString(payload);
            redis.convertAndSend(channel, json);
        } catch (Exception e) {
            log.error("pushToUser failed", e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            // channel = chat:ws:push:{userId}
            String userIdStr = channel.substring(CommonConstants.REDIS_WS_PUSH_CHANNEL.length());
            Long uid = Long.parseLong(userIdStr);
            // STOMP convertAndSendToUser: 走 /user/{userId}/queue/messages
            messagingTemplate.convertAndSendToUser(String.valueOf(uid), "/queue/messages", parse(body));
        } catch (Exception e) {
            log.error("[ws] redis pub/sub parse failed: {}", body, e);
        }
    }

    private Object parse(String json) {
        try {
            return mapper.readValue(json, MessageDTO.class);
        } catch (Exception e) {
            return json;
        }
    }

    /** 坐席端: 广播新会话等待通知 */
    public void notifyAgentNewWaiting(Long sessionId) {
        messagingTemplate.convertAndSend("/topic/sessions/new", sessionId);
        log.info("[ws] broadcast new waiting session {}", sessionId);
    }
}