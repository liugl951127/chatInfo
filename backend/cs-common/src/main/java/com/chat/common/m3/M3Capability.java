package com.chat.common.m3;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * M3Capability - MiniMax-M3 能力统一接口.
 * ----------------------------------------------------------------------------
 * 适配 M3 的所有 AI 能力, 供 6 个新模块 (cs-prediction/cs-cdp/cs-customer-success/
 * cs-community/cs-video/cs-voice) 统一调用.
 *
 * 部署形态:
 *   - M3 是 Python 调的工具, 通过 m3-adapter Python service 暴露 HTTP API (FastAPI, 端口 8084)
 *   - Java 端通过 WebClient 调 m3-adapter
 *
 * 阶段 1 实现的 6 个核心能力:
 *   1. chat: 多轮对话 (主对话引擎)
 *   2. embed: 文本向量化 (RAG 检索基础)
 *   3. tts: 文字转语音 (智能电话/通知)
 *   4. asr: 语音转文字 (语音消息理解)
 *   5. understandImage: 图片理解 (客户截图报错识别)
 *   6. analyzeSentiment: 情感分析 (情绪打分)
 *
 * 阶段 2-4 扩展:
 *   - tools/function calling
 *   - 实时流式 TTS (智能电话)
 *   - 长期记忆管理
 *   - 多模态视频理解
 */
public interface M3Capability {

    /**
     * 多轮对话.
     * @param req 包含 messages/tools/system 等
     * @return 模型回复 (text)
     */
    ChatResponse chat(ChatRequest req);

    /**
     * 文本向量化.
     * @param text 单条文本
     * @return 向量 (默认 1024 维, M3 标准)
     */
    float[] embed(String text);

    /**
     * 批量向量化.
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 文字转语音.
     * @param text 要合成的文字
     * @param config 语音配置 (音色/语速/音量)
     * @return 音频字节 (mp3/wav)
     */
    byte[] tts(String text, TtsConfig config);

    /**
     * 语音转文字.
     * @param audio 音频字节 (mp3/wav/m4a)
     * @return 转写文字
     */
    String asr(byte[] audio);

    /**
     * 图片理解.
     * @param imageUrl 图片 URL (或 base64)
     * @param prompt 提示词 ("这张图显示了哪些错误信息?")
     * @return 模型理解结果
     */
    String understandImage(String imageUrl, String prompt);

    /**
     * 情感分析.
     * @param text 客户消息
     * @return 情感结果 (-1 怒怼 ~ +1 开心)
     */
    SentimentResult analyzeSentiment(String text);

    /**
     * 健康检查 (m3-adapter 是否在线).
     */
    boolean isHealthy();

    // ===== DTO =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ChatMessage {
        private String role;     // system / user / assistant / tool
        private String content;
        private String name;     // tool name (可选)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class Tool {
        private String name;
        private String description;
        private Map<String, Object> parameters;  // JSON Schema
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ChatRequest {
        private List<ChatMessage> messages;
        private List<Tool> tools;
        private String model;            // 默认 minimax3
        private Double temperature;
        private Integer maxTokens;
        private String user;              // 用户标识 (审计/限速)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ChatResponse {
        private String content;
        private String finishReason;     // stop / tool_calls / length
        private List<ToolCall> toolCalls;
        private Integer promptTokens;
        private Integer completionTokens;
        private long latencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ToolCall {
        private String id;
        private String name;
        private Map<String, Object> arguments;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class TtsConfig {
        private String voiceId;          // male-qn-qingse 等
        private Double speed;            // 0.5-2.0
        private Double volume;           // 0.0-10.0
        private Integer pitch;           // -12 to 12
        private String emotion;          // happy / sad / neutral
        private String format;           // mp3 / wav / pcm
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class SentimentResult {
        private double score;            // -1.0 ~ +1.0
        private String label;            // angry / sad / neutral / happy
        private double confidence;       // 0.0 ~ 1.0
    }
}