package com.chat.common.ai;

import com.chat.common.ai.dto.SmartQaRequest;
import com.chat.common.ai.dto.SmartQaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * SmartQaController - 智能问答 REST 端点 (V3.1 增强).
 * ----------------------------------------------------------------------------
 * 端点: POST /api/ai/smart-qa
 * 业务: Bot 会话中, 客户问问题 → 返 markdown + 互动按钮.
 *
 * 客户端调用:
 *   - 客户:  Customer.vue 发送问题
 *   - 坐席:  Agent.vue 智能回复建议
 *   - 内部:  AI 决策服务
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class SmartQaController {

    private final SmartQaService smartQa;

    /**
     * 智能问答 (Markdown + 互动按钮).
     * @see SmartQaService
     */
    @PostMapping("/smart-qa")
    public SmartQaResponse smartQa(@RequestBody SmartQaRequest req) {
        return smartQa.answer(req);
    }

    /**
     * 仅问 AI (简单版, 不含历史).
     */
    @GetMapping("/ask")
    public SmartQaResponse ask(@RequestParam String q) {
        SmartQaRequest req = new SmartQaRequest();
        req.setQuestion(q);
        return smartQa.answer(req);
    }
}
