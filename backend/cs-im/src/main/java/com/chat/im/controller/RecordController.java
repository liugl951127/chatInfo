package com.chat.im.controller;

import com.chat.common.api.ApiResponse;
import com.chat.im.entity.ChatRecord;
import com.chat.im.entity.ChatRecordChunk;
import com.chat.im.service.RecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 录像回溯接口.
 *
 * 流程: SDK 初始化 -> init (consent 必须 true) -> 上传 chunk (n 次) -> end.
 * 客户页面关闭/被杀进程前必须先 end, 否则视为异常结束.
 */
@Tag(name = "录像回溯", description = "客户页面视频录制 + 回溯 (合规要求)")
@RestController
@RequestMapping("/api/im/record")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

    @Operation(summary = "开始录制 (consent 必须为 true)")
    @PostMapping("/init")
    public ApiResponse<Map<String, Object>> init(@RequestParam Long sessionId,
                                                 @RequestParam Boolean consent) {
        return recordService.init(sessionId, consent);
    }

    @Operation(summary = "上传一个录制分片 (multipart)")
    @PostMapping(value = "/chunk", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> uploadChunk(@RequestParam Long recordId,
                                                       @RequestParam Integer sequenceNo,
                                                       @RequestParam(required = false) Integer durationMs,
                                                       @RequestPart("file") MultipartFile file) throws IOException {
        return recordService.uploadChunk(recordId, sequenceNo, durationMs, file);
    }

    @Operation(summary = "结束录制 (endReason: NORMAL/PAGE_CLOSE/PROCESS_KILLED/ERROR)")
    @PostMapping("/end")
    public ApiResponse<Void> end(@RequestParam Long recordId,
                                 @RequestParam(required = false, defaultValue = "NORMAL") String endReason) {
        return recordService.end(recordId, endReason);
    }

    @Operation(summary = "查询会话的所有录像")
    @GetMapping("/session/{sessionId}")
    public ApiResponse<List<ChatRecord>> listBySession(@PathVariable Long sessionId) {
        return recordService.listBySession(sessionId);
    }

    @Operation(summary = "查询录像的所有分片")
    @GetMapping("/{recordId}/chunks")
    public ApiResponse<List<ChatRecordChunk>> chunks(@PathVariable Long recordId) {
        return recordService.chunks(recordId);
    }
}