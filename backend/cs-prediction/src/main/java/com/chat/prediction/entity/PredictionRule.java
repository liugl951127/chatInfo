package com.chat.prediction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PredictionRule - 预见式规则配置.
 * 阶段 1 预置 5 条 (见 v3-cdp-schema.sql):
 *   - ORDER_STUCK_24H: 订单停滞 24h
 *   - PAYMENT_FAILED_3X: 支付失败 3 次/小时
 *   - SILENT_30D: 30 天未活跃
 *   - BIRTHDAY_WEEK: 生日周关怀
 *   - HIGH_VALUE_RETURN: 高价值 60 天未回购
 */
@Data
@TableName("prediction_rule")
public class PredictionRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleCode;
    private String ruleName;
    private String triggerEvent;
    /** 条件 JSON (JSONLogic 风格) */
    private String conditionExpr;
    private String actionType;
    private String actionTemplate;
    private Integer priority;
    private Integer enabled;
    private LocalDateTime createdAt;
}