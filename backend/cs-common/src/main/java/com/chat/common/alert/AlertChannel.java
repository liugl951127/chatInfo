package com.chat.common.alert;

/**
 * AlertChannel - 告警通道枚举.
 * ----------------------------------------------------------------------------
 * 多通道: 同时发多个, 冗余送达.
 * 阶段 1: console (开发), webhook (生产).
 * 阶段 2: PagerDuty / 钉钉 / 飞书 / 邮件.
 */
public enum AlertChannel {
    CONSOLE,    // 控制台打印 (开发)
    WEBHOOK,    // 通用 webhook (自建 / Prometheus AlertManager)
    PAGERDUTY,  // PagerDuty
    DINGTALK,   // 钉钉
    FEISHU,     // 飞书
    EMAIL,      // 邮件
    SMS         // 短信
}