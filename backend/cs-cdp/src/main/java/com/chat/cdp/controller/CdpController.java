package com.chat.cdp.controller;

import com.chat.cdp.service.EventService;
import com.chat.cdp.service.ProfileService;
import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CdpController - 数字孪生 REST API.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - GET  /api/cdp/profile/me     我的 360 画像
 *   - GET  /api/cdp/profile/{uid}  任意用户画像 (坐席/管理员)
 *   - POST /api/cdp/event          上报事件
 *   - POST /api/cdp/active         心跳 (更新活跃时间)
 *   - POST /api/cdp/recompute      触发画像重算
 */
@Tag(name = "数字孪生 CDP")
@RestController
@RequestMapping("/api/cdp")
@RequiredArgsConstructor
public class CdpController {

    private final ProfileService profileService;
    private final EventService eventService;

    @Operation(summary = "我的 360 画像")
    @GetMapping("/profile/me")
    public ApiResponse<Map<String, Object>> myProfile() {
        Long uid = UserContext.userId();
        return ApiResponse.ok(profileService.getProfile(uid));
    }

    @Operation(summary = "查任意用户画像 (需坐席/管理员权限)")
    @GetMapping("/profile/{uid}")
    public ApiResponse<Map<String, Object>> profile(@PathVariable Long uid) {
        String role = UserContext.role();
        if (!"AGENT".equalsIgnoreCase(role) && !"ADMIN".equalsIgnoreCase(role)) {
            return ApiResponse.fail(403, "无权访问");
        }
        return ApiResponse.ok(profileService.getProfile(uid));
    }

    @Operation(summary = "上报事件 (前端 SDK 用)")
    @PostMapping("/event")
    public ApiResponse<Void> recordEvent(@RequestBody Map<String, Object> body) {
        String type = (String) body.get("eventType");
        Long sessionId = body.get("sessionId") == null ? null
                : Long.valueOf(body.get("sessionId").toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        eventService.recordEvent(null, type, payload, sessionId);
        return ApiResponse.ok();
    }

    @Operation(summary = "心跳 (更新活跃时间)")
    @PostMapping("/active")
    public ApiResponse<Void> active() {
        Long uid = UserContext.userId();
        profileService.touchActive(uid);
        return ApiResponse.ok();
    }

    @Operation(summary = "触发画像重算 (异步)")
    @PostMapping("/recompute")
    public ApiResponse<Void> recompute() {
        Long uid = UserContext.userId();
        profileService.recompute(uid);
        return ApiResponse.ok();
    }
}