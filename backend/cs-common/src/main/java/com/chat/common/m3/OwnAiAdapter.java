package com.chat.common.m3;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * OwnAiAdapter - 自研 AI 适配器 (替换 miniMax3).
 * ----------------------------------------------------------------------------
 * 调 own-ai-adapter Python service (FastAPI, 端口 8085).
 * 阶段 1 完全脱离 miniMax3 / 任何外部 LLM API.
 *
 * @Primary 标记, 业务注入 M3Capability 默认走自研 AI.
 *   @Autowired
 *   private M3Capability ai;  // 拿到 OwnAiAdapter
 *
 * 如需回退 miniMax3: 删 @Primary 或在业务类用 @Qualifier(\"httpM3Adapter\").
 *
 * 自研 AI 特性:
 *   - 零外部依赖 (纯本地 Python)
 *   - 可解释: 每个回复带 _meta (intent/source/action/sentiment)
 *   - 1ms 响应 (本地)
 *   - 离线可用
 */
@Slf4j
@Primary                                                                // 优先于 HttpM3Adapter
@Component("ownAiAdapter")
@RequiredArgsConstructor
public class OwnAiAdapter implements M3Capability {

    private final ObjectMapper mapper;

    @Value("${chat.own-ai.adapter-url:http://localhost:8085}")
    private String baseUrl;

    private WebClient client() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Object> body = new HashMap<>();
            List<Map<String, Object>> msgs = new ArrayList<>();
            for (ChatMessage m : req.getMessages()) {
                msgs.add(Map.of("role", m.getRole(), "content", m.getContent()));
            }
            body.put("messages", msgs);
            if (req.getTemperature() != null) body.put("temperature", req.getTemperature());
            if (req.getMaxTokens() != null) body.put("max_tokens", req.getMaxTokens());
            if (req.getUser() != null) body.put("user", req.getUser());

            Map<String, Object> resp = client().post()
                    .uri("/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .onErrorResume(e -> {
                        log.error("[own-ai] chat failed: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (resp == null) {
                return ChatResponse.builder()
                        .content("[own-ai offline] 服务暂不可用")
                        .finishReason("error")
                        .latencyMs(System.currentTimeMillis() - t0)
                        .build();
            }
            return ChatResponse.builder()
                    .content((String) resp.get("content"))
                    .finishReason((String) resp.get("finish_reason"))
                    .promptTokens(asInt(resp.get("prompt_tokens")))
                    .completionTokens(asInt(resp.get("completion_tokens")))
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        } catch (Exception e) {
            log.error("[own-ai] chat exception: {}", e.getMessage());
            return ChatResponse.builder()
                    .content("[own-ai exception] " + e.getMessage())
                    .finishReason("error")
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        }
    }

    @Override
    public float[] embed(String text) {
        try {
            Map<String, Object> body = Map.of("text", text == null ? "" : text);
            Map<String, Object> resp = client().post()
                    .uri("/embed")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            if (resp == null) return new float[0];
            @SuppressWarnings("unchecked")
            List<Number> vec = (List<Number>) resp.get("vector");
            float[] arr = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i).floatValue();
            return arr;
        } catch (Exception e) {
            log.error("[own-ai] embed failed: {}", e.getMessage());
            return new float[0];
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (String t : texts) out.add(embed(t));
        return out;
    }

    @Override
    public byte[] tts(String text, TtsConfig config) {
        // 阶段 1: 服务端不合成, 返空字节, 前端用 Web Speech API
        log.debug("[own-ai] TTS: 客户端 Web Speech API 合成, text='{}'", text);
        return new byte[0];
    }

    @Override
    public String asr(byte[] audio) {
        // 阶段 1: 服务端不做 ASR, 返空, 前端 Web Speech API 转写后送 chat
        log.debug("[own-ai] ASR: 客户端 Web Speech API 转写");
        return "";
    }

    @Override
    public String understandImage(String imageUrl, String prompt) {
        try {
            Map<String, Object> body = Map.of(
                "image_url", imageUrl == null ? "" : imageUrl,
                "prompt", prompt == null ? "描述这张图片" : prompt);
            Map<String, Object> resp = client().post()
                    .uri("/understand-image")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return resp == null ? "" : (String) resp.get("text");
        } catch (Exception e) {
            log.error("[own-ai] understandImage failed: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public SentimentResult analyzeSentiment(String text) {
        try {
            Map<String, Object> body = Map.of("text", text == null ? "" : text);
            Map<String, Object> resp = client().post()
                    .uri("/sentiment")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            if (resp == null) {
                return SentimentResult.builder().score(0).label("neutral").confidence(0).build();
            }
            return SentimentResult.builder()
                    .score(((Number) resp.get("score")).doubleValue())
                    .label((String) resp.get("label"))
                    .confidence(((Number) resp.get("confidence")).doubleValue())
                    .build();
        } catch (Exception e) {
            log.error("[own-ai] sentiment failed: {}", e.getMessage());
            return SentimentResult.builder().score(0).label("neutral").confidence(0).build();
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            String resp = client().get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            return resp != null && resp.contains("UP");
        } catch (Exception e) {
            return false;
        }
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return null; }
    }
}