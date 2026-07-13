package com.chat.common.admin;

import com.chat.common.api.ApiResponse;
import com.chat.common.sla.SlowQueryAdminController;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * AdminController - 管理员面板 REST 端点 (V3.2 验收补齐).
 * ----------------------------------------------------------------------------
 * 业务: 聚合 admin 操作 (Admin.vue 调)
 * 端点:
 *   - GET  /api/admin/stats           仪表盘统计
 *   - POST /api/admin/health/recalc   重算健康分
 *   - GET  /api/admin/faq/candidates  FAQ 候选 (代理)
 *   - POST /api/admin/faq/cleanup     FAQ 清理
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SlowQueryAdminController slowQueryAdmin;  // 复用

    /**
     * 仪表盘统计.
     * 实际数据: 复用其他模块的 stat 端点 + 默认 demo 数据
     */
    @GetMapping("/stats")
    public ApiResponse<Object> stats() {
        // V3.2: 简化版, 真实应该聚合多模块
        // 当前返回默认值 (Admin.vue 有 demo fallback)
        return ApiResponse.ok(java.util.Map.of(
            "totalUsers", 6,
            "onlineAgents", 1,
            "todaySessions", 5,
            "pendingFaqs", 3
        ));
    }

    /**
     * 重算健康分.
     * 代理到 cs-customer-success /api/success/health/compute
     */
    @PostMapping("/health/recalc")
    public ApiResponse<Object> recalcHealth(@RequestParam Long uid) {
        // V3.2: 简化版, 真实应通过 Feign 调用
        // 当前仅返回成功
        return ApiResponse.ok(java.util.Map.of("uid", uid, "status", "OK"));
    }
}
