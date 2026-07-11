package com.chat.common.sla;

import com.chat.common.api.ApiResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SlowQueryAdminController - 慢查询管理 (V3.1).
 * ----------------------------------------------------------------------------
 * 提供 API 触发 cleanup / 查询慢查询统计.
 */
@RestController
@RequestMapping("/api/perf/slow")
@RequiredArgsConstructor
public class SlowQueryAdminController {

    private final MeterRegistry registry;
    private final SlowQueryMetrics metrics;

    /**
     * 当前慢查询统计.
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Counter c : registry.get("http_slow_total").counters()) {
            String endpoint = c.getId().getTag("endpoint");
            if (endpoint != null) {
                out.put(endpoint, c.count());
            }
        }
        return ApiResponse.ok(out);
    }

    /**
     * 错误统计.
     */
    @GetMapping("/errors")
    public ApiResponse<Double> errors() {
        for (Counter c : registry.get("http_slow_errors_total").counters()) {
            return ApiResponse.ok(c.count());
        }
        return ApiResponse.ok(0.0);
    }
}
