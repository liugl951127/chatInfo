package com.chat.im.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** LOGIN / CREATE_SESSION / CLAIM / TRANSFER / CLOSE / RATE / RECALL ... */
    private String action;
    private String target;
    private String detail;
    private String ip;
    private LocalDateTime createdAt;
}