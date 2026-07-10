package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;       // MP 条件构造器
import com.chat.common.constant.CommonConstants;                              // 公共常量
import com.chat.common.dto.MessageDTO;                                       // 消息传输 DTO
import com.chat.im.entity.ChatMessage;                                      // chat_message 表实体
import com.chat.im.entity.ChatSession;                                      // chat_session 表实体
import com.chat.im.mapper.ChatMessageMapper;                                 // chat_message DAO
import com.chat.im.mapper.ChatSessionMapper;                                 // chat_session DAO
import lombok.RequiredArgsConstructor;                                       // Lombok: 自动生成 final 字段构造函数
import lombok.extern.slf4j.Slf4j;                                            // SLF4J 日志
import org.springframework.messaging.simp.SimpMessagingTemplate;              // STOMP 推送
import org.springframework.stereotype.Service;                                 // Spring Bean 标识

import java.time.LocalDateTime;                                                // 时间戳

/**
 * 系统消息服务 (独立, 避免 MessageService<->SessionService 循环依赖).
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 持久化系统消息 (sender_role=SYSTEM, msg_type=SYSTEM)
 *   - 更新会话 lastMessage 字段 (UI 会话列表显示最新摘要)
 *   - 推送给会话所有参与方 (customer + agent if any) via STOMP /queue/messages
 *   - 不依赖 MessageService (解耦两个 service)
 *
 * 调用方:
 *   - MessageService.sendBotReply() — 客户在 bot 会话时的机器人回复 (BOT 角色)
 *   - SessionService.create() — 客服接入/转接时的系统消息 ("客服 XXX 已为您服务")
 *   - SessionService.transfer() — 转接通知
 *   - SessionService.close() — 关闭提示
 *
 * 设计要点:
 *   - 单独抽出来是因为 MessageService 和 SessionService 互相依赖时 Spring 6 会报循环依赖
 *   - 把"写系统消息"这个共同职责提到独立 service, 两个 service 都注入它即可
 */
@Slf4j                                                                       // 自动生成 log 字段
@Service                                                                    // 注册为 Spring Bean
@RequiredArgsConstructor                                                    // final 字段自动注入
public class SystemMessageService {

    /** chat_message 表 DAO (insert / selectById) */
    private final ChatMessageMapper messageMapper;
    /** chat_session 表 DAO (selectById / updateById) */
    private final ChatSessionMapper sessionMapper;
    /** STOMP 推送模板 (向 /user/{userId}/queue/messages 发消息) */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 发送系统消息 (持久化 + 更新会话摘要 + STOMP 推送).
     *
     * 流程:
     *   1) 构造 ChatMessage (sender_role=SYSTEM, msg_type=SYSTEM)
     *   2) INSERT 到 chat_message 表
     *   3) 更新 chat_session.lastMessage (UI 列表显示用, 加 [系统] 前缀)
     *   4) 推送给 customer 和 agent 的 /queue/messages (前端 WebSocket 实时接收)
     *
     * @param sessionId 会话 ID
     * @param content 系统消息文本
     * @return MessageDTO (已包含 id, createdAt 等)
     */
    public MessageDTO sendSystemMessage(Long sessionId, String content) {
        // 1) 构造并 insert
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);                                          // 关联到具体会话
        m.setSenderId(0L);                                                 // 0 表示系统消息 (无真实发送人)
        m.setSenderRole(CommonConstants.ROLE_SYSTEM);                     // 'SYSTEM'
        m.setMsgType(CommonConstants.MSG_SYSTEM);                          // 'SYSTEM'
        m.setContent(content);
        m.setRecalled(0);                                                  // 系统消息不能撤回
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);

        // 2) 更新 session.lastMessage (用于会话列表的最近消息预览)
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            String prev = s.getLastMessage() == null ? "" : s.getLastMessage();
            // 系统消息加 [系统] 前缀便于辨识 (避免和正常消息混在一起)
            if (!prev.startsWith("[系统]")) {
                s.setLastMessage("[系统] " + truncate(content));
            } else {
                s.setLastMessage("[系统] " + truncate(content));
            }
            s.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(s);
        }

        // 3) 转 DTO + 推送
        MessageDTO dto = toDto(m);
        // 推给所有参与方: customer 一定有, agent 可能为 null (WAITING 会话)
        if (s != null) {
            if (s.getCustomerId() != null) {
                // 客户: /user/{uid}/queue/messages
                messagingTemplate.convertAndSendToUser(String.valueOf(s.getCustomerId()), "/queue/messages", dto);
            }
            if (s.getAgentId() != null) {
                // 坐席: /user/{uid}/queue/messages
                messagingTemplate.convertAndSendToUser(String.valueOf(s.getAgentId()), "/queue/messages", dto);
            }
        }
        return dto;
    }

    /**
     * ChatMessage 实体 -> MessageDTO (用于 STOMP 推送, 不暴露实体细节).
     */
    private MessageDTO toDto(ChatMessage m) {
        MessageDTO d = new MessageDTO();
        d.setId(m.getId());                                                 // 主键
        d.setSessionId(m.getSessionId());                                  // 会话 ID
        d.setSenderId(m.getSenderId());                                    // 0 (系统)
        d.setSenderRole(m.getSenderRole());                                // 'SYSTEM'
        d.setMsgType(m.getMsgType());                                      // 'SYSTEM'
        d.setContent(m.getContent());                                      // 文本
        d.setCreatedAt(m.getCreatedAt());                                  // 时间戳
        return d;
    }

    /**
     * 截断长文本 (lastMessage 字段在 UI 列表只展示一行, 太长会换行错乱).
     * @param s 原文
     * @return 截断后的字符串 (超过 60 字加 "...")
     */
    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}