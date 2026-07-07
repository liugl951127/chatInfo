package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.im.entity.ChatAuditLog;
import com.chat.im.entity.ChatRecord;
import com.chat.im.entity.ChatRecordChunk;
import com.chat.im.mapper.ChatAuditLogMapper;
import com.chat.im.mapper.ChatRecordChunkMapper;
import com.chat.im.mapper.ChatRecordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 录像服务: 客户页面的录制回溯.
 *
 * 设计要点 (合规要求):
 *  - consentGiven 字段记录用户是否明确同意被录制 (调用 init 时必须为 true)
 *  - 任何开始/结束/异常都写入 chat_audit_log
 *  - 分片落到磁盘, 路径以 UUID 防冲突
 *  - 默认 30 天后清理 (清理任务 cron 留给运维侧实现, 这里只标 ended_at)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    private final ChatRecordMapper recordMapper;
    private final ChatRecordChunkMapper chunkMapper;
    private final ChatAuditLogMapper auditLogMapper;

    @Value("${record.storage-path:/tmp/chat-records}")
    private String storagePath;

    private Path rootDir;

    @PostConstruct
    public void init() throws IOException {
        rootDir = Paths.get(storagePath).toAbsolutePath();
        Files.createDirectories(rootDir);
        log.info("[record] storage dir = {}", rootDir);
    }

    /**
     * 开启一段录制 (前端 SDK 初始化时调用).
     * 必须传入 consentGiven=true; 否则视为不合规, 拒绝创建.
     */
    @Transactional
    public ApiResponse<Map<String, Object>> init(Long sessionId, Boolean consent) {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        if (consent == null || !consent) {
            log.warn("[record] refuse init without consent: user={} session={}", uid, sessionId);
            audit(uid, role, "RECORD_DENY_NO_CONSENT", String.valueOf(sessionId), "拒绝无同意录制");
            return ApiResponse.fail(403, "未获得用户同意, 拒绝录制");
        }

        ChatRecord r = new ChatRecord();
        r.setSessionId(sessionId);
        r.setUserId(uid);
        r.setUserRole(role);
        r.setStartedAt(LocalDateTime.now());
        r.setChunkCount(0);
        r.setTotalBytes(0L);
        r.setConsentGiven(true);
        recordMapper.insert(r);

        audit(uid, role, "RECORD_INIT", String.valueOf(sessionId), "recordId=" + r.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("recordId", r.getId());
        data.put("storagePath", storagePath);
        return ApiResponse.ok(data);
    }

    /**
     * 上传一个分片. 用 multipart/form-data 接收 binary (浏览器 MediaRecorder 直接送 Blob).
     */
    @Transactional
    public ApiResponse<Map<String, Object>> uploadChunk(Long recordId, Integer sequenceNo,
                                                       Integer durationMs, MultipartFile file) throws IOException {
        Long uid = UserContext.userId();
        String role = UserContext.role();

        ChatRecord r = recordMapper.selectById(recordId);
        if (r == null) {
            return ApiResponse.fail(404, "record 不存在");
        }
        if (!r.getUserId().equals(uid)) {
            audit(uid, role, "RECORD_FORBIDDEN", String.valueOf(recordId), "非本人 record 拒绝写入");
            return ApiResponse.fail(403, "无权写入该 record");
        }
        if (r.getEndedAt() != null) {
            return ApiResponse.fail(409, "record 已结束, 不接受新分片");
        }

        byte[] bytes = file.getBytes();
        if (bytes.length == 0) {
            return ApiResponse.fail(400, "分片为空");
        }
        String mime = file.getContentType() != null ? file.getContentType() : "video/webm";

        // 落盘: <root>/<recordId>/<sequence>-<uuid>.webm
        Path recordDir = rootDir.resolve(String.valueOf(recordId));
        Files.createDirectories(recordDir);
        String filename = sequenceNo + "-" + UUID.randomUUID() + extOf(mime);
        Path target = recordDir.resolve(filename);
        Files.write(target, bytes);

        ChatRecordChunk c = new ChatRecordChunk();
        c.setRecordId(recordId);
        c.setSequenceNo(sequenceNo);
        c.setMimeType(mime);
        c.setDurationMs(durationMs != null ? durationMs : 0);
        c.setByteSize(bytes.length);
        c.setStoragePath(target.toString());
        c.setUploadedAt(LocalDateTime.now());
        chunkMapper.insert(c);

        // 更新 record 聚合统计
        r.setChunkCount(r.getChunkCount() + 1);
        r.setTotalBytes(r.getTotalBytes() + bytes.length);
        recordMapper.updateById(r);

        Map<String, Object> data = new HashMap<>();
        data.put("chunkId", c.getId());
        data.put("byteSize", bytes.length);
        data.put("totalBytes", r.getTotalBytes());
        data.put("chunkCount", r.getChunkCount());
        return ApiResponse.ok(data);
    }

    /**
     * 结束录制.
     *  endReason: NORMAL(用户主动停) / PAGE_CLOSE(关闭页面) / PROCESS_KILLED(被杀进程)
     *             / ERROR(异常) / USER_STOP(SDK 主动停, 与 NORMAL 类似但语义不同)
     */
    @Transactional
    public ApiResponse<Void> end(Long recordId, String endReason) {
        Long uid = UserContext.userId();
        String role = UserContext.role();

        ChatRecord r = recordMapper.selectById(recordId);
        if (r == null) return ApiResponse.fail(404, "record 不存在");
        if (!r.getUserId().equals(uid)) {
            audit(uid, role, "RECORD_FORBIDDEN", String.valueOf(recordId), "非本人 record 拒绝结束");
            return ApiResponse.fail(403, "无权结束该 record");
        }
        if (r.getEndedAt() == null) {
            r.setEndedAt(LocalDateTime.now());
            r.setEndReason(endReason != null ? endReason : "NORMAL");
            recordMapper.updateById(r);
            audit(uid, role, "RECORD_END", String.valueOf(recordId),
                "reason=" + r.getEndReason() + " chunks=" + r.getChunkCount() + " bytes=" + r.getTotalBytes());
        }
        return ApiResponse.ok();
    }

    /**
     * 查询某会话的所有录像 (用于回溯/审计).
     */
    public ApiResponse<List<ChatRecord>> listBySession(Long sessionId) {
        QueryWrapper<ChatRecord> q = new QueryWrapper<>();
        q.eq("session_id", sessionId).orderByDesc("started_at");
        return ApiResponse.ok(recordMapper.selectList(q));
    }

    /**
     * 列出某录像的所有分片 (回放时按 sequenceNo 排序).
     */
    public ApiResponse<List<ChatRecordChunk>> chunks(Long recordId) {
        QueryWrapper<ChatRecordChunk> q = new QueryWrapper<>();
        q.eq("record_id", recordId).orderByAsc("sequence_no");
        return ApiResponse.ok(chunkMapper.selectList(q));
    }

    private void audit(Long uid, String role, String action, String target, String detail) {
        try {
            ChatAuditLog a = new ChatAuditLog();
            a.setActorId(uid);
            a.setActorRole(role);
            a.setAction(action);
            a.setTarget(target);
            a.setDetail(detail);
            a.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(a);
        } catch (Exception e) {
            log.error("[record] audit log write failed", e);
        }
    }

    private static String extOf(String mime) {
        if (mime == null) return ".bin";
        return switch (mime) {
            case "video/webm" -> ".webm";
            case "video/mp4"  -> ".mp4";
            case "audio/webm" -> ".webm";
            default -> ".bin";
        };
    }
}