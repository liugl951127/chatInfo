package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;       // MP Lambda 构造器
import com.chat.common.api.ApiResponse;                                        // 统一响应
import com.chat.common.constant.CommonConstants;                               // 公共常量
import com.chat.common.dto.MessageDTO;                                         // 消息 DTO
import com.chat.common.security.UserContext;                                   // ThreadLocal
import com.chat.im.entity.ChatMessage;                                          // chat_message 实体
import com.chat.im.entity.ChatSession;                                          // chat_session 实体
import com.chat.im.entity.MessageReceipt;                                       // 已读回执实体
import com.chat.im.mapper.ChatMessageMapper;                                     // DAO
import com.chat.im.mapper.ChatSessionMapper;                                     // DAO
import com.chat.im.mapper.MessageReceiptMapper;                                  // 已读 DAO
import lombok.RequiredArgsConstructor;                                          // final 注入
import lombok.extern.slf4j.Slf4j;                                                // 日志
import org.springframework.messaging.simp.SimpMessagingTemplate;                  // STOMP 推送
import org.springframework.stereotype.Service;                                   // Spring Bean
import org.springframework.transaction.annotation.Transactional;                  // 事务

import java.time.LocalDateTime;                                                  // 时间戳
import java.util.List;                                                            // List

/**
 * MessageService - 消息核心业务服务.
 * ----------------------------------------------------------------------------
 * 职责:
 *   - handleIncoming: STOMP/REST 收消息 -> 持久化 + 推送 + 未读 +1
 *   - 机器人会话: 客户发文本时自动调用 BotService.reply() + sendBotReply()
 *   - 客户发 "人工"/"真人" 等关键词: 发布 TransferToHumanEvent (SessionService 异步消费)
 *   - history: 拉历史消息 (权限检查: 客户/坐席/管理员)
 *   - search: 关键字搜索 + 时间范围
 *   - recall: 2 分钟内撤回自己的消息
 *   - read 标记: 消息已读 + 会话已读
 *   - typing: 打字事件处理
 *
 * 依赖:
 *   - BotService (静态调用) - 机器人回复生成
 *   - SystemMessageService - 系统消息
 *   - ApplicationEventPublisher - 发布转人工事件 (解耦 SessionService)
 *   - WsPushService / messagingTemplate - STOMP 推送
 *   - PresenceService / OfflineMessageStore / UnreadCounterService
 *
 * 设计:
 *   - 撤回窗口 2 分钟 (RECALL_WINDOW_MS), 超时返 409
 *   - 推送双发: sender 收到自己的消息 (用于多端同步), peer 收到对方消息
 *   - 未读递增只在 peer 在线时 +1, 离线存 OfflineMessageStore
 */
@Slf4j                                                                          // 自动生成 log
@Service                                                                       // Spring Bean
@RequiredArgsConstructor                                                       // final 字段注入
public class MessageService {

    /** chat_message 表 DAO */
    private final ChatMessageMapper messageMapper;
    /** chat_session 表 DAO */
    private final ChatSessionMapper sessionMapper;
    /** message_receipt 表 DAO (已读回执) */
    private final MessageReceiptMapper receiptMapper;
    /** STOMP 推送模板 (向 /user/{uid}/queue/messages 推送) */
    private final SimpMessagingTemplate messagingTemplate;
    /** 不直接依赖 SessionService, 通过 ApplicationEvent 解耦 (避免循环依赖) */
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    /** 系统消息服务 (独立, 避免循环依赖) */
    private final SystemMessageService systemMessageService;
    /** 用户在线状态服务 */
    private final PresenceService presenceService;
    /** 离线消息存储 (peer 离线时暂存) */
    private final OfflineMessageStore offlineStore;
    /** 未读计数服务 (peer 在线 +1) */
    private final UnreadCounterService unreadCounterService;
    /** STOMP 业务事件推送 (TRANSFERRED / CLOSED / BOT_TRANSFER 等) */
    private final WsPushService wsPushService;
    // BotService 不需要注入, 静态方法调用 (避免循环依赖)

    /** 撤回窗口 (2 分钟, 超时不允许撤回) */
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    /**
     * 处理入站消息 (STOMP send 入口).
     * ----------------------------------------------------------------------------
     * 流程:
     *   1) 校验: sender / 角色 / 会话存在 / 会话未关闭
     *   2) 持久化 chat_message
     *   3) 更新 chat_session.lastMessage (会话列表预览)
     *   4) 推送: peer 在线 -> STOMP, 离线 -> OfflineMessageStore
     *   5) 推送: sender (多端同步, 自己发的也能在另一端看到)
     *   6) 机器人会话特化: 客户发"人工"等关键词 -> 发布 TransferToHumanEvent
     *   7) 机器人会话特化: 普通文本 -> BotService.reply() 自动回复
     */
    @Transactional
    public void handleIncoming(Long sessionId, MessageDTO in, Long senderId, String role) {
        if (senderId == null || role == null) throw new IllegalArgumentException("未登录");

        // 1) 校验会话状态
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) throw new IllegalArgumentException("会话不存在");
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            throw new IllegalStateException("会话已关闭");
        }

        // 2) 持久化消息
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setSenderId(senderId);
        m.setSenderRole(role);
        m.setMsgType(in.getMsgType() == null ? CommonConstants.MSG_TEXT : in.getMsgType());
        m.setContent(in.getContent());
        m.setRecalled(0);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);

        // 把 DB 生成的 id 和时间回填到 DTO (后续推送用)
        in.setId(m.getId());
        in.setSenderId(senderId);
        in.setSenderRole(role);
        in.setCreatedAt(m.getCreatedAt());

        // 3) 更新会话 lastMessage (用于会话列表预览)
        s.setLastMessage(truncate(in.getContent()));
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(s);

        // 4) + 5) 推送给 peer 和 sender
        Long peerId = isCustomer(senderId, s) ? s.getAgentId() : s.getCustomerId();
        if (peerId != null) {
            // 未读 +1 (peer 在线时)
            unreadCounterService.incr(peerId, sessionId);
            if (presenceService.isOnline(peerId)) {
                // 在线: 直接 STOMP 推送
                messagingTemplate.convertAndSendToUser(String.valueOf(peerId), "/queue/messages", in);
            } else {
                // 离线: 暂存, 上线后 drainOffline 取
                offlineStore.push(peerId, in);
            }
        }
        // sender 自己的多端同步 (例如客户手机+电脑同时登录)
        messagingTemplate.convertAndSendToUser(String.valueOf(senderId), "/queue/messages", in);

        log.debug("[msg] session={} from {} ({}) -> peer={}", sessionId, senderId, role, peerId);

        // 6) 机器人会话: 客户发"人工"/"真人" 等转人工关键词 -> 发布事件
        //    SessionService.onTransferToHumanEvent @Async 消费
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
                // 转人工时不再走 bot reply
                return;
            }
        }

        // 7) 机器人会话: 普通文本 -> BotService.reply() 自动回复
        if (s.getAgentId() == null && CommonConstants.ROLE_CUSTOMER.equals(role)
                && s.getIsBot() != null && s.getIsBot() == 1
                && CommonConstants.MSG_TEXT.equals(m.getMsgType())) {
            try {
                String reply = BotService.reply(in.getContent());
                if (reply != null) {
                    // 模拟思考延迟 (50-200ms, 自然些, 避免秒回显得机器人)
                    long delay = 50L + (long) (Math.random() * 150);
                    Thread.sleep(delay);
                    sendBotReply(sessionId, s.getCustomerId(), reply);
                }
            } catch (Exception e) {
                log.warn("[bot] reply failed", e);
            }
        }
    }

    /**
     * 检测文本是否包含转人工关键词.
     * 触发后 MessageService 发布 TransferToHumanEvent, SessionService 异步处理.
     * @param text 用户消息 (已 trim)
     * @return true = 包含 "人工"/"真人"/"转接"/"human" 等关键词
     */
    private static final java.util.Set<String> TRANSFER_KW = java.util.Set.of(
        "人工", "真人", "转接", "转人工", "human", "agent", "坐席");
    private static boolean containsTransferKeyword(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase().trim();                            // 不区分大小写
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