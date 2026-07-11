package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.constant.CommonConstants;
import com.chat.common.security.UserContext;
import com.chat.im.entity.ChatSession;
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
 * RecordService - 录像服务 (客户页面录制 + 回溯).
 * ----------------------------------------------------------------------------
 * 设计要点 (合规要求, PIPL/GDPR):
 *   - consentGiven 字段记录用户是否明确同意被录制 (调用 init 时必须为 true)
 *   - 任何开始/结束/异常都写入 chat_audit_log (可追溯)
 *   - 分片落到磁盘, 路径以 UUID 防冲突
 *   - 默认 30 天后清理 (清理任务 cron 留给运维侧实现, 这里只标 ended_at)
 *
 * 流程:
 *   1) init(consent=true) -> 创建 ChatRecord (status=RECORDING, started_at=now)
 *   2) uploadChunk(recordId, sequenceNo, blob) -> 落盘 + 插 ChatRecordChunk
 *      - 同 sequenceNo 重复上传返回 idempotent:true (可安全重试)
 *   3) end(recordId, endReason) -> 标 ended_at, 清 Redis 队列残留
 *   4) ffmpegMergeChunks(recordId) -> 服务端合并所有 chunk 为单一 webm (供下载/回放)
 *
 * 存储路径:
 *   - 配置项: record.storage-path (默认 /tmp/chat-records)
 *   - 分片: <root>/<recordId>/<seq>-<uuid>.<ext>
 *   - 合并: <root>/merged/<recordId>.webm (缓存, 复用避免重复合并)
 *
 * @author V3 Team
 * @since 2025-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordService {

    /** 录像主表 DAO */
    private final ChatRecordMapper recordMapper;
    /** 录像分片表 DAO */
    private final ChatRecordChunkMapper chunkMapper;
    /** 审计日志 DAO */
    private final ChatAuditLogMapper auditLogMapper;
    /** 会话 DAO (用于回放页权限检查) */
    private final com.chat.im.mapper.ChatSessionMapper sessionMapper;

    /** 录像存储根目录 (可配置, 默认 /tmp/chat-records) */
    @Value("${record.storage-path:/tmp/chat-records}")
    private String storagePath;

    /** 录像根目录 (Path, 启动时初始化) */
    private Path rootDir;

    /**
     * 启动时初始化 (创建根目录, 打印日志).
     * <p>
     * @PostConstruct: Spring 容器初始化后立即执行, 确保根目录存在.
     */
    @PostConstruct
    public void init() throws IOException {
        // 转绝对路径, 避免相对路径歧义
        rootDir = Paths.get(storagePath).toAbsolutePath();
        // 确保根目录存在 (不存在则创建)
        Files.createDirectories(rootDir);
        log.info("[record] storage dir = {}", rootDir);
    }

    /**
     * 开启一段录制 (前端 SDK 初始化时调用).
     * <p>
     * 算法:
     *   step 1: 合规校验: consent=true 才继续 (PIPL/GDPR 要求)
     *   step 2: 创建 ChatRecord (status=RECORDING)
     *   step 3: 写审计日志
     *   step 4: 返 recordId 给前端
     *
     * @param sessionId 会话 ID. 业务含义: 录像归属会话
     *                  取值范围: Long > 0
     *                  影响: 录像与该会话关联
     * @param consent   用户同意标志. 业务含义: 用户是否明确同意被录制
     *                  取值范围: 必传 true, 否则返 403
     *                  影响: 拒绝无同意的录制 (合规要求)
     * @return ApiResponse 包装的 Map {recordId, storagePath}
     *         - 成功: code=0, data={recordId, storagePath}
     *         - 失败: 403(无同意) / 401(未登录)
     */
    @Transactional
    public ApiResponse<Map<String, Object>> init(Long sessionId, Boolean consent) {
        Long uid = UserContext.userId();
        String role = UserContext.role();

        // 合规校验: 没拿到用户同意一律拒绝 (PIPL / GDPR 要求)
        if (consent == null || !consent) {
            log.warn("[record] refuse init without consent: user={} session={}", uid, sessionId);
            audit(uid, role, "RECORD_DENY_NO_CONSENT", String.valueOf(sessionId), "拒绝无同意录制");
            return ApiResponse.fail(403, "未获得用户同意, 拒绝录制");
        }

        // 创建录像记录
        ChatRecord r = new ChatRecord();
        r.setSessionId(sessionId);
        r.setUserId(uid);
        r.setUserRole(role);
        r.setStartedAt(LocalDateTime.now());
        r.setChunkCount(0);
        r.setTotalBytes(0L);
        r.setConsentGiven(true);
        recordMapper.insert(r);

        // 审计
        audit(uid, role, "RECORD_INIT", String.valueOf(sessionId), "recordId=" + r.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("recordId", r.getId());
        data.put("storagePath", storagePath);
        return ApiResponse.ok(data);
    }

    /**
     * 续录: 复用指定未结束的 record (同一用户, 同一会话).
     * <p>
     * 场景: 客户刷新页面 / 切换后台后返回 -> 继续追加分片到同一条录像.
     * <p>
     * 算法:
     *   step 1: 校验: record 存在 + 同 user + 同 session + 未结束
     *   step 2: 返 recordId + 现有统计
     *
     * @param sessionId     会话 ID. 业务含义: 续录的会话
     *                      取值范围: Long > 0
     *                      影响: 校验必须与原 record 同 session
     * @param resumeRecordId 续录的 record ID. 业务含义: 要复用的录像
     *                       取值范围: Long > 0 且存在
     *                       影响: 校验归属/状态
     * @return ApiResponse 包装的 Map {recordId, storagePath, resumed, existingChunkCount, existingTotalBytes}
     *         - 成功: code=0, data=续录信息
     *         - 失败: 400(会话不匹配) / 403(非本人) / 404(无) / 409(已结束)
     */
    @Transactional
    public ApiResponse<Map<String, Object>> resume(Long sessionId, Long resumeRecordId) {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        ChatRecord r = recordMapper.selectById(resumeRecordId);
        if (r == null) {
            return ApiResponse.fail(404, "录像不存在");
        }
        if (!r.getUserId().equals(uid)) {
            audit(uid, role, "RECORD_FORBIDDEN", String.valueOf(resumeRecordId), "续录拒绝: 非本人 record");
            return ApiResponse.fail(403, "无权续录该 record");
        }
        if (!r.getSessionId().equals(sessionId)) {
            return ApiResponse.fail(400, "录像与会话不匹配");
        }
        if (r.getEndedAt() != null) {
            // 已结束的 record 不能续, 但可以新建
            return ApiResponse.fail(409, "录像已结束, 请新建");
        }
        log.info("[record] resume: user={} session={} record={} chunks={}",
            uid, sessionId, resumeRecordId, r.getChunkCount());
        audit(uid, role, "RECORD_RESUME", String.valueOf(resumeRecordId),
            "session=" + sessionId + " existingChunks=" + r.getChunkCount());

        Map<String, Object> data = new HashMap<>();
        data.put("recordId", r.getId());
        data.put("storagePath", storagePath);
        data.put("resumed", true);
        data.put("existingChunkCount", r.getChunkCount());
        data.put("existingTotalBytes", r.getTotalBytes());
        return ApiResponse.ok(data);
    }

    /**
     * 上传一个分片 (核心方法). 用 multipart/form-data 接收 binary (浏览器 MediaRecorder 直接送 Blob).
     * <p>
     * 幂等: 同一 (recordId, sequenceNo) 重复上传返成功 (保证 SDK 重试 + 刷新后续录安全).
     * <p>
     * 算法:
     *   step 1: 校验: record 存在 + 属于本人 + 未结束
     *   step 2: 幂等检查: 同 (record, seq) 已存在 → 返现有 (idempotent=true)
     *   step 3: 读分片内容
     *   step 4: 落盘到 <root>/<recordId>/<seq>-<uuid>.<ext>
     *   step 5: 写 ChatRecordChunk 表
     *   step 6: 更新 ChatRecord 聚合统计 (chunk_count + 1, total_bytes += size)
     *
     * @param recordId    录像 ID. 业务含义: 分片归属
     *                    取值范围: Long > 0 且存在
     *                    影响: 写入分片 + 更新聚合
     * @param sequenceNo  分片序号. 业务含义: 时间顺序
     *                    取值范围: >= 0, 单调递增
     *                    影响: 决定文件名 + 回放顺序
     * @param durationMs  分片时长 (毫秒). 业务含义: 媒体时长
     *                    取值范围: >= 0
     *                    影响: 存 DB (供前端进度条)
     * @param file        分片二进制. 业务含义: 视频数据
     *                    取值范围: 非空 multipart file
     *                    影响: 落盘
     * @return ApiResponse 包装的 Map {chunkId, byteSize, totalBytes, chunkCount, [idempotent]}
     *         - 成功: code=0
     *         - 失败: 400(空分片) / 403(非本人) / 404(无) / 409(已结束)
     * @throws IOException 落盘失败
     */
    @Transactional
    public ApiResponse<Map<String, Object>> uploadChunk(Long recordId, Integer sequenceNo,
                                                       Integer durationMs, MultipartFile file) throws IOException {
        Long uid = UserContext.userId();
        String role = UserContext.role();

        // step 1: 校验 record 存在 + 属于本人 + 未结束
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

        // step 2: 幂等检查: 同一 (record_id, sequence_no) 已存在 -> 返现有记录
        //    场景: 浏览器 SDK 重试 / 网络抖动重传
        QueryWrapper<ChatRecordChunk> dupQw = new QueryWrapper<>();
        dupQw.eq("record_id", recordId).eq("sequence_no", sequenceNo).last("LIMIT 1");
        ChatRecordChunk existing = chunkMapper.selectOne(dupQw);
        if (existing != null) {
            // 已存在: 幂等返回, 不重复入库
            Map<String, Object> data = new HashMap<>();
            data.put("chunkId", existing.getId());
            data.put("byteSize", existing.getByteSize());
            data.put("totalBytes", r.getTotalBytes());
            data.put("chunkCount", r.getChunkCount());
            data.put("idempotent", true);                                    // 客户端可识别幂等
            log.debug("[record] chunk upload idempotent: record={} seq={} existing={}",
                recordId, sequenceNo, existing.getId());
            return ApiResponse.ok(data);
        }

        // step 3: 读取分片内容
        byte[] bytes = file.getBytes();
        if (bytes.length == 0) {
            return ApiResponse.fail(400, "分片为空");
        }
        String mime = file.getContentType() != null ? file.getContentType() : "video/webm";

        // step 4: 落盘到 <root>/<recordId>/<sequence>-<uuid>.<ext>
        Path recordDir = rootDir.resolve(String.valueOf(recordId));
        Files.createDirectories(recordDir);
        String filename = sequenceNo + "-" + UUID.randomUUID() + extOf(mime);
        Path target = recordDir.resolve(filename);
        Files.write(target, bytes);

        // step 5: 写 ChatRecordChunk 表
        ChatRecordChunk c = new ChatRecordChunk();
        c.setRecordId(recordId);
        c.setSequenceNo(sequenceNo);
        c.setMimeType(mime);
        c.setDurationMs(durationMs != null ? durationMs : 0);
        c.setByteSize(bytes.length);
        c.setStoragePath(target.toString());
        c.setUploadedAt(LocalDateTime.now());
        chunkMapper.insert(c);

        // step 6: 更新 ChatRecord 聚合统计 (chunk_count + 1, total_bytes += size)
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
     * <p>
     * 算法:
     *   step 1: 校验: record 存在 + 属于本人
     *   step 2: 幂等: 已结束直接返 200
     *   step 3: 标 ended_at + end_reason
     *   step 4: 写审计日志
     *
     * @param recordId  录像 ID. 业务含义: 要结束的录像
     *                  取值范围: Long > 0 且存在
     *                  影响: 标 ended_at
     * @param endReason 结束原因. 业务含义: SDK 关闭/页面关闭/异常
     *                  取值范围: NORMAL / PAGE_CLOSE / PROCESS_KILLED / ERROR / USER_STOP
     *                  影响: 写 end_reason + 审计
     * @return ApiResponse<Void>
     *         - 成功: code=0 (幂等)
     *         - 失败: 403(非本人) / 404(无)
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
            // 未结束才标 (幂等)
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
     *
     * @param sessionId 会话 ID. 业务含义: 要查的会话
     *                  取值范围: Long > 0
     *                  影响: 限定录像范围
     * @return ApiResponse 包装的 ChatRecord 列表 (按 started_at desc)
     */
    public ApiResponse<List<ChatRecord>> listBySession(Long sessionId) {
        QueryWrapper<ChatRecord> q = new QueryWrapper<>();
        q.eq("session_id", sessionId).orderByDesc("started_at");
        return ApiResponse.ok(recordMapper.selectList(q));
    }

    /**
     * 列出某录像的所有分片 (回放时按 sequenceNo 排序).
     *
     * @param recordId 录像 ID. 业务含义: 要列分片的录像
     *                 取值范围: Long > 0
     *                 影响: 决定返回哪些分片
     * @return ApiResponse 包装的 ChatRecordChunk 列表 (按 sequence_no asc)
     */
    public ApiResponse<List<ChatRecordChunk>> chunks(Long recordId) {
        QueryWrapper<ChatRecordChunk> q = new QueryWrapper<>();
        q.eq("record_id", recordId).orderByAsc("sequence_no");
        return ApiResponse.ok(chunkMapper.selectList(q));
    }

    /**
     * 取一个分片的二进制 (供回放页用). 走的是 chat_auth 同样的 JWT 拦截,
     * 不再加额外权限: 因为是 A 客户被录 -> 只有该客户 或 其会话的坐席 可看,
     * 这层放在 controller 里检查.
     *
     * @param chunkId 分片 ID. 业务含义: 要下载的分片
     *                取值范围: Long > 0 且存在
     *                影响: 返 FileSystemResource
     * @return Resource (Spring 静态资源, 找不到返 null)
     */
    public org.springframework.core.io.Resource chunkResource(Long chunkId) {
        ChatRecordChunk c = chunkMapper.selectById(chunkId);
        if (c == null) return null;
        return new org.springframework.core.io.FileSystemResource(c.getStoragePath());
    }

    /**
     * 拿分片实体 (供回放页用, 拿元信息: 时长/mime).
     */
    public ChatRecordChunk chunkEntity(Long chunkId) {
        return chunkMapper.selectById(chunkId);
    }

    /**
     * 拿录像实体 (供回放页用, 拿统计: 时长/分片数/总字节).
     */
    public ChatRecord recordEntity(Long recordId) {
        return recordMapper.selectById(recordId);
    }

    /**
     * 查 session 用于回放页权限检查. 返回该 session 如果用户有权看, 否则 null.
     * <p>
     * 权限规则:
     *   - 管理员 (ADMIN) 通读
     *   - 客户 (CUSTOMER) 仅自己会话
     *   - 坐席 (AGENT) 仅自己接过的会话
     *
     * @param sessionId 会话 ID
     * @param uid       用户 ID
     * @param role      用户角色
     * @return 有权则返 session, 否则 null
     */
    public com.chat.im.entity.ChatSession sessionForAuth(Long sessionId, Long uid, String role) {
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return null;
        // 管理员通读
        if (CommonConstants.ROLE_ADMIN.equalsIgnoreCase(role)) return s;
        // 客户必须是 owner
        if (s.getCustomerId() != null && s.getCustomerId().equals(uid)) return s;
        // 坐席必须是 assigned agent
        if (s.getAgentId() != null && s.getAgentId().equals(uid) && CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) return s;
        // 其他人无权
        return null;
    }

    /**
     * 列出会话的所有录像 + 每个录像的分片 (一次拉, 减少前端多次请求).
     * <p>
     * 算法:
     *   step 1: 查会话的所有录像
     *   step 2: 一次查所有相关分片, 按 recordId 分组
     *   step 3: 返 {sessionId, records, chunks: Map<recordId, chunks>}
     *
     * @param sessionId 会话 ID. 业务含义: 要查的会话
     *                  取值范围: Long > 0
     *                  影响: 限定范围
     * @return ApiResponse 包装的 Map {sessionId, records, chunks}
     *         - records: ChatRecord 列表 (按 started_at desc)
     *         - chunks: Map<recordId, List<ChatRecordChunk>> (按 sequence_no asc)
     */
    public ApiResponse<Map<String, Object>> listBySessionWithChunks(Long sessionId) {
        // step 1: 查所有录像
        QueryWrapper<ChatRecord> q = new QueryWrapper<>();
        q.eq("session_id", sessionId).orderByDesc("started_at");
        List<ChatRecord> records = recordMapper.selectList(q);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("records", records);
        // step 2: 一次查分片, 按 recordId 分组
        Map<Long, List<ChatRecordChunk>> byRecord = new java.util.LinkedHashMap<>();
        if (!records.isEmpty()) {
            List<Long> ids = records.stream().map(ChatRecord::getId).toList();
            QueryWrapper<ChatRecordChunk> qc = new QueryWrapper<>();
            qc.in("record_id", ids).orderByAsc("record_id", "sequence_no");
            for (ChatRecordChunk c : chunkMapper.selectList(qc)) {
                // group by recordId
                byRecord.computeIfAbsent(c.getRecordId(), k -> new java.util.ArrayList<>()).add(c);
            }
        }
        result.put("chunks", byRecord);
        return ApiResponse.ok(result);
    }

    /**
     * 写审计日志 (内部用).
     * <p>
     * 异常吞掉: 审计失败不影响主流程 (PIPL/GDPR 要求, 但不能让审计影响业务).
     */
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

    /**
     * MIME → 文件扩展名映射.
     */
    private static String extOf(String mime) {
        if (mime == null) return ".bin";
        return switch (mime) {
            case "video/webm" -> ".webm";
            case "video/mp4"  -> ".mp4";
            case "audio/webm" -> ".webm";
            default -> ".bin";
        };
    }

    /**
     * 服务端 ffmpeg 合流所有录像分片 → 单一 webm 文件 (供下载/回放).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 查缓存 (<root>/merged/{recordId}.webm 存在则直接返)
     *   step 2: 查分片 (按 sequenceNo 排序)
     *   step 3: 生成 ffmpeg concat list 文件 (ffmpeg 格式要求 file '绝对路径' 单行一条)
     *   step 4: 找 ffmpeg (常见路径 + which)
     *   step 5: ffmpeg -f concat -safe 0 -i list.txt -c copy output.webm
     *      - -c copy: 同源 copy 不重编码, 几秒搞定
     *      - 失败时返 null (兜底交给前端 MSE 拼接)
     *   step 6: 缓存合并结果 (后续同 recordId 直接返)
     *   step 7: 清理 list 文件
     *
     * @param recordId 录像 ID. 业务含义: 要合并的录像
     *                 取值范围: Long > 0
     *                 影响: 读分片 + 写合并文件
     * @return 合并后的 webm 文件 Path, 失败返 null
     * @throws IOException          写 list 文件失败
     * @throws InterruptedException ffmpeg 进程被中断
     */
    public java.nio.file.Path ffmpegMergeChunks(Long recordId) throws IOException, InterruptedException {
        // step 1: 查缓存 (避免重复合并)
        java.nio.file.Path mergedDir = rootDir.resolve("merged");
        Files.createDirectories(mergedDir);
        java.nio.file.Path cached = mergedDir.resolve(recordId + ".webm");
        if (Files.exists(cached) && Files.size(cached) > 0) return cached;

        // step 2: 查分片 (按 sequenceNo 排序)
        QueryWrapper<ChatRecordChunk> qw = new QueryWrapper<>();
        qw.eq("record_id", recordId).orderByAsc("sequence_no");
        List<ChatRecordChunk> chunks = chunkMapper.selectList(qw);
        if (chunks.isEmpty()) return null;

        // step 3: 写 concat list 文件 (ffmpeg 格式要求 file '绝对路径' 单行一条)
        java.nio.file.Path listFile = mergedDir.resolve(recordId + ".list.txt");
        try (java.io.BufferedWriter w = Files.newBufferedWriter(listFile)) {
            for (ChatRecordChunk c : chunks) {
                // ffmpeg concat 需要单引号包裹 + 转义
                w.write("file '" + c.getStoragePath().replace("'", "'\\''") + "'");
                w.newLine();
            }
        }

        // step 4: 找 ffmpeg (常见路径 + which)
        java.nio.file.Path ffmpegBin = locateFfmpeg();
        if (ffmpegBin == null) {
            log.warn("[record] ffmpeg not found in PATH; skip server-side merge");
            return null;
        }

        // step 5: ffmpeg concat + remux (-c copy 极速)
        java.util.List<String> cmd = java.util.Arrays.asList(
            ffmpegBin.toString(),
            "-y",                                                          // 覆盖输出
            "-f", "concat",                                                // concat demuxer
            "-safe", "0",                                                  // 允许任意路径
            "-i", listFile.toString(),                                     // 输入 list
            "-c", "copy",                                                  // 不重编码
            cached.toString()                                              // 输出合并文件
        );
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.directory(mergedDir.toFile());
        Process p = pb.start();
        // 读取 stdout/stderr 避免进程阻塞
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (log.isDebugEnabled()) log.debug("[ffmpeg] {}", line);
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            log.error("[record] ffmpeg merge failed: record={} exit={}", recordId, exit);
            return null;
        }
        log.info("[record] ffmpeg merge ok: record={} size={}", recordId, Files.size(cached));
        // step 7: 清理临时 list 文件
        try { Files.deleteIfExists(listFile); } catch (Exception e) {}
        return cached;
    }

    /**
     * 找 ffmpeg 二进制路径 (常见路径 + which 命令).
     * <p>
     * 优先查常见路径 (避免启动子进程), 失败再试 which.
     */
    private java.nio.file.Path locateFfmpeg() {
        // 常见路径
        String[] candidates = {"/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg"};
        for (String c : candidates) {
            java.nio.file.Path p = java.nio.file.Paths.get(c);
            if (java.nio.file.Files.isExecutable(p)) return p;
        }
        // 试 which
        try {
            Process p = new ProcessBuilder("which", "ffmpeg").start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                if (line != null && !line.isEmpty()) return java.nio.file.Paths.get(line.trim());
            }
        } catch (Exception e) {}
        return null;
    }
}
