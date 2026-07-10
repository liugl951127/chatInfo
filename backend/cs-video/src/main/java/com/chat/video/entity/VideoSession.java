package com.chat.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * VideoSession - 视频会话.
 * ----------------------------------------------------------------------------
 * 1v1 WebRTC 视频通话, 通过 STOMP 信令交换.
 * 阶段 1: P2P 模式 (1v1)
 * 阶段 3: SFU 模式 (mediasoup, 多方)
 */
@Data
@TableName("video_session")
public class VideoSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 IM 会话 ID (可选) */
    private Long chatSessionId;

    /** 发起人 (谁先按的"视频") */
    private Long initiatorId;

    /** 对端 (坐席/客户) */
    private Long peerId;

    /** P2P / SFU (阶段 1 固定 P2P) */
    private String mode;

    /** INIT / CONNECTING / ACTIVE / ENDED */
    private String status;

    /** SDP offer (JSON) */
    private String sdpOffer;

    /** SDP answer (JSON) */
    private String sdpAnswer;

    /** ICE candidates (JSON 数组) */
    private String iceCandidates;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** 通话时长 (秒) */
    private Integer durationSec;
    /** 关联录像 ID (合規录制) */
    private Long recordId;
    private LocalDateTime createdAt;
}