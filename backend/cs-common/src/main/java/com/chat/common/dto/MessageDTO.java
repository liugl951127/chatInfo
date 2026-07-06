package com.chat.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * WebSocket 消息 DTO (客户端 ↔ 服务端)。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageDTO {
    /** 消息业务 id,服务端持久化后回填 */
    private Long id;
    /** 会话 id */
    private Long sessionId;
    private Long senderId;
    private String senderRole;
    /** CUSTOMER / AGENT / SYSTEM */
    private String msgType;
    /** TEXT / IMAGE / FILE / SYSTEM */
    private String content;
    private LocalDateTime createdAt;
}