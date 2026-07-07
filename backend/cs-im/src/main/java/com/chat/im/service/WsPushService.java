package com.chat.im.service;

import com.chat.common.constant.CommonConstants;
import com.chat.common.dto.MessageDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 推送服务.
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
            String userIdStr = channel.substring(CommonConstants.REDIS_WS_PUSH_CHANNEL.length());
            Long uid = Long.parseLong(userIdStr);
            messagingTemplate.convertAndSendToUser(String.valueOf(uid), "/queue/messages", parse(body));
        } catch (Exception e) {
            log.error("[ws] redis pub/sub parse failed: {}", body, e);
        }
    }

    private Object parse(String json) {
        try { return mapper.readValue(json, MessageDTO.class); }
        catch (Exception e) { return json; }
    }

    /** 广播新会话等待 (含技能) */
    public void notifyAgentNewWaiting(Long sessionId, String skillTag) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "NEW_WAITING");
        payload.put("sessionId", sessionId);
        payload.put("skillTag", skillTag);
        messagingTemplate.convertAndSend("/topic/sessions/new", payload);
        log.info("[ws] broadcast NEW_WAITING session={} skill={}", sessionId, skillTag);
    }

    /** 通知会话转接 */
    public void notifySessionTransferred(Long sessionId, Long fromAgentId, Long toAgentId, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "TRANSFERRED");
        payload.put("sessionId", sessionId);
        payload.put("fromAgentId", fromAgentId);
        payload.put("toAgentId", toAgentId);
        payload.put("reason", reason);
        // 推给双方
        messagingTemplate.convertAndSendToUser(String.valueOf(fromAgentId), "/queue/events", payload);
        messagingTemplate.convertAndSendToUser(String.valueOf(toAgentId), "/queue/events", payload);
        // 客户也通知
        // (客户 id 在 controller 已知, 暂不在此查)
    }

    /** 客户/坐席 输入状态广播 */
    public void notifyTyping(Long sessionId, Long userId, String role, boolean typing) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "TYPING");
        payload.put("sessionId", sessionId);
        payload.put("userId", userId);
        payload.put("role", role);
        payload.put("typing", typing);
        // 群发到会话双方 (这里简化为全部在线用户)
        messagingTemplate.convertAndSend("/topic/typing/" + sessionId, payload);
    }

    /** 已读回执 (发回原发送者) */
    public void notifyRead(Long originalSenderId, Long messageId, Long readerId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "READ");
        payload.put("messageId", messageId);
        payload.put("readerId", readerId);
        messagingTemplate.convertAndSendToUser(String.valueOf(originalSenderId), "/queue/events", payload);
    }
}