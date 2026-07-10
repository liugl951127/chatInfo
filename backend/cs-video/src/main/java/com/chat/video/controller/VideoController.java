package com.chat.video.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.video.entity.VideoSession;
import com.chat.video.service.VideoSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * VideoController - 视频会话 REST 控制器.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - POST /api/video/init            创建 (返 sessionId + iceServers)
 *   - GET  /api/video/active          我的活跃会话
 *   - POST /api/video/{id}/end        结束
 *   - GET  /api/video/{id}            查询详情
 *   - POST /api/video/{id}/record     关联录像 (合规录制)
 */
@Tag(name = "视频会话")
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoSessionService sessionService;

    @Operation(summary = "初始化视频会话 (返 sessionId + iceServers)")
    @PostMapping("/init")
    public ApiResponse<Map<String, Object>> init(@RequestBody Map<String, Object> body) {
        Long chatSessionId = body.get("chatSessionId") == null ? null
                : Long.valueOf(body.get("chatSessionId").toString());
        Long peerId = body.get("peerId") == null ? null
                : Long.valueOf(body.get("peerId").toString());
        return sessionService.create(chatSessionId, peerId);
    }

    @Operation(summary = "我的活跃视频会话")
    @GetMapping("/active")
    public ApiResponse<List<VideoSession>> active() {
        Long uid = UserContext.userId();
        return ApiResponse.ok(sessionService.findActiveByUser(uid));
    }

    @Operation(summary = "结束视频会话")
    @PostMapping("/{id}/end")
    public ApiResponse<Void> end(@PathVariable Long id) {
        return sessionService.end(id);
    }

    @Operation(summary = "查询视频会话详情")
    @GetMapping("/{id}")
    public ApiResponse<VideoSession> get(@PathVariable Long id) {
        return ApiResponse.ok(sessionService.getById(id));
    }

    @Operation(summary = "关联录像 (合规录制, 上传完成后回调)")
    @PostMapping("/{id}/record")
    public ApiResponse<Void> linkRecord(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long recordId = Long.valueOf(body.get("recordId").toString());
        sessionService.linkRecord(id, recordId);
        return ApiResponse.ok();
    }
}