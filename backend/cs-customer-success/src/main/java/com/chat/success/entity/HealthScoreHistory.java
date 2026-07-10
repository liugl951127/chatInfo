package com.chat.success.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * HealthScoreHistory - 健康分历史.
 */
@Data
@TableName("success_health_score_history")
public class HealthScoreHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Integer score;
    private String components;       // JSON: {login, usage, support, csat}
    private String tier;             // CHAMPION / HEALTHY / AT_RISK / CHURNED
    private LocalDateTime createdAt;
}