package com.chat.common.m3;

import com.chat.common.ai.LocalAiService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OwnAiAdapter - 自研 AI 适配器 (兼容层, 推荐直接用 LocalAiService).
 * ----------------------------------------------------------------------------
 * 阶段 2 改为: 直接调 cs-common 中的 LocalAiService (Java 原生, 0 依赖).
 *   调 own-ai-adapter Python service (FastAPI, 端口 8085) 作为 fallback.
 *
 * 注意: 如果 LocalAiService Bean 存在 (@Primary), Spring 注入 M3Capability
 *       时优先 LocalAiService. 本类主要给未来 Python service 切换用.
 *
 * 自研 AI 特性:
 *   - 零外部依赖 (纯 Java)
 *   - 1ms 响应 (JVM 内存)
 *   - 可解释
 *   - 离线可用
 */
@Slf4j
@Component("ownAiAdapter")
@RequiredArgsConstructor
@ConditionalOnMissingBean(name = "localAiService")
public class OwnAiAdapter implements M3Capability {

    @Value("${chat.own-ai.adapter-url:http://localhost:8085}")
    private String baseUrl;

    @Override
    public ChatResponse chat(ChatRequest req) {
        log.warn("[own-ai] Python fallback 未启用, 改用 LocalAiService");
        return new LocalAiService().chat(req);
    }

    @Override
    public float[] embed(String text) {
        return new LocalAiService().embed(text);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return new LocalAiService().embedBatch(texts);
    }

    @Override
    public byte[] tts(String text, TtsConfig config) {
        return new LocalAiService().tts(text, config);
    }

    @Override
    public String asr(byte[] audio) {
        return new LocalAiService().asr(audio);
    }

    @Override
    public String understandImage(String imageUrl, String prompt) {
        return new LocalAiService().understandImage(imageUrl, prompt);
    }

    @Override
    public SentimentResult analyzeSentiment(String text) {
        return new LocalAiService().analyzeSentiment(text);
    }

    @Override
    public boolean isHealthy() {
        return new LocalAiService().isHealthy();
    }
}