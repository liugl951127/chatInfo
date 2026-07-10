package com.chat.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PredictionEvent - 预见式触发记录.
 * 用于审计 + 效果追踪.
 */
@Data
@TableName("prediction_event")
public class PredictionEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String ruleCode;
    private String status;        // PENDING/SENT/FAILED/SKIPPED
    private String triggerContext;  // JSON
    private String actionPayload;   // JSON
    private LocalDateTime sentAt;
    private String response;        // JSON
    private LocalDateTime createdAt;
}