package com.chat.common.alert;

/**
 * AlertSeverity - 告警严重度.
 * ----------------------------------------------------------------------------
 * 4 级, P0 最高, 配合 PagerDuty / 钉钉的优先级.
 */
public enum AlertSeverity {
    P0_CRITICAL,  // 紧急: 服务不可用, 立即响应
    P1_HIGH,      // 高: 核心功能受损, 1 小时内
    P2_MEDIUM,    // 中: 性能降级, 4 小时内
    P3_LOW        // 低: 提示性, 24 小时内
}