package com.chat.success.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.success.entity.HealthScoreHistory;
import com.chat.success.service.AgentStatsService;
import com.chat.success.service.HealthScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SuccessController - 客户成功 REST 控制器.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - GET  /api/success/health/me    我的健康分
 *   - GET  /api/success/health/{uid} 查任意用户 (坐席/管理员)
 *   - GET  /api/success/health/me/history 历史曲线
 *   - POST /api/success/health/compute  触发计算
 */
@Tag(name = "客户成功")
@RestController
@RequestMapping("/api/success")
@RequiredArgsConstructor
public class SuccessController {

    private final HealthScoreService healthService;
    private final AgentStatsService agentStatsService;

    @Operation(summary = "我的健康分 (最新)")
    @GetMapping("/health/me")
    public ApiResponse<Map<String, Object>> myHealth() {
        Long uid = UserContext.userId();
        HealthScoreHistory h = healthService.findLatest(uid);
        if (h == null) {
            // 没数据, 触发一次默认计算
            var r = healthService.computeFromEvents(uid, 0, 4.0);
            return ApiResponse.ok(Map.of(
                "userId", r.userId(),
                "score", r.score(),
                "tier", r.tier(),
                "components", r.components()
            ));
        }
        return ApiResponse.ok(Map.of(
            "userId", h.getUserId(),
            "score", h.getScore(),
            "tier", h.getTier(),
            "createdAt", h.getCreatedAt()
        ));
    }

    @Operation(summary = "查任意用户健康分")
    @GetMapping("/health/{uid}")
    public ApiResponse<HealthScoreHistory> health(@PathVariable Long uid) {
        String role = UserContext.role();
        if (!"AGENT".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) {
            return ApiResponse.fail(403, "无权访问");
        }
        return ApiResponse.ok(healthService.findLatest(uid));
    }

    @Operation(summary = "我的健康分历史曲线 (最近 30 次)")
    @GetMapping("/health/me/history")
    public ApiResponse<List<HealthScoreHistory>> myHistory() {
        Long uid = UserContext.userId();
        return ApiResponse.ok(healthService.findHistory(uid));
    }

    @Operation(summary = "触发健康分计算")
    @PostMapping("/health/compute")
    public ApiResponse<HealthScoreService.HealthResult> compute(@RequestBody Map<String, Object> body) {
        Long uid = body.get("userId") == null ? UserContext.userId()
                : Long.valueOf(body.get("userId").toString());
        long activeDays = body.get("activeDays") == null ? 7
                : Long.parseLong(body.get("activeDays").toString());
        double avgCsat = body.get("avgCsat") == null ? 4.0
                : Double.parseDouble(body.get("avgCsat").toString());
        return ApiResponse.ok(healthService.computeFromEvents(uid, activeDays, avgCsat));
    }

    @Operation(summary = "坐席 dashboard 统计 (阶段 1 mock + 部分真数据)")
    @GetMapping("/agent-stats")
    public ApiResponse<AgentStatsService.AgentStats> agentStats(@RequestParam(required = false) Long agentId) {
        if (agentId == null) agentId = UserContext.userId();
        return ApiResponse.ok(agentStatsService.getStats(agentId));
    }
}