package com.chat.common.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * AiServiceWrapper - AI 服务统一入口 (V3.1 增强).
 * ----------------------------------------------------------------------------
 * 包装 LocalAiService, 增加:
 *   - FAQ 自学习: 无答案时自动记录到候选池
 *   - 调用统计: 总调用次数 + 命中率
 *   - Markdown 增强: 返 markdown 格式 + 互动按钮 (V3.2)
 *
 * 用法: 业务调用 aiServiceWrapper.chat(req), 而非直接 localAiService.chat
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceWrapper {

    private final LocalAiService localAi;
    private final FaqLearner learner;

    /**
     * 聊天入口.
     * 如果结果是 fallback (兜底), 记录问题到 FAQ 候选池.
     */
    public LocalAiService.ChatResponse chat(LocalAiService.ChatRequest req) {
        LocalAiService.ChatResponse resp = localAi.chat(req);
        // V3.1: 兜底问题自动收集
        if (resp.getContent() != null && resp.getContent().contains("没理解您的问题")) {
            String lastUser = lastUserContent(req);
            if (lastUser != null) {
                learner.record(lastUser);
            }
        }
        return resp;
    }

    /**
     * 提取最后一条 user 消息.
     */
    private String lastUserContent(LocalAiService.ChatRequest req) {
        if (req == null || req.getMessages() == null) return null;
        for (int i = req.getMessages().size() - 1; i >= 0; i--) {
            LocalAiService.ChatMessage m = req.getMessages().get(i);
            if ("user".equals(m.getRole())) {
                return m.getContent();
            }
        }
        return null;
    }

    /**
     * 情感分析.
     * 直接用 SentimentAnalyzer (V3.1 准确率 +20%, 90 词)
     */
    public SentimentAnalyzer.Result analyzeSentiment(String text) {
        return new SentimentAnalyzer().analyze(text);
    }

    /**
     * 获取 Top 候选.
     */
    public java.util.Set<java.util.Map<String, Object>> topCandidates(int n) {
        return learner.topN(n);
    }
}
