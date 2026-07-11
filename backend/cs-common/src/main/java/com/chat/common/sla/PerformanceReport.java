package com.chat.common.sla;

import com.chat.common.api.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PerformanceReport - 性能监控端点 (V3.1 新增).
 * ----------------------------------------------------------------------------
 * 端点: GET /api/perf/summary
 * 返回: 各端点 P50/P95/P99 响应时间 + 慢查询数
 *
 * 用途:
 *   - 实时看 P99 SLA
 *   - 排查慢查询
 *   - 容量规划 (调优基础)
 */
@RestController
@RequestMapping("/api/perf")
@RequiredArgsConstructor
public class PerformanceReport {

    private final MeterRegistry registry;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> endpoints = new LinkedHashMap<>();
        // 遍历所有 Timer (按 tag.endpoint 聚合)
        for (Timer t : registry.getTimers()) {
            String endpoint = t.getId().getTag("endpoint");
            if (endpoint == null) continue;
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("count", t.count());
            stats.put("avg_ms", Math.round(t.mean(java.util.concurrent.TimeUnit.MILLISECONDS)));
            for (double p : t.takeSnapshot().percentileValues()) {
                stats.put("p" + (int) (p.percentile() * 100) + "_ms", Math.round(p.value(java.util.concurrent.TimeUnit.MILLISECONDS)));
            }
            endpoints.put(endpoint, stats);
        }
        result.put("endpoints", endpoints);
        result.put("uptime_sec", (System.currentTimeMillis() - getStartTime()) / 1000);
        return ApiResponse.ok(result);
    }

    private long getStartTime() {
        // 简化为 JVM 启动时间
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
    }
}
