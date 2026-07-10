package com.chat.voice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * VoiceCall - 通话.
 * ----------------------------------------------------------------------------
 * 阶段 1 智能电话:
 *   - 客户在浏览器内拨打 (软电话, WebRTC AudioTrack)
 *   - AI 自动接听 (或坐席接听)
 *   - 实时 ASR 转写
 *   - AI Agent 决策 + TTS 播放
 *   - 全程录音 (合规)
 *
 * 阶段 2 升级:
 *   - 接真实 SIP/PSTN (FreeSWITCH / 阿里云语音)
 *   - 客户拨打 400 电话 -> 落 IVR -> 分配 AI
 */
@Data
@TableName("voice_call")
public class VoiceCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 主叫 ID (谁拨的) */
    private Long callerId;
    /** 被叫 (uid 内部 / number 外部) */
    private String calleeNumber;
    /** INBOUND (客户呼入) / OUTBOUND (AI 呼出) */
    private String direction;
    /** RINGING / CONNECTED / ON_HOLD / ENDED / FAILED */
    private String status;
    /** 是否 AI 接听 (0=人工 / 1=AI) */
    private Integer aiEnabled;

    /** 通话转写 JSON: [{speaker, text, ts}] */
    private String transcript;
    /** AI 决策日志: [{type, action, ts}] */
    private String aiActions;

    /** 关联录音 ID */
    private Long recordId;

    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer durationSec;
    /** CSAT 评分 (1-5) */
    private Integer csatScore;
    private LocalDateTime createdAt;
}