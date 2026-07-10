package com.chat.video.controller;

import com.chat.video.service.VideoSessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.Map;

/**
 * VideoSignalingController - WebRTC 信令 STOMP 控制器.
 * ----------------------------------------------------------------------------
 * 信令中转模式 (不存数据库, 只转发):
 *   A 发起 -> 服务端收到 -> 推给 B (/user/{B}/queue/video/{sid}/...)
 *   B 回应 -> 服务端收到 -> 推给 A
 *
 * 阶段 1: 内存中转, 简单 broker
 * 阶段 2: 跨实例用 redis relay
 */
@Slf4j
@Tag(name = "视频会话 - 信令")
@Controller
@RequiredArgsConstructor
public class VideoSignalingController {

    private final SimpMessagingTemplate messagingTemplate;
    private final VideoSessionService sessionService;

    /** A 发送 offer -> 转给 B */
    @MessageMapping("/video/{sid}/offer")
    public void onOffer(@DestinationVariable String sid, @Payload Map<String, Object> body) {
        Long sessionId = Long.valueOf(sid);
        Long from = asLong(body.get("from"));
        Long to = asLong(body.get("to"));
        String sdp = (String) body.get("sdp");

        if (sdp != null) sessionService.saveOffer(sessionId, sdp);
        log.info("[video] offer: sid={} from={} to={}", sid, from, to);

        // 转发给对端
        messagingTemplate.convertAndSendToUser(
                String.valueOf(to), "/queue/video/" + sid + "/offer", body);
    }

    /** B 回应 answer -> 转给 A */
    @MessageMapping("/video/{sid}/answer")
    public void onAnswer(@DestinationVariable String sid, @Payload Map<String, Object> body) {
        Long sessionId = Long.valueOf(sid);
        Long to = asLong(body.get("to"));
        String sdp = (String) body.get("sdp");

        if (sdp != null) sessionService.saveAnswer(sessionId, sdp);
        log.info("[video] answer: sid={} to={}", sid, to);

        messagingTemplate.convertAndSendToUser(
                String.valueOf(to), "/queue/video/" + sid + "/answer", body);
    }

    /** ICE 候选 -> 转发 */
    @MessageMapping("/video/{sid}/ice")
    public void onIce(@DestinationVariable String sid, @Payload Map<String, Object> body) {
        Long sessionId = Long.valueOf(sid);
        Long to = asLong(body.get("to"));

        sessionService.appendIce(sessionId, body.get("candidate"));
        log.debug("[video] ice: sid={} to={}", sid, to);

        messagingTemplate.convertAndSendToUser(
                String.valueOf(to), "/queue/video/" + sid + "/ice", body);
    }

    /** 挂断 -> 推给对端 */
    @MessageMapping("/video/{sid}/hangup")
    public void onHangup(@DestinationVariable String sid, @Payload Map<String, Object> body) {
        Long to = asLong(body.get("to"));
        log.info("[video] hangup: sid={} to={}", sid, to);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(to), "/queue/video/" + sid + "/hangup", body);
    }

    /** 媒体控制: mute / unmute / camera-off / camera-on */
    @MessageMapping("/video/{sid}/media")
    public void onMediaControl(@DestinationVariable String sid, @Payload Map<String, Object> body) {
        Long to = asLong(body.get("to"));
        log.debug("[video] media control: sid={} action={}", sid, body.get("action"));
        messagingTemplate.convertAndSendToUser(
                String.valueOf(to), "/queue/video/" + sid + "/media", body);
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.valueOf(o.toString());
    }
}