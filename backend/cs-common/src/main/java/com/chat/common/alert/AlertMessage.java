package com.chat.common.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * AlertMessage - 告警消息.
 * ----------------------------------------------------------------------------
 * 包含触发告警所需的所有信息, 跨通道统一格式.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertMessage {
    /** 标题 (一行简短描述) */
    private String title;
    /** 详细描述 */
    private String description;
    /** 严重度 */
    private AlertSeverity severity;
    /** 触发源 (服务名/模块/指标名) */
    private String source;
    /** 当前值 */
    private String currentValue;
    /** 阈值 */
    private String threshold;
    /** 时间戳 */
    private Instant timestamp;
    /** 标签 (环境/版本/区域 等) */
    private Map<String, String> tags;
    /** 链接 (Grafana / 文档) */
    private String link;
}