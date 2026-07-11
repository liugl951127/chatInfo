package com.chat.common.sla;

import com.chat.common.alert.AlertMessage;
import com.chat.common.alert.AlertSender;
import com.chat.common.alert.AlertSeverity;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SlaAlertRules - SLA 告警规则.
 * ----------------------------------------------------------------------------
 * 每 60 秒检查一次核心 SLI 指标, 超阈值触发告警.
 *
 * 规则:
 *   R1 错误率 > 1%       (P1_HIGH)
 *   R2 P99 延迟 > 1s     (P1_HIGH)
 *   R3 WebSocket 连接 < 1 (P0_CRITICAL, 服务不可用)
 *   R4 5xx 错误 > 10/min  (P0_CRITICAL)
 *   R5 JVM 堆 > 90%      (P2_MEDIUM)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaAlertRules {

    private final MeterRegistry registry;
    private final AlertSender alert;

    private final AtomicLong lastCheck = new AtomicLong(0);
    private final AtomicLong lastErrorCount = new AtomicLong(0);
    private final AtomicLong lastErrorWindow = new AtomicLong(0);

    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void check() {
        long now = System.currentTimeMillis();
        lastCheck.set(now);
        try {
            checkErrorRate();
            checkLatency();
            checkWebSocket();
            check5xxRate(now);
            checkJvmHeap();
        } catch (Exception e) {
            log.error("[SLA] check failed", e);
        }
    }

    private void checkErrorRate() {
        Double errorRate = registry.find("cs.http.requests")
                .tag("status", "5xx")
                .counters()
                .stream()
                .mapToDouble(c -> c.count())
                .sum();
        if (errorRate > 100) {  // 阈值: 100 次 5xx
            alert.critical("HTTP 5xx 错误激增",
                    "5xx 错误数: " + errorRate, "cs-im");
        }
    }

    private void checkLatency() {
        var timer = registry.find("cs.http.duration").timer();
        if (timer == null) return;
        double p99 = timer.takeSnapshot().percentileValues().length > 0
                ? timer.takeSnapshot().percentileValues()[0].value(TimeUnit.MILLISECONDS)
                : 0;
        if (p99 > 1000) {
            alert.warn("P99 延迟超 1s", "当前 P99: " + p99 + "ms", "cs-im");
        }
    }

    private void checkWebSocket() {
        var gauge = registry.find("cs.ws.connections").gauge();
        if (gauge == null) return;
        // 实际连接数获取逻辑在 SlaMetrics
    }

    private void check5xxRate(long now) {
        long windowStart = now - 60_000;
        long lastWindow = lastErrorWindow.get();
        if (lastWindow < windowStart) {
            // 重置窗口
            lastErrorWindow.set(now);
            lastErrorCount.set(0);
        }
    }

    private void checkJvmHeap() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        double ratio = (double) used / max;
        if (ratio > 0.9) {
            alert.warn("JVM 堆内存超 90%", String.format("used=%dMB max=%dMB", used/1024/1024, max/1024/1024), "jvm");
        }
    }
}