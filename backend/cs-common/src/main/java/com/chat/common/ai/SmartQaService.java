package com.chat.common.ai;

import com.chat.common.ai.dto.SmartQaRequest;
import com.chat.common.ai.dto.SmartQaResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SmartQaService - 智能问答服务 (V3.1 增强).
 * ----------------------------------------------------------------------------
 * 业务场景: 客户/坐席向 AI 提问, 返:
 *   1) Markdown 格式的回复 (含标题/列表/代码块)
 *   2) 互动按钮 [button:type:label:payload]
 *   3) 意图 + 置信度 + 来源 (用于分析)
 *
 * 算法:
 *   1) 调 LocalAiService.chat → 决策结果 (text + intent + source)
 *   2) 解析 text 中的 [button:...] 标记, 提取为 Button[] 元数据
 *   3) 同时也保留 markdown 原文, 前端渲染器处理
 *
 * 与 LocalAiService 关系:
 *   - LocalAiService: 核心决策 (0-1ms)
 *   - SmartQaService: 增强层 (markdown 解析 + 按钮提取)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartQaService {

    /** 按钮标记正则: [button:type:label:payload] 或 [button:type:label] */
    private static final Pattern BUTTON_PATTERN = Pattern.compile(
        "\\[button:([a-z]+)(?::([^\\]:]+))?(?::([^\\]]+))?\\]"
    );

    private final LocalAiService localAi;

    /**
     * 智能问答主入口.
     * @param req 智能问答请求
     * @return SmartQaResponse (含 markdown + 按钮元数据)
     */
    public SmartQaResponse answer(SmartQaRequest req) {
        long t0 = System.currentTimeMillis();

        // 1) 转 LocalAiService.ChatRequest
        List<LocalAiService.ChatMessage> msgs = new ArrayList<>();
        if (req.getHistory() != null) {
            for (SmartQaRequest.HistoryItem h : req.getHistory()) {
                msgs.add(LocalAiService.ChatMessage.builder()
                    .role(h.getRole())
                    .content(h.getContent())
                    .build());
            }
        }
        // 加当前问题
        msgs.add(LocalAiService.ChatMessage.builder()
            .role("user")
            .content(req.getQuestion())
            .build());

        LocalAiService.ChatRequest chatReq = LocalAiService.ChatRequest.builder()
            .messages(msgs)
            .build();

        // 2) 调 LocalAiService
        LocalAiService.ChatResponse resp = localAi.chat(chatReq);
        long latency = System.currentTimeMillis() - t0;

        // 3) 提取 markdown 中的按钮
        List<SmartQaResponse.Button> buttons = extractButtons(resp.getContent());

        // 4) 估算置信度 (基于 source)
        Double confidence = "rule".equals("rule") ? 0.95 :
                            "faq".equals("faq") ? 0.8 : 0.5;

        // 5) 提取 intent (从 content 推断, 因为 LocalAiService.Decision 是内部)
        //    简化: 从 content 头部 ### 提取
        String intent = "unknown";
        if (resp.getContent() != null) {
            if (resp.getContent().startsWith("### ")) {
                intent = "structured";
            } else if (resp.getContent().contains("常见问题")) {
                intent = "faq";
            } else if (resp.getContent().contains("转人工")) {
                intent = "transfer";
            } else if (resp.getContent().contains("谢谢") || resp.getContent().contains("祝您")) {
                intent = "goodbye";
            } else {
                intent = "general";
            }
        }

        return SmartQaResponse.builder()
            .content(resp.getContent())
            .intent(intent)
            .confidence(confidence)
            .source("local-ai")
            .buttons(buttons)
            .tokens(resp.getPromptTokens() + resp.getCompletionTokens())
            .latencyMs(latency)
            .sessionId(req.getSessionId())
            .build();
    }

    /**
     * 提取 markdown 文本中的 [button:...] 按钮.
     * 保留原 text (button 占位符), 按钮元数据单独返, 由前端决定渲染.
     */
    public List<SmartQaResponse.Button> extractButtons(String text) {
        if (text == null) return List.of();
        List<SmartQaResponse.Button> out = new ArrayList<>();
        Matcher m = BUTTON_PATTERN.matcher(text);
        while (m.find()) {
            String type = m.group(1);
            String label = m.group(2) != null ? m.group(2) : "操作";
            String payload = m.group(3) != null ? m.group(3) : "";
            out.add(SmartQaResponse.Button.builder()
                .type(type)
                .label(label)
                .payload(payload)
                .build());
        }
        return out;
    }
}
