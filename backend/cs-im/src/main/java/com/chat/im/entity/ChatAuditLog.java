package com.chat.im.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_audit_log")
public class ChatAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actorId;
    private String actorRole;
    private String action;
    private String target;
    private String detail;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}