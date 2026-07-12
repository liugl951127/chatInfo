package com.chat.common.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SmartQaResponse - 智能问答响应 (V3.1 增强).
 * ----------------------------------------------------------------------------
 * 字段:
 *   - content:   AI 回复内容 (Markdown 格式, 含 [button:...] 互动按钮)
 *   - intent:    命中的意图
 *   - confidence: 置信度 0-1
 *   - source:    来源 (rule / faq / fallback)
 *   - buttons:   互动按钮元数据 (前端可自定义渲染)
 *   - tokens:    token 估算
 *   - latencyMs: 响应时间
 *   - sessionId: 会话 ID
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartQaResponse {
    private String content;
    private String intent;
    private Double confidence;
    private String source;
    private List<Button> buttons;
    private Integer tokens;
    private Long latencyMs;
    private String sessionId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Button {
        private String type;
        private String label;
        private String payload;
    }
}
