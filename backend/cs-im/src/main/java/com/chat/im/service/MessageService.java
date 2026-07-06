package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.dto.MessageDTO;
import com.chat.common.security.UserContext;
import com.chat.im.entity.ChatMessage;
import com.chat.im.entity.ChatSession;
import com.chat.im.mapper.ChatMessageMapper;
import com.chat.im.mapper.ChatSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final PresenceService presenceService;
    private final OfflineMessageStore offlineStore;
    private final ObjectMapper mapper;

    /**
     * WebSocket / STOMP 入口: 由 MessageController#send 调用。
     * <p>
     * STOMP 消息不经过 Spring MVC, UserContext 是空的, 所以这里直接用 controller 传入的 sender 信息。
     */
    @Transactional
    public void handleIncoming(Long sessionId, MessageDTO in, Long senderId, String role) {
        if (senderId == null || role == null) throw new IllegalArgumentException("未登录");

        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) throw new IllegalArgumentException("会话不存在");
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            throw new IllegalStateException("会话已关闭");
        }

        // 持久化
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setSenderId(senderId);
        m.setSenderRole(role);
        m.setMsgType(in.getMsgType() == null ? CommonConstants.MSG_TEXT : in.getMsgType());
        m.setContent(in.getContent());
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);

        // 回填
        in.setId(m.getId());
        in.setSenderId(senderId);
        in.setSenderRole(role);
        in.setCreatedAt(m.getCreatedAt());

        // 更新会话 last_message
        s.setLastMessage(truncate(in.getContent()));
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(s);

        // 推送目标: 对端 (对方) + 自己 (用于多端同步)
        Long peerId = isCustomer(senderId, s) ? s.getAgentId() : s.getCustomerId();
        if (peerId != null) {
            if (presenceService.isOnline(peerId)) {
                messagingTemplate.convertAndSendToUser(String.valueOf(peerId), "/queue/messages", in);
            } else {
                offlineStore.push(peerId, in);
            }
        }
        // 回显给自己
        messagingTemplate.convertAndSendToUser(String.valueOf(senderId), "/queue/messages", in);

        log.debug("[msg] session={} from {} ({}) -> peer={}", sessionId, senderId, role, peerId);
    }

    /**
     * REST: 拉历史消息。
     */
    public ApiResponse<List<MessageDTO>> history(Long sessionId, Integer limit) {
        Long uid = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        boolean allowed = uid.equals(s.getCustomerId()) || uid.equals(s.getAgentId());
        if (!allowed) return ApiResponse.fail(403, "无权查看");

        int n = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<ChatMessage> rows = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .last("LIMIT " + n));
        return ApiResponse.ok(rows.stream().map(this::toDto).toList());
    }

    public MessageDTO toDto(ChatMessage m) {
        return new MessageDTO(m.getId(), m.getSessionId(), m.getSenderId(),
                m.getSenderRole(), m.getMsgType(), m.getContent(), m.getCreatedAt());
    }

    private boolean isCustomer(Long uid, ChatSession s) {
        return uid.equals(s.getCustomerId());
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}