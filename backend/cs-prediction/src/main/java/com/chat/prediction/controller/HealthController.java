package com.chat.prediction.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * HealthController - 预见式服务 健康检查.
 */
@Tag(name = "预见式服务 - 健康")
@RestController
@RequestMapping("/api/prediction")
public class HealthController {

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "service", "cs-prediction",
            "status", "UP",
            "ts", LocalDateTime.now().toString()
        );
    }
}
