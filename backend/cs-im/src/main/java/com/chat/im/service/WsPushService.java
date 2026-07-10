package com.chat.im.service;

import com.chat.common.constant.CommonConstants;                               // 公共常量 (Redis 频道前缀)
import com.chat.common.dto.MessageDTO;                                         // 消息 DTO (反序列化用)
import com.chat.im.entity.ChatSession;                                          // chat_session 实体
import com.chat.im.event.PresenceChangedEvent;                                  // 在线状态变化事件
import com.fasterxml.jackson.databind.ObjectMapper;                            // JSON 序列化
import jakarta.annotation.PostConstruct;                                        // Spring 初始化钩子
import lombok.RequiredArgsConstructor;                                          // final 字段注入
import lombok.extern.slf4j.Slf4j;                                                // SLF4J 日志
import org.springframework.context.event.EventListener;                        // Spring 事件监听
import org.springframework.data.redis.connection.Message;                       // Redis pub/sub 消息
import org.springframework.data.redis.connection.MessageListener;              // Redis 订阅
import org.springframework.data.redis.core.StringRedisTemplate;                  // Redis 发布
import org.springframework.data.redis.listener.PatternTopic;                    // Redis 订阅模式
import org.springframework.data.redis.listener.RedisMessageListenerContainer;   // Redis 订阅容器
import org.springframework.messaging.simp.SimpMessagingTemplate;                // STOMP 推送
import org.springframework.stereotype.Service;                                   // Spring Bean

import java.nio.charset.StandardCharsets;                                          // UTF-8
import java.util.HashMap;                                                          // Map payload
import java.util.List;                                                              // List
import java.util.Map;                                                               // Map

/**
 * WsPushService - WebSocket 推送服务.
 * ----------------------------------------------------------------------------
 * 职责:
 *   - pushToUser: 通过 Redis pub/sub 跨实例推消息给指定用户
 *   - onMessage: Redis 订阅回调, 路由到 STOMP /queue/messages 或 /queue/events
 *   - 业务事件: NEW_WAITING / TRANSFERRED / CLOSED / PRESENCE / TYPING / READ / BOT_TRANSFER
 *   - onPresenceChanged: 监听在线状态变化, 广播给对端
 *
 * 跨实例设计:
 *   - 多个 cs-im 实例同时运行, Redis pub/sub 解决跨实例推送
 *   - 每个实例都订阅 REDIS_WS_PUSH_CHANNEL + "*"
 *   - pushToUser 通过 Redis 频道 publish, 所有实例 onMessage 收到
 *   - 本实例的 STOMP 用户订阅直接推, 其他实例的 simpUser 订阅经 onMessage 转发
 *
 * payload 路由:
 *   - 含 'type' 字段但无 'msgType' -> 业务事件 -> /queue/events
 *   - 含 'msgType' 字段 -> 聊天消息 -> /queue/messages
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsPushService implements MessageListener {

    /** STOMP 推送模板 (本地实例的 simpUser 订阅直接走这里) */
    private final SimpMessagingTemplate messagingTemplate;
    /** Redis 客户端 (跨实例 publish) */
    private final StringRedisTemplate redis;
    /** Redis 订阅容器 (管理 onMessage 回调) */
    private final RedisMessageListenerContainer listenerContainer;
    /** JSON 序列化 (Redis payload ↔ STOMP message) */
    private final ObjectMapper mapper;
    /** 在线状态服务 (PRESENCE 事件需要查活跃会话) */
    private final PresenceService presenceService;

    /**
     * Spring 启动时注册 Redis 订阅.
     * 监听所有 ws push 频道 (REDIS_WS_PUSH_CHANNEL + "*" 通配).
     */
    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new PatternTopic(CommonConstants.REDIS_WS_PUSH_CHANNEL + "*"));
        log.info("[ws] redis pub/sub listener registered for {}", CommonConstants.REDIS_WS_PUSH_CHANNEL + "*");
    }

    /**
     * 推送消息给指定用户 (跨实例).
     * 通过 Redis 频道 publish, 所有 cs-im 实例的 onMessage 会收到并路由到对应用户的 STOMP 订阅.
     * @param userId 目标用户 ID
     * @param payload 任意可序列化对象 (MessageDTO 或 Map)
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

    /**
     * Redis 订阅回调.
     * 路由 payload 到正确的 STOMP 频道:
     *   - 含 'type' 字段但无 'msgType' -> 业务事件 -> /queue/events
     *   - 含 'msgType' 字段 -> 聊天消息 -> /queue/messages
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            // 从频道名提取 userId (REDIS_WS_PUSH_CHANNEL + uid)
            String userIdStr = channel.substring(CommonConstants.REDIS_WS_PUSH_CHANNEL.length());
            Long uid = Long.parseLong(userIdStr);
            // 路由: 业务事件 -> /queue/events, 聊天消息 -> /queue/messages
            String destination = looksLikeEvent(body) ? "/queue/events" : "/queue/messages";
            messagingTemplate.convertAndSendToUser(String.valueOf(uid), destination, parse(body));
        } catch (Exception e) {
            log.error("[ws] redis pub/sub parse failed: {}", body, e);
        }
    }

    /**
     * 判断 JSON payload 是否是业务事件型.
     * 业务事件: 含 'type' 字段 (PRESENCE/READ/RECALL/TRANSFERRED/CLOSED/NEW_WAITING/BOT_TRANSFER/TYPING)
     * 聊天消息: 含 'msgType' 字段 (TEXT/IMAGE/VOICE/...)
     */
    private boolean looksLikeEvent(String json) {
        try {
            java.util.Map<?, ?> m = mapper.readValue(json, java.util.Map.class);
            return m != null && m.containsKey("type") && !m.containsKey("msgType");
        } catch (Exception e) {
            return false;                                                     // 反序列化失败当作消息
        }
    }

    /**
     * 反序列化 JSON -> 业务对象.
     * - 聊天消息 (含 msgType) -> MessageDTO (前端期望的强类型)
     * - 业务事件 -> Map (前端 onEvent 处理器按 type 字段分发)
     */
    private Object parse(String json) {
        try {
            java.util.Map<?, ?> m = mapper.readValue(json, java.util.Map.class);
            if (m != null && m.containsKey("msgType")) {
                return mapper.convertValue(m, MessageDTO.class);
            }
            return m;
        } catch (Exception e) {
            return json;                                                      // fallback: 原字符串
        }
    }

    /**
     * 广播新会话等待 (所有坐席的 /topic/sessions/new 频道).
     * @param sessionId 新等待会话 ID
     * @param skillTag 技能标签 (坐席按技能筛选)
     */
    public void notifyAgentNewWaiting(Long sessionId, String skillTag) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "NEW_WAITING");
        payload.put("sessionId", sessionId);
        payload.put("skillTag", skillTag);
        messagingTemplate.convertAndSend("/topic/sessions/new", payload);
        log.info("[ws] broadcast NEW_WAITING session={} skill={}", sessionId, skillTag);
    }

    /**
     * 通知会话转接.
     * 推送给转出方和转入方, 触发前端 UI 更新 (显示 "已转接" 提示).
     * @param sessionId 会话 ID
     * @param fromAgentId 原坐席 (转出方)
     * @param toAgentId 新坐席 (转入方)
     * @param reason 转接原因 (前端展示用)
     */
    public void notifySessionTransferred(Long sessionId, Long fromAgentId, Long toAgentId, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "TRANSFERRED");
        payload.put("sessionId", sessionId);
        payload.put("fromAgentId", fromAgentId);
        payload.put("toAgentId", toAgentId);
        payload.put("reason", reason);
        // 双方都推
        messagingTemplate.convertAndSendToUser(String.valueOf(fromAgentId), "/queue/events", payload);
        messagingTemplate.convertAndSendToUser(String.valueOf(toAgentId), "/queue/events", payload);
    }

    /**
     * 会话被关闭事件 (客户/坐席主动关闭).
     * 推送给会话双方:
     *   - 客户: 清空本地 session.value + 触发退出录制 + 显示 CSAT 评分
     *   - 坐席: 刷新会话列表 (该会话从 ACTIVE 移到 CLOSED)
     * @param sessionId 关闭的会话 ID
     * @param customerId 客户 ID (可能 null)
     * @param agentId 坐席 ID (可能 null)
     * @param reason CLOSED 原因 (CUSTOMER_EXIT / AGENT_CLOSE / TIMEOUT 等)
     */
    public void notifySessionClosed(Long sessionId, Long customerId, Long agentId, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CLOSED");
        payload.put("sessionId", sessionId);
        payload.put("reason", reason);
        payload.put("ts", System.currentTimeMillis());
        if (customerId != null) {
            messagingTemplate.convertAndSendToUser(String.valueOf(customerId), "/queue/events", payload);
        }
        if (agentId != null) {
            messagingTemplate.convertAndSendToUser(String.valueOf(agentId), "/queue/events", payload);
        }
    }

    /**
     * 机器人会话转人工事件 (推给客户).
     * 客户前端收到后:
     *   1) 停录制 (原 bot 会话)
     *   2) 刷 mine 列表
     *   3) 跳转到新人工会话
     * @param customerId 客户 ID
     * @param oldSessionId 旧 bot 会话 ID (已 CLOSED)
     * @param newSessionId 新人工会话 ID (WAITING/ACTIVE)
     */
    public void pushBotTransferEvent(Long customerId, Long oldSessionId, Long newSessionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "BOT_TRANSFER");
        payload.put("oldSessionId", oldSessionId);
        payload.put("newSessionId", newSessionId);
        payload.put("ts", System.currentTimeMillis());
        messagingTemplate.convertAndSendToUser(String.valueOf(customerId), "/queue/events", payload);
    }

    /**
     * 输入状态广播 (typing indicator).
     * @param sessionId 会话 ID
     * @param userId 输入方 ID
     * @param role 输入方角色 (CUSTOMER / AGENT)
     * @param typing true=正在输入 / false=停止
     */
    public void notifyTyping(Long sessionId, Long userId, String role, boolean typing) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "TYPING");
        payload.put("sessionId", sessionId);
        payload.put("userId", userId);
        payload.put("role", role);
        payload.put("typing", typing);
        messagingTemplate.convertAndSend("/topic/typing/" + sessionId, payload);
    }

    /**
     * 已读回执 (发回原消息发送者).
     * @param originalSenderId 原消息发送者 (被读的人)
     * @param messageId 被读的消息 ID
     * @param readerId 读消息的人 (显示 ✓✓)
     */
    public void notifyRead(Long originalSenderId, Long messageId, Long readerId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "READ");
        payload.put("messageId", messageId);
        payload.put("readerId", readerId);
        messagingTemplate.convertAndSendToUser(String.valueOf(originalSenderId), "/queue/events", payload);
    }

    /**
     * 监听用户在线状态变化, 广播给所有活跃会话的对端.
     * 触发链: PresenceService.updatePresence() -> publishEvent ->
     *         onPresenceChanged -> pushToUser -> Redis pub/sub -> STOMP
     */
    @EventListener
    public void onPresenceChanged(PresenceChangedEvent event) {
        try {
            // 查该用户所有 ACTIVE 会话
            List<ChatSession> sessions = presenceService.findActiveSessionsOf(event.getUserId(), event.getRole());
            for (ChatSession s : sessions) {
                // 找对端: 客户 -> 坐席, 坐席 -> 客户
                Long peerId = CommonConstants.ROLE_AGENT.equalsIgnoreCase(event.getRole())
                    ? s.getCustomerId()
                    : s.getAgentId();
                if (peerId == null) continue;
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "PRESENCE");
                payload.put("userId", event.getUserId());
                payload.put("role", event.getRole());
                payload.put("online", event.isOnline());
                payload.put("sessionId", s.getId());
                payload.put("ts", System.currentTimeMillis());
                // 跨实例推送 (其他实例的 simpUser 订阅通过 Redis 转发)
                pushToUser(peerId, payload);
            }
            log.debug("[ws] PRESENCE broadcast user={} online={} peers={}",
                event.getUserId(), event.isOnline(), sessions.size());
        } catch (Exception e) {
            log.error("[ws] PRESENCE broadcast failed", e);
        }
    }

    /**
     * 保留旧接口以备外部调用. 推荐用 onPresenceChanged 事件机制.
     * @deprecated 直接调用 pushToUser 更简洁
     */
    @Deprecated
    public void notifyPresence(Long peerId, Long userId, String role, boolean online, Long sessionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "PRESENCE");
        payload.put("userId", userId);
        payload.put("role", role);
        payload.put("online", online);
        payload.put("sessionId", sessionId);
        payload.put("ts", System.currentTimeMillis());
        pushToUser(peerId, payload);
    }
}