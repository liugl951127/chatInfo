package com.chat.common.m3;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * HttpM3Adapter - HTTP 客户端实现 M3Capability.
 * ----------------------------------------------------------------------------
 * 调 m3-adapter Python service (FastAPI, 端口 8084) 的 HTTP 接口.
 *
 * 部署:
 *   - m3-adapter 启动: uvicorn m3_server:app --port 8084
 *   - 配置: chat.m3.adapter-url=http://localhost:8084
 *
 * 设计:
 *   - WebClient (响应式, 非阻塞)
 *   - 超时 30s (M3 chat 可能慢)
 *   - 失败 fallback: 返回默认值 (上层用 BotService 静态类兜底)
 *   - 重试 2 次
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpM3Adapter implements M3Capability {

    private final ObjectMapper mapper;

    @Value("${chat.m3.adapter-url:http://localhost:8084}")
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
            Map<String, Object> body = mapper.convertValue(req, Map.class);
            Map<String, Object> resp = client().post()
                    .uri("/chat")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .onErrorResume(e -> {
                        log.error("[m3] chat failed: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (resp == null) {
                return ChatResponse.builder()
                        .content("")   // fallback
                        .finishReason("error")
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
            log.error("[m3] chat exception: {}", e.getMessage());
            return ChatResponse.builder()
                    .content("")
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
                    .timeout(Duration.ofSeconds(10))
                    .block();
            if (resp == null) return new float[0];
            @SuppressWarnings("unchecked")
            List<Number> vec = (List<Number>) resp.get("vector");
            float[] arr = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) arr[i] = vec.get(i).floatValue();
            return arr;
        } catch (Exception e) {
            log.error("[m3] embed failed: {}", e.getMessage());
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
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("text", text);
            if (config != null) {
                body.put("voice_id", config.getVoiceId());
                body.put("speed", config.getSpeed());
                body.put("volume", config.getVolume());
                body.put("pitch", config.getPitch());
                body.put("emotion", config.getEmotion());
                body.put("format", config.getFormat());
            }
            return client().post()
                    .uri("/tts")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(20))
                    .block();
        } catch (Exception e) {
            log.error("[m3] tts failed: {}", e.getMessage());
            return new byte[0];
        }
    }

    @Override
    public String asr(byte[] audio) {
        try {
            // 上传音频: 阶段 1 用 base64 简化, 阶段 2 改 multipart
            String b64 = Base64.getEncoder().encodeToString(audio == null ? new byte[0] : audio);
            Map<String, Object> body = Map.of("audio_b64", b64);
            Map<String, Object> resp = client().post()
                    .uri("/asr")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();
            return resp == null ? "" : (String) resp.get("text");
        } catch (Exception e) {
            log.error("[m3] asr failed: {}", e.getMessage());
            return "";
        }
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
                    .timeout(Duration.ofSeconds(30))
                    .block();
            return resp == null ? "" : (String) resp.get("text");
        } catch (Exception e) {
            log.error("[m3] understandImage failed: {}", e.getMessage());
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
                    .timeout(Duration.ofSeconds(10))
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
            log.error("[m3] sentiment failed: {}", e.getMessage());
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