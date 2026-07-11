package com.chat.common.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * AlertSender - 智能告警发送器.
 * ----------------------------------------------------------------------------
 * 多通道并发发送, 任一失败不影响其他.
 *
 * 通道:
 *   - CONSOLE: 控制台 (默认, 0 依赖)
 *   - WEBHOOK: 通用 webhook (AlertManager / 自建)
 *   - PAGERDUTY: PagerDuty Events API v2
 *   - DINGTALK: 钉钉机器人
 *   - FEISHU: 飞书机器人
 *   - EMAIL/SMS: 阶段 2
 *
 * 配置 (application.yml):
 *   cs:
 *     alert:
 *       enabled: true
 *       channels: [console, webhook]
 *       webhook-url: https://hooks.example.com/alert
 *       pagerduty-key: xxx
 *       dingtalk-url: https://oapi.dingtalk.com/robot/send?access_token=xxx
 */
@Slf4j
@Component
public class AlertSender {

    @Value("${cs.alert.enabled:false}")
    private boolean enabled;

    @Value("${cs.alert.webhook-url:}")
    private String webhookUrl;

    @Value("${cs.alert.pagerduty-key:}")
    private String pagerdutyKey;

    @Value("${cs.alert.dingtalk-url:}")
    private String dingtalkUrl;

    @Value("${cs.alert.feishu-url:}")
    private String feishuUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * 发送告警 (非阻塞, 异步入队).
     */
    public void send(AlertMessage msg) {
        if (!enabled) {
            console(msg);
            return;
        }
        new Thread(() -> {
            try {
                console(msg);
                if (!webhookUrl.isEmpty()) webhook(msg);
                if (!pagerdutyKey.isEmpty()) pagerduty(msg);
                if (!dingtalkUrl.isEmpty()) dingtalk(msg);
                if (!feishuUrl.isEmpty()) feishu(msg);
            } catch (Exception e) {
                log.error("[alert] send failed", e);
            }
        }, "alert-sender-" + System.currentTimeMillis()).start();
    }

    /** 触发 P0 紧急告警 (快捷方法) */
    public void critical(String title, String desc, String source) {
        send(AlertMessage.builder()
                .title(title).description(desc)
                .severity(AlertSeverity.P0_CRITICAL)
                .source(source).timestamp(java.time.Instant.now())
                .build());
    }

    public void warn(String title, String desc, String source) {
        send(AlertMessage.builder()
                .title(title).description(desc)
                .severity(AlertSeverity.P2_MEDIUM)
                .source(source).timestamp(java.time.Instant.now())
                .build());
    }

    private void console(AlertMessage m) {
        String emoji = switch (m.getSeverity()) {
            case P0_CRITICAL -> "🚨";
            case P1_HIGH -> "🔴";
            case P2_MEDIUM -> "🟡";
            case P3_LOW -> "🟢";
        };
        log.warn("{} [{}] {} - {} (source={})",
                emoji, m.getSeverity(), m.getTitle(), m.getDescription(), m.getSource());
    }

    private void webhook(AlertMessage m) {
        try {
            String body = String.format("""
                {"title":"%s","desc":"%s","severity":"%s","source":"%s","ts":"%s"}
                """, m.getTitle(), m.getDescription(), m.getSeverity(),
                m.getSource(), m.getTimestamp());
            http.send(HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("[alert] webhook failed", e);
        }
    }

    private void pagerduty(AlertMessage m) {
        try {
            String body = String.format("""
                {
                  "routing_key": "%s",
                  "event_action": "trigger",
                  "dedup_key": "%s",
                  "payload": {
                    "summary": "%s",
                    "source": "%s",
                    "severity": "%s",
                    "custom_details": {
                      "description": "%s",
                      "timestamp": "%s"
                    }
                  }
                }
                """, pagerdutyKey, m.getSource() + "-" + m.getTitle(),
                m.getTitle(), m.getSource(), m.getSeverity().name().toLowerCase(),
                m.getDescription(), m.getTimestamp());
            http.send(HttpRequest.newBuilder()
                            .uri(URI.create("https://events.pagerduty.com/v2/enqueue"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("[alert] pagerduty failed", e);
        }
    }

    private void dingtalk(AlertMessage m) {
        try {
            String body = String.format("""
                {
                  "msgtype": "markdown",
                  "markdown": {
                    "title": "%s",
                    "text": "## %s\\n\\n> %s\\n\\n**严重度**: %s\\n**来源**: %s\\n**时间**: %s"
                  }
                }
                """, m.getTitle(), m.getTitle(), m.getDescription(),
                m.getSeverity(), m.getSource(), m.getTimestamp());
            http.send(HttpRequest.newBuilder()
                            .uri(URI.create(dingtalkUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("[alert] dingtalk failed", e);
        }
    }

    private void feishu(AlertMessage m) {
        try {
            String body = String.format("""
                {
                  "msg_type": "interactive",
                  "card": {
                    "header": {"title": {"tag": "plain_text", "content": "%s"}},
                    "elements": [{"tag": "markdown", "content": "**严重度**: %s\\n**来源**: %s\\n%s"}]
                  }
                }
                """, m.getTitle(), m.getSeverity(), m.getSource(), m.getDescription());
            http.send(HttpRequest.newBuilder()
                            .uri(URI.create(feishuUrl))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.error("[alert] feishu failed", e);
        }
    }
}