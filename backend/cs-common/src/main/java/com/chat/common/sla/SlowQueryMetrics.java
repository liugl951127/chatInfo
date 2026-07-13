package com.chat.common.sla;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SlowQueryMetrics - 慢查询指标 (V3.1 性能监控).
 * ----------------------------------------------------------------------------
 * 记录:
 *   - 每个端点的响应时间 (Timer, 自动 P50/P95/P99)
 *   - 慢查询计数 (> 1s, 可配置)
 *   - 错误计数 (5xx 异常)
 *
 * 用途:
 *   - 实时看 P99 响应时间 (SLA)
 *   - 慢查询告警 (alerts.yml P0_ResponseP99)
 *   - 容量规划 (调优基础)
 */
@Component
public class SlowQueryMetrics {

    private final MeterRegistry registry;
    private final Counter errorCounter;
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> slowCounters = new ConcurrentHashMap<>();

    /**
     * 显式构造器 (Spring 注入用).
     */
    public SlowQueryMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.errorCounter = Counter.builder("http_slow_errors_total")
                .description("5xx errors count")
                .register(registry);
    }

    /**
     * 记录端点响应时间.
     * 自动按 endpoint 分桶, 计算 P50/P95/P99.
     */
    public void recordLatency(String endpoint, long ms) {
        Timer t = timers.computeIfAbsent(endpoint, ep -> Timer.builder("http_endpoint_latency")
                .description("endpoint latency")
                .tag("endpoint", ep)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
        t.record(ms, TimeUnit.MILLISECONDS);
        // 慢查询 (> 1s) 计数
        if (ms > 1000) {
            Counter c = slowCounters.computeIfAbsent(endpoint, ep -> Counter.builder("http_slow_total")
                    .description("slow query count > 1s")
                    .tag("endpoint", ep)
                    .register(registry));
            c.increment();
        }
    }

    /**
     * 记录错误.
     */
    public void recordError() {
        errorCounter.increment();
    }
}
