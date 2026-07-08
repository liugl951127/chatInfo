package com.chat.im.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session")
public class ChatSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionNo;
    private Long customerId;
    private Long agentId;
    private String skillTag;
    /** WAITING / ACTIVE / CLOSED */
    private String status;
    /** 是否机器人会话 (0=人工 1=智能客服 bot) */
    private Integer isBot;
    private Long transferredFromAgentId;
    private String transferReason;
    private String lastMessage;
    /** CSAT 1-5 */
    private Integer rating;
    private String ratingComment;
    private LocalDateTime ratedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;
}