package com.chat.ai.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chat.common.ai.FaqEngine;
import com.chat.common.ai.IntentClassifier;
import com.chat.common.ai.LocalAiService;
import com.chat.common.ai.SentimentAnalyzer;
import com.chat.common.ai.TfIdfEmbedder;
import com.chat.common.m3.M3Capability;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AiController - Java 自研 AI 服务对外 REST 接口.
 *
 * 复用 cs-common/ai 下的本地推理组件:
 *   - LocalAiService  → 端到端 chat 决策
 *   - FaqEngine       → FAQ 知识库检索
 *   - IntentClassifier → 意图分类
 *   - SentimentAnalyzer → 情感分析
 *   - TfIdfEmbedder   → 文本向量化
 *
 * 独立运行时使用 H2 + Mock 数据, 不依赖 mariadb/redis.
 */
@Tag(name = "AI 服务 - 自研本地推理")
@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Autowired(required = false) private LocalAiService localAiService;
    @Autowired(required = false) private FaqEngine faqEngine;
    @Autowired(required = false) private IntentClassifier intentClassifier;
    @Autowired(required = false) private SentimentAnalyzer sentimentAnalyzer;
    @Autowired(required = false) private TfIdfEmbedder tfIdfEmbedder;

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> r = new HashMap<>();
        r.put("service", "cs-ai");
        r.put("status", "UP");
        r.put("ts", LocalDateTime.now().toString());
        r.put("components", List.of(
            "LocalAiService", "FaqEngine", "IntentClassifier",
            "SentimentAnalyzer", "TfIdfEmbedder"
        ));
        r.put("faqSize", faqEngine != null ? faqEngine.size() : 0);
        return r;
    }

    @Operation(summary = "本地 AI chat (无外部依赖)")
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        String prompt = (String) body.getOrDefault("prompt", "");
        String reply;
        if (localAiService != null) {
            M3Capability.ChatRequest req = M3Capability.ChatRequest.builder()
                .messages(List.of(
                    M3Capability.ChatMessage.builder()
                        .role("user")
                        .content(prompt)
                        .build()))
                .build();
            M3Capability.ChatResponse resp = localAiService.chat(req);
            reply = resp.getContent() != null ? resp.getContent() : "";
        } else {
            reply = "[mock-ai] echo: " + prompt;
        }
        return Map.of("prompt", prompt, "reply", reply, "ts", LocalDateTime.now().toString());
    }

    @Operation(summary = "FAQ 检索")
    @GetMapping("/faq")
    public Map<String, Object> faq(@RequestParam String q) {
        Map<String, Object> r = new HashMap<>();
        r.put("q", q);
        if (faqEngine != null) {
            Optional<FaqEngine.SearchResult> hit = faqEngine.search(q);
            r.put("hit", hit.isPresent());
            r.put("answer", hit.map(FaqEngine.SearchResult::answer).orElse(""));
            r.put("intent", hit.map(FaqEngine.SearchResult::intent).orElse(""));
            r.put("score", hit.map(FaqEngine.SearchResult::score).orElse(0.0));
        } else {
            r.put("hit", false);
            r.put("answer", "[mock-faq] no entry for: " + q);
        }
        return r;
    }

    @Operation(summary = "意图识别")
    @PostMapping("/intent")
    public Map<String, Object> intent(@RequestBody Map<String, Object> body) {
        String text = (String) body.getOrDefault("text", "");
        if (intentClassifier != null) {
            IntentClassifier.Result r = intentClassifier.classify(text);
            return Map.of("text", text, "intent", r.intent().name(), "confidence", r.confidence());
        }
        return Map.of("text", text, "intent", "UNKNOWN", "confidence", 0.0);
    }

    @Operation(summary = "情感分析")
    @PostMapping("/sentiment")
    public Map<String, Object> sentiment(@RequestBody Map<String, Object> body) {
        String text = (String) body.getOrDefault("text", "");
        if (sentimentAnalyzer != null) {
            SentimentAnalyzer.Result r = sentimentAnalyzer.analyze(text);
            return Map.of("text", text, "score", r.score(),
                          "label", r.label().name(), "confidence", r.confidence());
        }
        return Map.of("text", text, "score", 0.0, "label", "NEUTRAL", "confidence", 0.0);
    }

    @Operation(summary = "TF-IDF 文本向量化 (256 维)")
    @PostMapping("/embed")
    public Map<String, Object> embed(@RequestBody Map<String, Object> body) {
        String text = (String) body.getOrDefault("text", "");
        if (tfIdfEmbedder != null) {
            float[] vec = tfIdfEmbedder.embed(text);
            return Map.of("text", text, "dim", vec.length,
                          "vec", vec, "preview", vec.length > 0 ? vec[0] : 0.0f);
        }
        return Map.of("text", text, "dim", 0);
    }
}
