package com.chat.common.sla;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ApiPerformanceFilter - HTTP 性能监控过滤器 (V3.1).
 * ----------------------------------------------------------------------------
 * 自动记录每个 HTTP 请求的响应时间到 Prometheus (P50/P95/P99).
 *
 * 流程:
 *   1) 进入记录开始时间
 *   2) 放行 filter chain
 *   3) 退出计算耗时
 *   4) 上报 SlowQueryMetrics
 *
 * 排除:
 *   - /actuator/* (健康检查自身)
 *   - /ws/* (WebSocket upgrade)
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ApiPerformanceFilter extends OncePerRequestFilter {

    private final SlowQueryMetrics metrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return uri.startsWith("/actuator") || uri.startsWith("/ws/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        long t0 = System.nanoTime();
        try {
            chain.doFilter(req, resp);
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String endpoint = req.getMethod() + " " + normalizeUri(req.getRequestURI());
            metrics.recordLatency(endpoint, ms);
            if (resp.getStatus() >= 500) {
                metrics.recordError();
            }
            if (ms > 1000) {
                log.warn("[perf] slow {} {} {}ms", req.getMethod(), req.getRequestURI(), ms);
            }
        }
    }

    /**
     * URI 归一化: 把数字 ID 替换为 {id}, 减少高基数.
     */
    private String normalizeUri(String uri) {
        return uri.replaceAll("/\\d+", "/{id}").replaceAll("/[^/]+\\.[^/]+", "/{file}");
    }
}
