package com.chat.common.sla;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SlaMetrics - SLI/SLO 指标采集 (Micrometer).
 * ----------------------------------------------------------------------------
 * 4 个黄金信号 + 1 个可用性:
 *   - latency   P50/P95/P99 响应时间
 *   - traffic   QPS / 请求数
 *   - errors    错误率
 *   - saturation 活跃连接/线程池
 *   - availability 在线时间
 *
 * SLO 目标 (V3):
 *   - 可用性   99.95%  (年度宕机 < 4.4 小时)
 *   - 错误率   < 0.1%
 *   - 延迟     P99 < 500ms
 *   - 吞吐量   > 1000 QPS
 *
 * Prometheus 端点: /actuator/prometheus
 * Grafana 看板: 业务 + SLO
 */
@Component
@RequiredArgsConstructor
public class SlaMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    /** HTTP 请求计数 (按端点+状态码) */
    public Counter requestCounter(String endpoint, int status) {
        return Counter.builder("cs.http.requests")
                .tag("endpoint", endpoint)
                .tag("status", String.valueOf(status))
                .description("HTTP 请求总数")
                .register(registry);
    }

    /** HTTP 响应时间 (P99) */
    public Timer requestTimer(String endpoint) {
        return Timer.builder("cs.http.duration")
                .tag("endpoint", endpoint)
                .description("HTTP 响应时间")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    /** 记录一次调用 */
    public void recordRequest(String endpoint, int status, Duration duration) {
        requestCounter(endpoint, status).increment();
        requestTimer(endpoint).record(duration);
    }

    /** 错误计数 */
    public Counter errorCounter(String type, String reason) {
        return Counter.builder("cs.errors")
                .tag("type", type)
                .tag("reason", reason)
                .description("业务错误总数")
                .register(registry);
    }

    /** STOMP 消息计数 */
    public Counter stompMessage(String direction) {
        return Counter.builder("cs.stomp.messages")
                .tag("direction", direction)
                .description("STOMP 消息总数")
                .register(registry);
    }

    /** WebSocket 活跃连接 */
    public int incrementConnection() {
        registry.gauge("cs.ws.connections", activeConnections);
        return activeConnections.incrementAndGet();
    }

    public int decrementConnection() {
        registry.gauge("cs.ws.connections", activeConnections);
        return activeConnections.decrementAndGet();
    }

    /** 业务事件 (会话/转人工/录像) */
    public Counter businessEvent(String type) {
        return Counter.builder("cs.business.events")
                .tag("type", type)
                .description("业务事件计数")
                .register(registry);
    }
}