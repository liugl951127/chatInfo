package com.chat.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * HealthController - 视频会话 健康检查.
 */
@Tag(name = "视频会话 - 健康")
@RestController
@RequestMapping("/api/video")
public class HealthController {

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "service", "cs-video",
            "status", "UP",
            "ts", LocalDateTime.now().toString()
        );
    }
}
