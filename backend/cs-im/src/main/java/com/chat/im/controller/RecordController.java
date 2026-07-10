package com.chat.im.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.security.UserContext;
import com.chat.im.entity.ChatRecord;
import com.chat.im.entity.ChatRecordChunk;
import com.chat.im.service.RecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * RecordController - 录像回溯 REST 控制器.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - POST /init                            初始化录像 (consent=true 才接受)
 *   - POST /{recordId}/chunk                上传分片 (MultipartFile, 幂等 sequenceNo)
 *   - POST /{recordId}/end                  结束 (endReason: NORMAL/PAGE_CLOSE/ERROR)
 *   - GET  /session/{sessionId}             按会话查录像列表
 *   - GET  /{recordId}/chunks               录像分片列表
 *   - GET  /{recordId}/merged               服务端 ffmpeg 合并下载 (缓存)
 *
 * 合规: init 必须 consent=true, 所有操作走 chat_audit_log
 */
@Tag(name = "录像回溯", description = "客户页面视频录制 + 回溯 (合规要求)")
@RestController
@RequestMapping("/api/im/record")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

    @Operation(summary = "开始录制 (consent 必须为 true). 若传 resumeRecordId 则续上未结束的 record.")
    @PostMapping("/init")
    public ApiResponse<Map<String, Object>> init(@RequestParam Long sessionId,
                                                 @RequestParam Boolean consent,
                                                 @RequestParam(required = false) Long resumeRecordId) {
        if (resumeRecordId != null) {
            return recordService.resume(sessionId, resumeRecordId);
        }
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

    @Operation(summary = "下载一个分片的二进制 (供回放用, 限本人/该会话坐席/管理员)")
    @GetMapping("/chunk/{chunkId}/raw")
    public ResponseEntity<Resource> downloadChunk(@PathVariable Long chunkId) throws IOException {
        ChatRecordChunk c = recordService.chunkEntity(chunkId);
        if (c == null) return ResponseEntity.notFound().build();

        // 权限检查: 本人 或 该会话坐席 或 管理员
        ChatRecord r = recordService.recordEntity(c.getRecordId());
        Long uid = UserContext.userId();
        String role = UserContext.role();
        // 需要查 session 取 agent id
        com.chat.im.entity.ChatSession s = recordService.sessionForAuth(r.getSessionId(), uid, role);
        boolean owner = r.getUserId().equals(uid);
        boolean admin = CommonConstants.ROLE_ADMIN.equalsIgnoreCase(role);
        if (!(owner || (s != null) || admin)) {
            return ResponseEntity.status(403).build();
        }

        Resource res = recordService.chunkResource(chunkId);
        if (res == null || !res.exists()) return ResponseEntity.notFound().build();
        MediaType mt = MediaType.parseMediaType(
            c.getMimeType() != null ? c.getMimeType() : "video/webm");
        return ResponseEntity.ok()
            .contentType(mt)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"chunk-" + c.getSequenceNo() + ".webm\"")
            .header("X-Chunk-Sequence", String.valueOf(c.getSequenceNo()))
            .header("X-Chunk-Duration-Ms", String.valueOf(c.getDurationMs()))
            .body(res);
    }

    @Operation(summary = "服务端 ffmpeg 合流所有分片 (作为 MSE 不可用时的兑底, 或下载完整录像)")
    @GetMapping("/{recordId}/merged")
    public ResponseEntity<Resource> mergedRecord(@PathVariable Long recordId) throws IOException, InterruptedException {
        ChatRecord r = recordService.recordEntity(recordId);
        if (r == null) return ResponseEntity.notFound().build();
        // 权限 (同上)
        Long uid = UserContext.userId();
        String role = UserContext.role();
        com.chat.im.entity.ChatSession s = recordService.sessionForAuth(r.getSessionId(), uid, role);
        boolean owner = r.getUserId().equals(uid);
        boolean admin = CommonConstants.ROLE_ADMIN.equalsIgnoreCase(role);
        if (!(owner || (s != null) || admin)) return ResponseEntity.status(403).build();

        // 服务端合流 (ffmpeg 不可用时兑底为 503)
        java.nio.file.Path merged = recordService.ffmpegMergeChunks(recordId);
        if (merged == null) return ResponseEntity.status(503).build();
        long size = java.nio.file.Files.size(merged);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("video/webm"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"record-" + recordId + ".webm\"")
            .header("X-File-Size", String.valueOf(size))
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .body(new FileSystemResource(merged));
    }

    @Operation(summary = "查询某会话所有录像 (含元信息, 供回放页)")
    @GetMapping("/session/{sessionId}/with-chunks")
    public ApiResponse<Map<String, Object>> sessionRecordsWithChunks(@PathVariable Long sessionId) {
        // 同样检查权限: 只允许该会话参与方 (客户/坐席) 查看
        Long uid = UserContext.userId();
        String role = UserContext.role();
        // 查 session 验证一下
        var s = recordService.sessionForAuth(sessionId, uid, role);
        if (s == null) return ApiResponse.fail(403, "无权查看该会话录像");
        return recordService.listBySessionWithChunks(sessionId);
    }
}