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
    // 不再直接依赖 SessionService, 通过 ApplicationEvent 解耦 (避免循环依赖)
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final SystemMessageService systemMessageService;
    private final PresenceService presenceService;
    private final OfflineMessageStore offlineStore;
    private final UnreadCounterService unreadCounterService;
    private final WsPushService wsPushService;
    // BotService 不需要注入, 静态方法调用 (避免循环依赖)

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

        // 机器人会话: 客户发"人工"或"真人" → 发布事件, SessionService 异步处理转人工
        if (s.getAgentId() == null && CommonConstants.ROLE_CUSTOMER.equals(role)
                && s.getIsBot() != null && s.getIsBot() == 1
                && CommonConstants.MSG_TEXT.equals(m.getMsgType())) {
            String text = in.getContent() == null ? "" : in.getContent().trim();
            if (containsTransferKeyword(text)) {
                try {
                    // 发事件给 SessionService (解耦, 避免循环依赖)
                    eventPublisher.publishEvent(new com.chat.im.event.TransferToHumanEvent(
                            this, senderId, sessionId, s.getSkillTag()));
                    log.info("[bot] transfer request published: customer={} session={}", senderId, sessionId);
                } catch (Exception e) {
                    log.warn("[bot] transfer publish failed", e);
                }
                return;  // 不再走 bot reply
            }
        }

        // 机器人会话: 客户发消息后自动回复 (sender_role=BOT 推到 /queue/messages)
        if (s.getAgentId() == null && CommonConstants.ROLE_CUSTOMER.equals(role)
                && s.getIsBot() != null && s.getIsBot() == 1
                && CommonConstants.MSG_TEXT.equals(m.getMsgType())) {
            try {
                String reply = BotService.reply(in.getContent());
                if (reply != null) {
                    // 模拟思考延迟 (50-200ms, 自然些)
                    long delay = 50L + (long) (Math.random() * 150);
                    Thread.sleep(delay);
                    sendBotReply(sessionId, s.getCustomerId(), reply);
                }
            } catch (Exception e) {
                log.warn("[bot] reply failed", e);
            }
        }
    }

    /** 检测客户是否请求转人工 ("人工" / "真人" / "转接" / "转人工" / "human") */
    private static final java.util.Set<String> TRANSFER_KW = java.util.Set.of(
        "人工", "真人", "转接", "转人工", "human", "agent", "坐席");
    private static boolean containsTransferKeyword(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase().trim();
        for (String kw : TRANSFER_KW) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * 发送机器人回复 (sender_role=BOT, msg_type=TEXT).
     * 插入 chat_message, push 给客户 /queue/messages.
     */
    private void sendBotReply(Long sessionId, Long customerId, String content) {
        ChatMessage bot = new ChatMessage();
        bot.setSessionId(sessionId);
        bot.setSenderId(0L);
        bot.setSenderRole(CommonConstants.ROLE_BOT);
        bot.setMsgType(CommonConstants.MSG_TEXT);
        bot.setContent(content);
        bot.setRecalled(0);
        bot.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(bot);

        MessageDTO dto = toDto(bot);
        // 更新会话 lastMessage
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            s.setLastMessage("[机器人] " + truncate(content));
            s.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(s);
        }
        // 推给客户
        messagingTemplate.convertAndSendToUser(String.valueOf(customerId), "/queue/messages", dto);
        log.info("[bot] reply session={} customer={} len={}", sessionId, customerId, content.length());
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

    /**
     * 发送系统消息已挪到 SystemMessageService (避免与 SessionService 循环依赖).
     * @deprecated 保留空方法防止外部调用报错
     */
    @Deprecated
    public void sendSystemMessage(Long sessionId, String content) {
        // delegate to SystemMessageService
        systemMessageService.sendSystemMessage(sessionId, content);
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

    /**
     * 搜索会话内消息 (按关键字 + 可选时间范围).
     *  - keyword: 匹配 content LIKE '%kw%' (中文也走 LIKE, MySQL 默认 utf8mb4_general_ci)
     *  - fromTs/toTs: 毫秒时间戳
     *  - limit: 最多返回条数 (默认 50, 上限 200)
     */
    public ApiResponse<List<MessageDTO>> search(Long sessionId, String keyword,
                                                Long fromTs, Long toTs, Integer limit) {
        Long uid = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        boolean allowed = uid.equals(s.getCustomerId()) || (s.getAgentId() != null && uid.equals(s.getAgentId()))
                || CommonConstants.ROLE_ADMIN.equalsIgnoreCase(UserContext.role());
        if (!allowed) return ApiResponse.fail(403, "无权查看");

        if (keyword == null || keyword.trim().isEmpty()) {
            return ApiResponse.fail(400, "关键字不能为空");
        }
        int n = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);

        LambdaQueryWrapper<ChatMessage> q = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .like(ChatMessage::getContent, keyword.trim())
                .orderByDesc(ChatMessage::getCreatedAt)
                .last("LIMIT " + n);
        if (fromTs != null) q.ge(ChatMessage::getCreatedAt,
                java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(fromTs), java.time.ZoneId.systemDefault()));
        if (toTs != null) q.le(ChatMessage::getCreatedAt,
                java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(toTs), java.time.ZoneId.systemDefault()));

        List<ChatMessage> rows = messageMapper.selectList(q);
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