package com.chat.voice.controller;

import com.chat.voice.service.VoiceCallService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

/**
 * VoiceSignalingController - 智能电话 STOMP 控制器.
 *
 * 阶段 1 STOMP 频道:
 *   - /app/voice/{id}/hangup        客户端 -> 服务端: 挂断
 *   - /app/voice/{id}/dtmf          客户端 -> 服务端: 按键 (DTMF)
 *   - /app/voice/{id}/media         客户端 -> 服务端: 静音/恢复
 *
 * 推送给客户端:
 *   - /user/queue/voice/{id}/tts        AI 语音 (text + audioB64)
 *   - /user/queue/voice/{id}/transcript 实时转写
 *   - /user/queue/voice/{id}/status     通话状态
 */
@Slf4j
@Tag(name = "智能电话 - 信令")
@Controller
@RequiredArgsConstructor
public class VoiceSignalingController {

    private final SimpMessagingTemplate messagingTemplate;
    private final VoiceCallService callService;

    @MessageMapping("/voice/{id}/hangup")
    public void onHangup(@DestinationVariable String id, @Payload Map<String, Object> body) {
        Long callId = Long.valueOf(id);
        Long from = asLong(body.get("from"));
        Long to = asLong(body.get("to"));
        log.info("[voice] hangup: call={} from={} to={}", id, from, to);
        try {
            // 推送给对端
            if (to != null) {
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(to), "/queue/voice/" + id + "/status",
                        Map.of("type", "CALL_ENDED", "callId", callId));
            }
            // 写状态
            callService.end(callId);
        } catch (Exception e) {
            log.error("[voice] hangup failed", e);
        }
    }

    @MessageMapping("/voice/{id}/dtmf")
    public void onDtmf(@DestinationVariable String id, @Payload Map<String, Object> body) {
        Long to = asLong(body.get("to"));
        String digit = (String) body.get("digit");
        log.debug("[voice] dtmf: call={} digit={}", id, digit);
        if (to != null) {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(to), "/queue/voice/" + id + "/dtmf", body);
        }
    }

    @MessageMapping("/voice/{id}/media")
    public void onMedia(@DestinationVariable String id, @Payload Map<String, Object> body) {
        Long to = asLong(body.get("to"));
        if (to != null) {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(to), "/queue/voice/" + id + "/media", body);
        }
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.valueOf(o.toString()); } catch (Exception e) { return null; }
    }
}