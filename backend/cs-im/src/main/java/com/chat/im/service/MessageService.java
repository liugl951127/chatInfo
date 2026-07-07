package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.dto.MessageDTO;
import com.chat.common.security.UserContext;
import com.chat.im.entity.ChatMessage;
import com.chat.im.entity.ChatSession;
import com.chat.im.entity.MessageReceipt;
import com.chat.im.mapper.ChatMessageMapper;
import com.chat.im.mapper.ChatSessionMapper;
import com.chat.im.mapper.MessageReceiptMapper;
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
    private final MessageReceiptMapper receiptMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final PresenceService presenceService;
    private final OfflineMessageStore offlineStore;
    private final UnreadCounterService unreadCounterService;
    private final WsPushService wsPushService;

    /** 撤回窗口 (2 分钟) */
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    @Transactional
    public void handleIncoming(Long sessionId, MessageDTO in, Long senderId, String role) {
        if (senderId == null || role == null) throw new IllegalArgumentException("未登录");

        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) throw new IllegalArgumentException("会话不存在");
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            throw new IllegalStateException("会话已关闭");
        }

        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setSenderId(senderId);
        m.setSenderRole(role);
        m.setMsgType(in.getMsgType() == null ? CommonConstants.MSG_TEXT : in.getMsgType());
        m.setContent(in.getContent());
        m.setRecalled(0);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);

        in.setId(m.getId());
        in.setSenderId(senderId);
        in.setSenderRole(role);
        in.setCreatedAt(m.getCreatedAt());

        s.setLastMessage(truncate(in.getContent()));
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(s);

        Long peerId = isCustomer(senderId, s) ? s.getAgentId() : s.getCustomerId();
        if (peerId != null) {
            // 未读 +1
            unreadCounterService.incr(peerId, sessionId);
            if (presenceService.isOnline(peerId)) {
                messagingTemplate.convertAndSendToUser(String.valueOf(peerId), "/queue/messages", in);
            } else {
                offlineStore.push(peerId, in);
            }
        }
        messagingTemplate.convertAndSendToUser(String.valueOf(senderId), "/queue/messages", in);

        log.debug("[msg] session={} from {} ({}) -> peer={}", sessionId, senderId, role, peerId);
    }

    /**
     * 撤回消息 (2 分钟内).
     */
    @Transactional
    public ApiResponse<Void> recall(Long messageId) {
        Long uid = UserContext.userId();
        ChatMessage m = messageMapper.selectById(messageId);
        if (m == null) return ApiResponse.fail(404, "消息不存在");
        if (!uid.equals(m.getSenderId())) return ApiResponse.fail(403, "只能撤回自己的消息");
        if (m.getRecalled() != null && m.getRecalled() == 1) return ApiResponse.fail(409, "消息已撤回");
        if (System.currentTimeMillis() - timestampMs(m.getCreatedAt()) > RECALL_WINDOW_MS) {
            return ApiResponse.fail(409, "超过 2 分钟, 无法撤回");
        }
        m.setRecalled(1);
        m.setContent("对方撤回了一条消息");
        m.setMsgType(CommonConstants.MSG_RECALL);
        messageMapper.updateById(m);

        // 广播撤回事件
        ChatSession s = sessionMapper.selectById(m.getSessionId());
        if (s != null) {
            Long peerId = isCustomer(uid, s) ? s.getAgentId() : s.getCustomerId();
            if (peerId != null) {
                wsPushService.pushToUser(peerId, java.util.Map.of(
                        "type", "RECALL",
                        "messageId", messageId,
                        "sessionId", m.getSessionId()
                ));
            }
            wsPushService.pushToUser(uid, java.util.Map.of(
                    "type", "RECALL",
                    "messageId", messageId,
                    "sessionId", m.getSessionId()
            ));
        }
        return ApiResponse.ok();
    }

    /**
     * 标记消息已读 (写 receipt 表, 并通知发送方).
     */
    @Transactional
    public ApiResponse<Void> markRead(Long messageId) {
        Long uid = UserContext.userId();
        ChatMessage m = messageMapper.selectById(messageId);
        if (m == null) return ApiResponse.fail(404, "消息不存在");

        // 幂等插入
        try {
            MessageReceipt r = new MessageReceipt();
            r.setMessageId(messageId);
            r.setUserId(uid);
            r.setReadAt(LocalDateTime.now());
            receiptMapper.insert(r);
        } catch (Exception ignore) {
            // 已存在 unique 冲突, 忽略
        }
        // 清未读 + 通知发送方
        unreadCounterService.clear(uid, m.getSessionId());
        if (!uid.equals(m.getSenderId())) {
            wsPushService.notifyRead(m.getSenderId(), messageId, uid);
        }
        return ApiResponse.ok();
    }

    /** 会话所有消息标已读 */
    @Transactional
    public ApiResponse<Void> markSessionRead(Long sessionId) {
        Long uid = UserContext.userId();
        unreadCounterService.clear(uid, sessionId);
        // 拉会话消息, 给每条非自己发的消息写 receipt + 通知对方
        List<ChatMessage> msgs = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .last("LIMIT 500"));
        for (ChatMessage m : msgs) {
            if (uid.equals(m.getSenderId())) continue;
            if (m.getRecalled() != null && m.getRecalled() == 1) continue;
            try {
                MessageReceipt r = new MessageReceipt();
                r.setMessageId(m.getId());
                r.setUserId(uid);
                r.setReadAt(LocalDateTime.now());
                receiptMapper.insert(r);
            } catch (Exception ignore) { /* unique 冲突, 已读过 */ }
            wsPushService.notifyRead(m.getSenderId(), m.getId(), uid);
        }
        return ApiResponse.ok();
    }

    /**
     * 输入状态广播 (用户在输入框敲字时调用).
     */
    public void typing(Long sessionId, boolean typing) {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return;
        Long peerId = isCustomer(uid, s) ? s.getAgentId() : s.getCustomerId();
        if (peerId != null) {
            wsPushService.notifyTyping(sessionId, uid, role, typing);
        }
    }

    public ApiResponse<List<MessageDTO>> history(Long sessionId, Integer limit) {
        Long uid = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        boolean allowed = uid.equals(s.getCustomerId()) || (s.getAgentId() != null && uid.equals(s.getAgentId()));
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

    private long timestampMs(LocalDateTime t) {
        return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}