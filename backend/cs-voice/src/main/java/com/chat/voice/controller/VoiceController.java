package com.chat.voice.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.voice.entity.VoiceCall;
import com.chat.voice.service.VoiceCallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * VoiceController - 智能电话 REST 控制器.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - POST /api/voice/init          发起通话 (aiEnabled=true AI 自动接听)
 *   - POST /api/voice/{id}/answer   接听
 *   - POST /api/voice/{id}/asr      ASR + AI 决策 + TTS (multipart audio)
 *   - POST /api/voice/{id}/end      挂断
 *   - GET  /api/voice/active        活跃通话
 *   - GET  /api/voice/{id}          详情
 */
@Tag(name = "智能电话")
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceCallService callService;

    @Operation(summary = "发起通话")
    @PostMapping("/init")
    public ApiResponse<VoiceCall> init(@RequestBody Map<String, Object> body) {
        String callee = (String) body.get("callee");
        Boolean aiEnabled = (Boolean) body.get("aiEnabled");
        return callService.initiate(callee, aiEnabled == null ? true : aiEnabled);
    }

    @Operation(summary = "接听")
    @PostMapping("/{id}/answer")
    public ApiResponse<Void> answer(@PathVariable Long id) {
        return callService.answer(id);
    }

    @Operation(summary = "ASR 转写 + AI 决策 + TTS 合成")
    @PostMapping(value = "/{id}/asr", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> asr(@PathVariable Long id,
                                                @RequestParam("audio") MultipartFile audio) throws Exception {
        return callService.asrAndDecide(id, audio.getBytes());
    }

    @Operation(summary = "挂断")
    @PostMapping("/{id}/end")
    public ApiResponse<Void> end(@PathVariable Long id) {
        return callService.end(id);
    }

    @Operation(summary = "我的活跃通话")
    @GetMapping("/active")
    public ApiResponse<List<VoiceCall>> active() {
        Long uid = UserContext.userId();
        return ApiResponse.ok(callService.findActiveByCaller(uid));
    }

    @Operation(summary = "通话详情 (含 transcript)")
    @GetMapping("/{id}")
    public ApiResponse<VoiceCall> get(@PathVariable Long id) {
        return ApiResponse.ok(callService.getById(id));
    }
}