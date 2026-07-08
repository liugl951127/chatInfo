package com.chat.im.controller;

import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.security.UserContext;
import com.chat.im.dto.UploadResult;
import com.chat.im.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文件上传 (通用, 不限于图片).
 * 流程:
 *   1. POST /api/im/file/upload?sessionId=&msgType=FILE  (multipart)
 *      -> 返回 {fileUrl, fileName, fileSize, mimeType}
 *   2. 客户端再走 STOMP /app/send/{sessionId} 把 fileUrl 放进 content 字段发出
 *   3. 收件方通过 GET /api/im/file/raw?path=xxx 拉文件
 * 注: 文件存储路径只允许 <storage-path>/chat-files/ 之下, 防越权.
 */
@Tag(name = "文件")
@RestController
@RequestMapping("/api/im/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @Operation(summary = "上传文件 (PDF / Word / Excel / 图片 / 压缩包 等)")
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<UploadResult> upload(@RequestParam Long sessionId,
                                           @RequestPart("file") MultipartFile file) throws IOException {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        return fileService.upload(sessionId, uid, role, file);
    }

    @Operation(summary = "下载文件 (受控访问, 防越权)")
    @GetMapping("/raw")
    public ResponseEntity<Resource> raw(@RequestParam String path) throws IOException {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        // path 形如 "sessionId/uuid.ext" — 限定到 session 关联的目录
        var pair = fileService.resolveAndAuthorize(path, uid, role);
        if (pair == null) return ResponseEntity.status(403).build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(pair.getMimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + pair.getFileName() + "\"")
            .body(pair.getResource());
    }
}