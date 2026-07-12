package com.chat.common.ai.dto;

import lombok.Data;
import java.util.List;

/**
 * SmartQaRequest - 智能问答请求 (V3.1 增强).
 * ----------------------------------------------------------------------------
 * 业务: 客户在 Bot 会话中提问, 返 markdown 格式 + 互动按钮.
 */
@Data
public class SmartQaRequest {
    private String question;
    private List<HistoryItem> history;
    private String sessionId;

    @Data
    public static class HistoryItem {
        private String role;
        private String content;
    }
}
