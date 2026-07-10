package com.chat.voice.service;

import com.chat.common.api.ApiResponse;
import com.chat.common.m3.M3Capability;
import com.chat.common.security.UserContext;
import com.chat.voice.entity.VoiceCall;
import com.chat.voice.mapper.VoiceCallMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * VoiceCallService - 通话核心服务.
 * ----------------------------------------------------------------------------
 * 流程:
 *   1) initiate(caller, callee, aiEnabled): 创建通话 -> 状态 RINGING, 推 callee
 *   2) answer(callId): callee 接听 -> 状态 CONNECTED
 *   3) asrAndDecide(callId, audioBytes): ASR 转写 + AI 决策 + TTS 生成
 *   4) end(callId): 挂断 -> 状态 ENDED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceCallService {

    private final VoiceCallMapper callMapper;
    private final VoiceAiAgent aiAgent;
    private final M3Capability m3;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper mapper;

    /**
     * 发起通话.
     */
    @Transactional
    public ApiResponse<VoiceCall> initiate(String callee, Boolean aiEnabled) {
        Long uid = UserContext.userId();
        VoiceCall c = new VoiceCall();
        c.setCallerId(uid);
        c.setCalleeNumber(callee);
        c.setDirection("OUTBOUND");
        c.setStatus("RINGING");
        c.setAiEnabled(aiEnabled != null && aiEnabled ? 1 : 0);
        c.setTranscript("[]");
        c.setAiActions("[]");
        c.setStartedAt(LocalDateTime.now());
        callMapper.insert(c);

        // 推 STOMP 通知被叫 (如果 callee 是 uid 数字)
        try {
            Long calleeUid = Long.valueOf(callee);
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "INCOMING_CALL");
            payload.put("callId", c.getId());
            payload.put("callerId", uid);
            payload.put("aiEnabled", c.getAiEnabled());
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(calleeUid), "/queue/voice/" + c.getId() + "/status", payload);
        } catch (NumberFormatException ignored) {
            // 外部号码, 不推送 (阶段 2 接 SIP)
        }
        return ApiResponse.ok(c);
    }

    /**
     * 接听.
     */
    @Transactional
    public ApiResponse<Void> answer(Long callId) {
        VoiceCall c = callMapper.selectById(callId);
        if (c == null) return ApiResponse.fail(404, "通话不存在");
        c.setStatus("CONNECTED");
        c.setStartedAt(LocalDateTime.now());
        callMapper.updateById(c);
        return ApiResponse.ok();
    }

    /**
     * ASR 转写 + AI 决策 + TTS.
     * 这是单次对话的入口.
     * @param audioBytes 客户说话的音频 (mp3/wav)
     * @return AI 回复 (text + audio base64)
     */
    public ApiResponse<Map<String, Object>> asrAndDecide(Long callId, byte[] audioBytes) {
        VoiceCall c = callMapper.selectById(callId);
        if (c == null || !"CONNECTED".equals(c.getStatus())) {
            return ApiResponse.fail(409, "通话未在连接状态");
        }

        // 1) ASR
        String userText = m3.asr(audioBytes);
        log.info("[voice] ASR: call={} text={}", callId, userText);

        // 2) AI 决策
        VoiceAiAgent.AIResponse aiResp = aiAgent.decide(c, userText);

        // 3) 写 transcript + aiActions
        appendTranscript(c, "user", userText);
        appendTranscript(c, "ai", aiResp.getText());
        appendAiAction(c, aiResp);
        callMapper.updateById(c);

        // 4) TTS 生成 AI 语音
        byte[] aiAudio = m3.tts(aiResp.getText(),
                M3Capability.TtsConfig.builder()
                        .voiceId("male-qn-qingse")
                        .speed(1.0).volume(1.0)
                        .emotion("neutral")
                        .format("mp3")
                        .build());

        // 5) 推 STOMP 实时通知 (TTS 音频 + transcript)
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "AI_SPEAK");
            payload.put("callId", callId);
            payload.put("text", aiResp.getText());
            payload.put("action", aiResp.getAction());
            payload.put("audioB64", Base64.getEncoder().encodeToString(aiAudio));
            payload.put("ts", System.currentTimeMillis());
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(c.getCallerId()), "/queue/voice/" + callId + "/tts", payload);
        } catch (Exception e) {
            log.error("[voice] push TTS failed", e);
        }

        // 6) 处理 action
        if ("end_call".equals(aiResp.getAction())) {
            end(callId);
        } else if ("transfer_to_human".equals(aiResp.getAction())) {
            // TODO 阶段 2: 调人工坐席分配
            log.info("[voice] transfer to human requested: call={}", callId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userText", userText);
        result.put("aiText", aiResp.getText());
        result.put("action", aiResp.getAction());
        result.put("audioB64", Base64.getEncoder().encodeToString(aiAudio));
        return ApiResponse.ok(result);
    }

    /**
     * 挂断.
     */
    @Transactional
    public ApiResponse<Void> end(Long callId) {
        Long uid = UserContext.userId();
        VoiceCall c = callMapper.selectById(callId);
        if (c == null) return ApiResponse.fail(404, "通话不存在");
        if (!uid.equals(c.getCallerId()) && !uid.equals(parseUid(c.getCalleeNumber()))) {
            return ApiResponse.fail(403, "无权操作");
        }
        c.setStatus("ENDED");
        c.setEndedAt(LocalDateTime.now());
        if (c.getStartedAt() != null) {
            c.setDurationSec((int) java.time.Duration
                    .between(c.getStartedAt(), c.getEndedAt()).getSeconds());
        }
        callMapper.updateById(c);
        // 推 STOMP 状态
        try {
            Map<String, Object> payload = Map.of("type", "CALL_ENDED", "callId", callId);
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(c.getCallerId()), "/queue/voice/" + callId + "/status", payload);
        } catch (Exception ignored) {}
        return ApiResponse.ok();
    }

    /** 查某用户活跃通话 */
    public List<VoiceCall> findActiveByCaller(Long uid) {
        return callMapper.findActiveByCaller(uid);
    }

    public VoiceCall getById(Long id) {
        return callMapper.selectById(id);
    }

    // ============ helpers ============

    private void appendTranscript(VoiceCall c, String speaker, String text) {
        try {
            List<Map<String, Object>> arr = mapper.readValue(
                    c.getTranscript() == null ? "[]" : c.getTranscript(), List.class);
            arr.add(Map.of("speaker", speaker, "text", text, "ts", System.currentTimeMillis()));
            c.setTranscript(mapper.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("[voice] append transcript failed", e);
        }
    }

    private void appendAiAction(VoiceCall c, VoiceAiAgent.AIResponse ai) {
        try {
            List<Map<String, Object>> arr = mapper.readValue(
                    c.getAiActions() == null ? "[]" : c.getAiActions(), List.class);
            Map<String, Object> act = new HashMap<>();
            act.put("text", ai.getText());
            act.put("action", ai.getAction());
            act.put("ts", System.currentTimeMillis());
            arr.add(act);
            c.setAiActions(mapper.writeValueAsString(arr));
        } catch (Exception e) {
            log.warn("[voice] append ai action failed", e);
        }
    }

    private Long parseUid(String s) {
        try { return Long.valueOf(s); } catch (Exception e) { return null; }
    }
}