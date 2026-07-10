package com.chat.voice.service;

import com.chat.common.m3.M3Capability;
import com.chat.voice.entity.VoiceCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * VoiceAiAgent - 智能电话 AI Agent (对话决策).
 * ----------------------------------------------------------------------------
 * 注入 M3Capability, 默认走自研 AI (LocalAiService, @Primary).
 * 零外部依赖, 0-1ms 响应, 完全可解释.
 *
 * 阶段 1: 简化版 (关键词意图 + LocalAiService.chat 兑底)
 * 阶段 2: 完整 Function Calling (依赖 M3 tool use)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceAiAgent {

    private final M3Capability m3;

    /**
     * AI 决策: 给定客户说的话, 返回 AI 回复 + 决策动作.
     * @param call 通话上下文
     * @param userText 客户 ASR 转写文字
     * @return AIResponse{text, action, actionParams}
     */
    public AIResponse decide(VoiceCall call, String userText) {
        if (userText == null || userText.isEmpty()) {
            return new AIResponse("抱歉没听清, 请再说一遍?", null, null);
        }

        // 1) 意图识别 (轻量级关键词, 阶段 2 接 M3 Function Calling)
        String lower = userText.toLowerCase();
        if (lower.contains("人工") || lower.contains("真人") || lower.contains("坐席") || lower.contains("转接")) {
            return new AIResponse("好的, 正在为您转接人工客服, 请稍等",
                    "transfer_to_human", Map.of());
        }
        if (lower.contains("再见") || lower.contains("bye") || lower.contains("挂")) {
            return new AIResponse("感谢您的来电, 祝您生活愉快!",
                    "end_call", Map.of());
        }
        if (lower.contains("订单") && (lower.contains("查询") || lower.contains("查") || lower.contains("看"))) {
            return new AIResponse("好的, 请提供您的订单号",
                    "ask_order_id", Map.of());
        }
        if (lower.contains("退款") || lower.contains("退货")) {
            return new AIResponse("理解您的心情, 请告诉我订单号, 我帮您发起退款",
                    "ask_order_id", Map.of());
        }
        if (lower.contains("投诉")) {
            return new AIResponse("非常抱歉给您带来不便, 我马上为您转接主管处理",
                    "transfer_to_human", Map.of("priority", "high"));
        }

        // 2) 兜底: 调 M3 chat 生成自然回复
        try {
            M3Capability.ChatRequest req = M3Capability.ChatRequest.builder()
                    .messages(List.of(
                        M3Capability.ChatMessage.builder()
                            .role("system")
                            .content("你是 MiniMax 智能电话客服, 用中文回答, 50 字以内, 简洁专业. 听到 '人工/真人/坐席/转接' 立即转人工.")
                            .build(),
                        M3Capability.ChatMessage.builder()
                            .role("user")
                            .content(userText)
                            .build()))
                    .maxTokens(150)
                    .temperature(0.7)
                    .build();
            M3Capability.ChatResponse resp = m3.chat(req);
            String text = resp.getContent();
            if (text == null || text.isEmpty()) {
                text = "好的, 我来帮您处理";
            }
            return new AIResponse(text, null, null);
        } catch (Exception e) {
            log.error("[voice-ai] decide failed", e);
            return new AIResponse("好的, 请问您需要什么帮助?", null, null);
        }
    }

    /** AI 决策结果 */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class AIResponse {
        private String text;             // AI 说话内容
        private String action;           // transfer_to_human / end_call / ask_order_id / null
        private Map<String, Object> actionParams;
    }
}