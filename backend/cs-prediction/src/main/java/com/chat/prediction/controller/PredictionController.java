package com.chat.prediction.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.prediction.entity.PredictionEvent;
import com.chat.prediction.entity.PredictionRule;
import com.chat.prediction.mapper.PredictionEventMapper;
import com.chat.prediction.mapper.PredictionRuleMapper;
import com.chat.prediction.service.PredictionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * PredictionController - 预见式服务 API.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - POST /api/prediction/evaluate    手动评估某事件 (供其他模块调用)
 *   - GET  /api/prediction/rules       列出所有规则 (管理员)
 *   - GET  /api/prediction/history     某用户触发历史
 */
@Tag(name = "预见式服务")
@RestController
@RequestMapping("/api/prediction")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predictionService;
    private final PredictionRuleMapper ruleMapper;
    private final PredictionEventMapper eventMapper;

    @Operation(summary = "评估事件 (供 cs-cdp / cs-im 调用)")
    @PostMapping("/evaluate")
    public ApiResponse<List<PredictionEvent>> evaluate(@RequestBody Map<String, Object> body) {
        Long userId = body.get("userId") == null ? null
                : Long.valueOf(body.get("userId").toString());
        if (userId == null) userId = UserContext.userId();
        String eventType = (String) body.get("eventType");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) body.get("context");
        return ApiResponse.ok(predictionService.evaluate(userId, eventType, context));
    }

    @Operation(summary = "列出规则 (管理员)")
    @GetMapping("/rules")
    public ApiResponse<List<PredictionRule>> rules() {
        return ApiResponse.ok(ruleMapper.selectList(null));
    }

    @Operation(summary = "某用户触发历史")
    @GetMapping("/history/{uid}")
    public ApiResponse<List<PredictionEvent>> history(@PathVariable Long uid) {
        return ApiResponse.ok(eventMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PredictionEvent>()
                        .eq("user_id", uid)
                        .orderByDesc("created_at")
                        .last(true, "LIMIT 50")));
    }
}