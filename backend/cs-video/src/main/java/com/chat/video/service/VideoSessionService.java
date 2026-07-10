package com.chat.video.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.video.entity.VideoSession;
import com.chat.video.mapper.VideoSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * VideoSessionService - 视频会话服务.
 * ----------------------------------------------------------------------------
 * 职责:
 *   - create(chatSessionId, peerId): 创建会话 (返 sessionId + iceServers 配置)
 *   - 维护 SDP/ICE 状态 (信令在 STOMP 层转发, 这里存 DB 仅用于断线重连)
 *   - end(sessionId): 结束 + 写录像关联
 *
 * ICE/TURN 配置:
 *   - 阶段 1: 公共 STUN (stun.l.google.com:19302) - 同一 NAT 可穿透
 *   - 阶段 2: 部署自建 coturn (TURN + STUN), 跨网络也能通
 *   - 通过 application.yml 配置 chat.video.ice-servers
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoSessionService {

    private final VideoSessionMapper sessionMapper;
    private final ObjectMapper mapper;

    /** 阶段 1 ICE servers (可读 yml 覆盖) */
    private final List<Map<String, Object>> iceServers = List.of(
            Map.of("urls", "stun:stun.l.google.com:19302"),
            Map.of("urls", "stun:stun1.l.google.com:19302"));

    /**
     * 创建视频会话.
     */
    @Transactional
    public ApiResponse<Map<String, Object>> create(Long chatSessionId, Long peerId) {
        Long uid = UserContext.userId();
        if (peerId == null) {
            return ApiResponse.fail(400, "peerId 必填");
        }

        VideoSession s = new VideoSession();
        s.setChatSessionId(chatSessionId);
        s.setInitiatorId(uid);
        s.setPeerId(peerId);
        s.setMode("P2P");
        s.setStatus("INIT");
        s.setStartedAt(LocalDateTime.now());
        sessionMapper.insert(s);

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("sessionId", s.getId());
        data.put("iceServers", iceServers);
        data.put("peerId", peerId);
        data.put("mode", "P2P");
        log.info("[video] session created: id={} initiator={} peer={}", s.getId(), uid, peerId);
        return ApiResponse.ok(data);
    }

    /**
     * 存 SDP offer.
     */
    @Transactional
    public void saveOffer(Long sessionId, String sdp) {
        VideoSession s = sessionMapper.selectById(sessionId);
        if (s == null) return;
        s.setSdpOffer(sdp);
        s.setStatus("CONNECTING");
        sessionMapper.updateById(s);
    }

    /**
     * 存 SDP answer.
     */
    @Transactional
    public void saveAnswer(Long sessionId, String sdp) {
        VideoSession s = sessionMapper.selectById(sessionId);
        if (s == null) return;
        s.setSdpAnswer(sdp);
        if ("CONNECTING".equals(s.getStatus())) {
            s.setStatus("ACTIVE");
        }
        sessionMapper.updateById(s);
    }

    /**
     * 追加 ICE candidate.
     */
    @Transactional
    public void appendIce(Long sessionId, Object candidate) {
        VideoSession s = sessionMapper.selectById(sessionId);
        if (s == null) return;
        try {
            String arr = s.getIceCandidates();
            if (arr == null || arr.isEmpty()) arr = "[]";
            // 简化: 直接 append 字符串 (前端会发完整候选数组)
            // 实际生产应该用 JSON array add, 这里为简单起见重写
            String json = mapper.writeValueAsString(candidate);
            if ("[]".equals(arr)) {
                s.setIceCandidates("[" + json + "]");
            } else {
                s.setIceCandidates(arr.substring(0, arr.length() - 1) + "," + json + "]");
            }
            sessionMapper.updateById(s);
        } catch (Exception e) {
            log.error("[video] save ice failed", e);
        }
    }

    /**
     * 结束视频会话.
     */
    @Transactional
    public ApiResponse<Void> end(Long sessionId) {
        Long uid = UserContext.userId();
        VideoSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "video session 不存在");
        if (!uid.equals(s.getInitiatorId()) && !uid.equals(s.getPeerId())) {
            return ApiResponse.fail(403, "无权操作该 video session");
        }
        s.setStatus("ENDED");
        s.setEndedAt(LocalDateTime.now());
        if (s.getStartedAt() != null) {
            s.setDurationSec((int) java.time.Duration
                    .between(s.getStartedAt(), s.getEndedAt()).getSeconds());
        }
        sessionMapper.updateById(s);
        log.info("[video] session ended: id={} duration={}s", sessionId, s.getDurationSec());
        return ApiResponse.ok();
    }

    /**
     * 关联录像.
     */
    public void linkRecord(Long sessionId, Long recordId) {
        VideoSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            s.setRecordId(recordId);
            sessionMapper.updateById(s);
        }
    }

    /**
     * 查询某用户当前活跃的视频会话.
     */
    public List<VideoSession> findActiveByUser(Long uid) {
        return sessionMapper.findActiveByUser(uid);
    }

    public VideoSession getById(Long id) {
        return sessionMapper.selectById(id);
    }
}