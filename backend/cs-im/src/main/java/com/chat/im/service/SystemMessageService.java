package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chat.common.constant.CommonConstants;
import com.chat.common.dto.MessageDTO;
import com.chat.im.entity.ChatMessage;
import com.chat.im.entity.ChatSession;
import com.chat.im.mapper.ChatMessageMapper;
import com.chat.im.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 系统消息服务 (独立, 避免 MessageService<->SessionService 循环依赖).
 *  - insert chat_message (sender_role=SYSTEM, msg_type=SYSTEM)
 *  - push to all participants via /queue/messages
 *  - update session.lastMessage
 *  - called by both MessageService (bot reply) and SessionService (claim/transfer/close)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMessageService {

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final SimpMessagingTemplate messagingTemplate;

    public MessageDTO sendSystemMessage(Long sessionId, String content) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setSenderId(0L);
        m.setSenderRole(CommonConstants.ROLE_SYSTEM);
        m.setMsgType(CommonConstants.MSG_SYSTEM);
        m.setContent(content);
        m.setRecalled(0);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);

        // 更新 session.lastMessage
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            String prev = s.getLastMessage() == null ? "" : s.getLastMessage();
            // 系统消息加 [系统] 前缀便于辨识
            if (!prev.startsWith("[系统]")) {
                s.setLastMessage("[系统] " + truncate(content));
            } else {
                s.setLastMessage("[系统] " + truncate(content));
            }
            s.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(s);
        }

        MessageDTO dto = toDto(m);
        // 推给所有参与方 (customer + agent if any)
        if (s != null) {
            if (s.getCustomerId() != null) {
                messagingTemplate.convertAndSendToUser(String.valueOf(s.getCustomerId()), "/queue/messages", dto);
            }
            if (s.getAgentId() != null) {
                messagingTemplate.convertAndSendToUser(String.valueOf(s.getAgentId()), "/queue/messages", dto);
            }
        }
        return dto;
    }

    private MessageDTO toDto(ChatMessage m) {
        MessageDTO d = new MessageDTO();
        d.setId(m.getId());
        d.setSessionId(m.getSessionId());
        d.setSenderId(m.getSenderId());
        d.setSenderRole(m.getSenderRole());
        d.setMsgType(m.getMsgType());
        d.setContent(m.getContent());
        d.setCreatedAt(m.getCreatedAt());
        return d;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
