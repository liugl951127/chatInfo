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
 * MessageService - 消息核心业务服务 (IM 中枢, 收发 + 撤回 + 已读).
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
 *
 * @author V3 Team
 * @since 2025-09
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
    /** ApplicationEventPublisher (发布转人工事件, 解耦 SessionService, 避免循环依赖) */
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

    /**
     * 撤回窗口 (2 分钟, 超时不允许撤回).
     * <p>
     * 设计: 2 分钟是行业惯例 (微信/QQ 都用 2 分钟), 防止滥用.
     */
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    /**
     * 处理入站消息 (STOMP send 入口, 核心方法).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 校验: sender / 角色 / 会话存在 / 会话未关闭
     *   step 2: 持久化 chat_message
     *   step 3: 更新 chat_session.lastMessage (会话列表预览)
     *   step 4: 推送: peer 在线 -> STOMP, 离线 -> OfflineMessageStore
     *   step 5: 推送: sender (多端同步, 自己发的也能在另一端看到)
     *   step 6: 机器人会话特化: 客户发"人工"等关键词 -> 发布 TransferToHumanEvent
     *   step 7: 机器人会话特化: 普通文本 -> BotService.reply() 自动回复
     *
     * @param sessionId 会话 ID. 业务含义: 消息归属
     *                  取值范围: Long > 0 且存在 + ACTIVE
     *                  影响: 决定消息归属
     * @param in        消息 DTO. 业务含义: 内容/类型
     *                  取值范围: content 非空, msgType 必填
     *                  影响: 持久化 + 推送
     * @param senderId  发送者 ID. 业务含义: 消息作者
     *                 取值范围: Long > 0
     *                 影响: 决定谁发的 + 多端同步推送
     * @param role      发送者角色. 业务含义: CUSTOMER/AGENT/BOT
     *                 取值范围: CommonConstants.ROLE_*
     *                 影响: 决定未读推送给谁 + bot 转人工识别
     * @throws IllegalArgumentException 未登录 / 会话不存在
     * @throws IllegalStateException    会话已关闭
     */
    @Transactional
    public void handleIncoming(Long sessionId, MessageDTO in, Long senderId, String role) {
        if (senderId == null || role == null) throw new IllegalArgumentException("未登录");

        // step 1: 校验会话状态
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) throw new IllegalArgumentException("会话不存在");
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            throw new IllegalStateException("会话已关闭");
        }

        // step 2: 持久化消息
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

        // step 3: 更新会话 lastMessage (用于会话列表预览)
        s.setLastMessage(truncate(in.getContent()));
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(s);

        // step 4 + 5: 推送给 peer 和 sender
        // 计算 peer (对方): 客户发→坐席收, 坐席发→客户收
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

        // step 6: 机器人会话: 客户发"人工"/"真人" 等转人工关键词 -> 发布事件
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

        // step 7: 机器人会话: 普通文本 -> BotService.reply() 自动回复
        if (s.getAgentId() == null && CommonConstants.ROLE_CUSTOMER.equals(role)
                && s.getIsBot() != null && s.getIsBot() == 1
                && CommonConstants.MSG_TEXT.equals(m.getMsgType())) {
            try {
                // 调 BotService 生成回复 (静态方法, 避免循环依赖)
                String reply = BotService.reply(in.getContent());
                if (reply != null) {
                    // 模拟思考延迟 (50-200ms, 自然些, 避免秒回显得机器人)
                    long delay = 50L + (long) (Math.random() * 150);
                    Thread.sleep(delay);
                    // 发送 bot 回复 (sender=BOT, 推给客户)
                    sendBotReply(sessionId, s.getCustomerId(), reply);
                }
            } catch (Exception e) {
                log.warn("[bot] reply failed", e);
            }
        }
    }

    /**
     * 转人工关键词集合 (不区分大小写, 用于自动转人工).
     * <p>
     * 触发后 MessageService 发布 TransferToHumanEvent, SessionService 异步处理.
     */
    private static final java.util.Set<String> TRANSFER_KW = java.util.Set.of(
        "人工", "真人", "转接", "转人工", "human", "agent", "坐席");

    /**
     * 检测文本是否包含转人工关键词.
     *
     * @param text 用户消息 (已 trim)
     *             业务含义: 待检测消息
     *             取值范围: 任意字符串
     *             影响: 决定是否触发转人工
     * @return true=包含 "人工"/"真人"/"转接"/"human"/"agent"/"坐席" 等关键词
     */
    private static boolean containsTransferKeyword(String text) {
        if (text == null || text.isEmpty()) return false;
        // 统一小写, 关键词统一小写比对 (支持英文 HUMAN/Human/human)
        String lower = text.toLowerCase().trim();
        for (String kw : TRANSFER_KW) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * 发送机器人回复 (sender_role=BOT, msg_type=TEXT).
     * <p>
     * 算法:
     *   step 1: 持久化 chat_message (sender=0, role=BOT)
     *   step 2: 更新会话 lastMessage (加 [机器人] 前缀)
     *   step 3: 推 STOMP 给客户 /queue/messages
     *
     * @param sessionId  会话 ID. 业务含义: 消息归属
     *                   取值范围: Long > 0
     *                   影响: 写入 chat_message
     * @param customerId 客户 ID. 业务含义: 推送目标
     *                   取值范围: Long > 0
     *                   影响: 推送给谁
     * @param content    回复内容. 业务含义: 机器人回复
     *                   取值范围: 任意字符串
     *                   影响: 持久化 + 推送
     */
    private void sendBotReply(Long sessionId, Long customerId, String content) {
        // step 1: 持久化
        ChatMessage bot = new ChatMessage();
        bot.setSessionId(sessionId);
        bot.setSenderId(0L);                                                  // 0 = 系统/BOT
        bot.setSenderRole(CommonConstants.ROLE_BOT);
        bot.setMsgType(CommonConstants.MSG_TEXT);
        bot.setContent(content);
        bot.setRecalled(0);
        bot.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(bot);

        // 转 DTO 用于推送
        MessageDTO dto = toDto(bot);
        // step 2: 更新会话 lastMessage (加前缀便于 UI 区分)
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            s.setLastMessage("[机器人] " + truncate(content));
            s.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(s);
        }
        // step 3: 推 STOMP 给客户
        messagingTemplate.convertAndSendToUser(String.valueOf(customerId), "/queue/messages", dto);
        log.info("[bot] reply session={} customer={} len={}", sessionId, customerId, content.length());
    }

    /**
     * 撤回消息 (2 分钟内).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 校验: 消息存在 + 是自己发的 + 未撤回 + 2 分钟内
     *   step 2: 标 recalled=1, 内容改为"对方撤回了一条消息", msg_type=RECALL
     *   step 3: 推 RECALL 事件给双方 (前端隐藏气泡)
     *
     * @param messageId 消息 ID. 业务含义: 要撤回的消息
     *                  取值范围: Long > 0 且存在
     *                  影响: 撤回后内容变占位符
     * @return ApiResponse<Void>
     *         - 成功: code=0
     *         - 失败: 403(非自己) / 404(不存在) / 409(已撤/超时)
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
        // 标撤回 + 占位文案
        m.setRecalled(1);
        m.setContent("对方撤回了一条消息");
        m.setMsgType(CommonConstants.MSG_RECALL);
        messageMapper.updateById(m);

        // 广播撤回事件 (双方前端隐藏气泡)
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
            // 自己也要推 (多端同步, 比如手机撤回后电脑也更新)
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
     * <p>
     * 算法:
     *   step 1: 校验消息存在
     *   step 2: 幂等插入 receipt (unique 冲突忽略)
     *   step 3: 清未读 + 推 READ 事件给发送方
     *
     * @param messageId 消息 ID. 业务含义: 已读的消息
     *                  取值范围: Long > 0 且存在
     *                  影响: 写 receipt + 清未读 + 通知发送方
     * @return ApiResponse<Void>
     *         - 成功: code=0 (幂等)
     *         - 失败: 404(消息不存在)
     */
    @Transactional
    public ApiResponse<Void> markRead(Long messageId) {
        Long uid = UserContext.userId();
        ChatMessage m = messageMapper.selectById(messageId);
        if (m == null) return ApiResponse.fail(404, "消息不存在");

        // 幂等插入 receipt (unique 冲突由 catch 兜底)
        try {
            MessageReceipt r = new MessageReceipt();
            r.setMessageId(messageId);
            r.setUserId(uid);
            r.setReadAt(LocalDateTime.now());
            receiptMapper.insert(r);
        } catch (Exception ignore) {
            // 已存在 unique 冲突 (同一用户重复 mark), 忽略
        }
        // 清未读计数
        unreadCounterService.clear(uid, m.getSessionId());
        // 通知发送方 (前端可显示"已读")
        if (!uid.equals(m.getSenderId())) {
            wsPushService.notifyRead(m.getSenderId(), messageId, uid);
        }
        return ApiResponse.ok();
    }

    /**
     * 会话所有消息标已读 (批量).
     * <p>
     * 算法: 拉会话所有消息, 给非自己发的写 receipt, 推 READ 事件给对应发送方.
     *
     * @param sessionId 会话 ID. 业务含义: 要全部标已读的会话
     *                  取值范围: Long > 0
     *                  影响: 该会话未读清零, 对方收到多条 READ
     */
    @Transactional
    public ApiResponse<Void> markSessionRead(Long sessionId) {
        Long uid = UserContext.userId();
        // 清整个会话的未读计数
        unreadCounterService.clear(uid, sessionId);
        // 拉会话所有消息 (按时间升序, 上限 500 防爆)
        List<ChatMessage> msgs = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .last("LIMIT 500"));
        // 逐条处理 (排除自己发的 + 已撤回的)
        for (ChatMessage m : msgs) {
            if (uid.equals(m.getSenderId())) continue;                       // 自己发的跳过
            if (m.getRecalled() != null && m.getRecalled() == 1) continue;  // 已撤回跳过
            // 写 receipt (幂等)
            try {
                MessageReceipt r = new MessageReceipt();
                r.setMessageId(m.getId());
                r.setUserId(uid);
                r.setReadAt(LocalDateTime.now());
                receiptMapper.insert(r);
            } catch (Exception ignore) { /* unique 冲突, 已读过 */ }
            // 通知发送方
            wsPushService.notifyRead(m.getSenderId(), m.getId(), uid);
        }
        return ApiResponse.ok();
    }

    /**
     * 输入状态广播 (用户在输入框敲字时调用).
     * <p>
     * 推 STOMP 事件 {type=TYPING, sessionId, fromUid, role, typing} 给 peer.
     *
     * @param sessionId 会话 ID. 业务含义: 触发打字事件的会话
     *                  取值范围: Long > 0
     *                  影响: 推 TYPING 事件
     * @param typing    是否正在打字. true=开始/继续, false=停止
     *                  取值范围: 布尔
     *                  影响: 前端显示"对方正在输入..."
     */
    public void typing(Long sessionId, boolean typing) {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return;
        Long peerId = isCustomer(uid, s) ? s.getAgentId() : s.getCustomerId();
        if (peerId != null) {
            // 推 TYPING 事件给 peer
            wsPushService.notifyTyping(sessionId, uid, role, typing);
        }
    }

    /**
     * 发送系统消息 (已挪到 SystemMessageService, 避免与 SessionService 循环依赖).
     *
     * @param sessionId 会话 ID
     * @param content   消息内容
     * @deprecated 保留空方法防止外部调用报错, 实际委托给 SystemMessageService
     */
    @Deprecated
    public void sendSystemMessage(Long sessionId, String content) {
        // delegate to SystemMessageService
        systemMessageService.sendSystemMessage(sessionId, content);
    }

    /**
     * 拉历史消息 (按时间升序, 权限校验).
     * <p>
     * 算法:
     *   step 1: 校验会话存在
     *   step 2: 权限校验: 调用方必须是客户/坐席之一
     *   step 3: 拉消息 (clamp 1-200, 默认 50)
     *
     * @param sessionId 会话 ID. 业务含义: 要查的会话
     *                  取值范围: Long > 0 且存在
     *                  影响: 决定返回哪些消息
     * @param limit     返回条数. 业务含义: 分页大小
     *                  取值范围: 1-200 (默认 50)
     *                  影响: 决定返回多少条
     * @return ApiResponse 包装的 MessageDTO 列表
     *         - 成功: code=0, data=消息列表
     *         - 失败: 403(无权) / 404(无会话)
     */
    public ApiResponse<List<MessageDTO>> history(Long sessionId, Integer limit) {
        Long uid = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        // 权限: 客户或坐席之一
        boolean allowed = uid.equals(s.getCustomerId()) || (s.getAgentId() != null && uid.equals(s.getAgentId()));
        if (!allowed) return ApiResponse.fail(403, "无权查看");

        // clamp 1-200
        int n = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        // 按时间升序查
        List<ChatMessage> rows = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt)
                .last("LIMIT " + n));
        return ApiResponse.ok(rows.stream().map(this::toDto).toList());
    }

    /**
     * 搜索会话内消息 (按关键字 + 可选时间范围).
     * <p>
     * 算法:
     *   step 1: 校验会话 + 权限 (客户/坐席/管理员)
     *   step 2: 校验关键字非空
     *   step 3: 构造查询: sessionId + LIKE + 可选时间范围 + LIMIT
     *   step 4: 返结果
     *
     * @param sessionId 会话 ID. 业务含义: 搜索范围
     *                  取值范围: Long > 0 且存在
     *                  影响: 限定搜索范围
     * @param keyword   关键字. 业务含义: 匹配 content
     *                  取值范围: 1+ 字符
     *                  影响: 空则返 400
     * @param fromTs    起始时间戳 (毫秒). 业务含义: 时间窗下界
     *                  取值范围: Long (可空)
     *                  影响: 加 ge 条件
     * @param toTs      结束时间戳 (毫秒). 业务含义: 时间窗上界
     *                  取值范围: Long (可空)
     *                  影响: 加 le 条件
     * @param limit     返回条数
     * @return ApiResponse 包装的 MessageDTO 列表
     *         - 成功: code=0, data=搜索结果 (按时间倒序)
     *         - 失败: 400(空关键字) / 403(无权) / 404(无会话)
     */
    public ApiResponse<List<MessageDTO>> search(Long sessionId, String keyword,
                                                Long fromTs, Long toTs, Integer limit) {
        Long uid = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        // 权限: 客户/坐席/管理员 (管理员通读)
        boolean allowed = uid.equals(s.getCustomerId()) || (s.getAgentId() != null && uid.equals(s.getAgentId()))
                || CommonConstants.ROLE_ADMIN.equalsIgnoreCase(UserContext.role());
        if (!allowed) return ApiResponse.fail(403, "无权查看");

        if (keyword == null || keyword.trim().isEmpty()) {
            return ApiResponse.fail(400, "关键字不能为空");
        }
        int n = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);

        // 构造查询 (按时间倒序, 即最新匹配在前)
        LambdaQueryWrapper<ChatMessage> q = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .like(ChatMessage::getContent, keyword.trim())               // LIKE 模糊匹配
                .orderByDesc(ChatMessage::getCreatedAt)                      // 倒序
                .last("LIMIT " + n);
        // 可选时间窗
        if (fromTs != null) q.ge(ChatMessage::getCreatedAt(),
                java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(fromTs), java.time.ZoneId.systemDefault()));
        if (toTs != null) q.le(ChatMessage::getCreatedAt(),
                java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(toTs), java.time.ZoneId.systemDefault()));

        List<ChatMessage> rows = messageMapper.selectList(q);
        return ApiResponse.ok(rows.stream().map(this::toDto).toList());
    }

    /**
     * ChatMessage → MessageDTO (内部转换).
     *
     * @param m chat_message 实体
     * @return MessageDTO (前端可序列化)
     */
    public MessageDTO toDto(ChatMessage m) {
        return new MessageDTO(m.getId(), m.getSessionId(), m.getSenderId(),
                m.getSenderRole(), m.getMsgType(), m.getContent(), m.getCreatedAt());
    }

    /**
     * 判断 uid 是否是会话的客户.
     *
     * @param uid 用户 ID
     * @param s   会话实体
     * @return true=uid==s.customerId
     */
    private boolean isCustomer(Long uid, ChatSession s) {
        return uid.equals(s.getCustomerId());
    }

    /**
     * LocalDateTime → epoch 毫秒 (用于 2 分钟撤回判断).
     */
    private long timestampMs(LocalDateTime t) {
        return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 截断字符串 (会话列表预览用, 上限 200 字符).
     */
    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
