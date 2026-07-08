package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.security.UserContext;
import com.chat.im.dto.UploadResult;
import com.chat.im.entity.ChatSession;
import com.chat.im.mapper.ChatSessionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 通用文件存储 (图片/PDF/Office/压缩包 等).
 * 与录制分片分开存放, 但复用同一 storage-path.
 * 目录结构: <root>/chat-files/<sessionId>/<uuid>.<ext>
 * 客户端拿到的 fileUrl 是相对路径 (不含 root), 真实路径由 storage-path 拼.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final ChatSessionMapper sessionMapper;

    @Value("${record.storage-path:/tmp/chat-records}")
    private String storagePath;

    private Path filesDir;

    @PostConstruct
    public void init() throws IOException {
        filesDir = Paths.get(storagePath).toAbsolutePath().resolve("chat-files");
        Files.createDirectories(filesDir);
        log.info("[file] storage dir = {}", filesDir);
    }

    public ApiResponse<UploadResult> upload(Long sessionId, Long uid, String role, MultipartFile file) throws IOException {
        if (file.isEmpty()) return ApiResponse.fail(400, "文件为空");
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        // 权限: 必须是该会话的客户或坐席
        if (!isParticipant(s, uid, role)) return ApiResponse.fail(403, "无权上传到该会话");

        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file.bin";
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot > 0) ext = original.substring(dot);

        // 文件大小限制 50MB
        if (file.getSize() > 50 * 1024 * 1024) return ApiResponse.fail(413, "文件超过 50MB 限制");
        // 黑名单: 不允许上传可执行文件 (安全)
        String lower = original.toLowerCase();
        if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".sh")
                || lower.endsWith(".msi") || lower.endsWith(".scr") || lower.endsWith(".js")
                || lower.endsWith(".jar")) {
            return ApiResponse.fail(400, "禁止上传可执行文件");
        }

        Path sessionDir = filesDir.resolve(String.valueOf(sessionId));
        Files.createDirectories(sessionDir);
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = sessionDir.resolve(filename);
        file.transferTo(target);

        String relPath = sessionId + "/" + filename;
        log.info("[file] upload: user={} session={} size={} path={}", uid, sessionId, file.getSize(), relPath);

        UploadResult r = new UploadResult();
        r.setFileUrl(relPath);
        r.setFileName(original);
        r.setFileSize(file.getSize());
        r.setMimeType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        r.setStoragePath(target.toString());
        return ApiResponse.ok(r);
    }

    /**
     * 解析文件路径 + 鉴权.
     * 返回 null 表示无权访问.
     */
    public ResolvedFile resolveAndAuthorize(String relPath, Long uid, String role) throws IOException {
        if (relPath == null || relPath.contains("..") || relPath.startsWith("/")) return null;
        String[] parts = relPath.split("/", 2);
        if (parts.length != 2) return null;
        Long sid;
        try { sid = Long.parseLong(parts[0]); } catch (NumberFormatException e) { return null; }
        ChatSession s = sessionMapper.selectById(sid);
        if (s == null) return null;
        if (!isParticipant(s, uid, role)) return null;
        Path target = filesDir.resolve(relPath).normalize();
        // 防越权: 必须在 filesDir 之下
        if (!target.startsWith(filesDir)) return null;
        if (!Files.exists(target)) return null;
        ResolvedFile r = new ResolvedFile();
        r.setResource(new FileSystemResource(target));
        r.setFileName(parts[1]);
        r.setMimeType(Files.probeContentType(target));
        if (r.getMimeType() == null) r.setMimeType("application/octet-stream");
        return r;
    }

    private boolean isParticipant(ChatSession s, Long uid, String role) {
        if (CommonConstants.ROLE_ADMIN.equalsIgnoreCase(role)) return true;
        if (s.getCustomerId() != null && s.getCustomerId().equals(uid)) return true;
        if (s.getAgentId() != null && s.getAgentId().equals(uid)
                && CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) return true;
        return false;
    }

    public static class ResolvedFile {
        private Resource resource;
        private String fileName;
        private String mimeType;
        public Resource getResource() { return resource; }
        public void setResource(Resource r) { this.resource = r; }
        public String getFileName() { return fileName; }
        public void setFileName(String f) { this.fileName = f; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String m) { this.mimeType = m; }
    }
}