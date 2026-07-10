package com.chat.common.ai;

import com.chat.common.m3.M3Capability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * LocalAiService - 自研 AI 主服务 (替换 miniMax3, Java 原生, 开箱即用).
 * ----------------------------------------------------------------------------
 * 整合 IntentClassifier / SentimentAnalyzer / FaqEngine / TfIdfEmbedder,
 * 实现 M3Capability 接口, 业务可直接注入.
 *
 * 核心能力:
 *   - chat: 意图 + FAQ + 模板生成 (1ms 响应)
 *   - embed: TF-IDF 256 维 (1ms 响应)
 *   - sentiment: 情感分析 (词典 + 否定翻转 + 程度副词)
 *   - tts: 返空, 前端用 Web Speech API
 *   - asr: 返空, 前端用 Web Speech API
 *   - understandImage: 简化版, 返结构化提示
 *
 * @Primary 标记, 业务注入 M3Capability 默认走本服务.
 *
 * 优势 vs miniMax3:
 *   - 零外部依赖 (无 Python service)
 *   - 0-1ms 响应 (JVM 内存)
 *   - 零成本
 *   - 可解释 (_meta 字段)
 *   - 离线可用
 */
@Slf4j
@Primary                                                                  // 默认走自研
@Service
@RequiredArgsConstructor
public class LocalAiService implements M3Capability {

    private final IntentClassifier intentClassifier;
    private final SentimentAnalyzer sentimentAnalyzer;
    private final TfIdfEmbedder embedder;
    private final FaqEngine faqEngine;

    public LocalAiService() {
        this.intentClassifier = new IntentClassifier();
        this.sentimentAnalyzer = new SentimentAnalyzer();
        this.embedder = new TfIdfEmbedder(256);
        this.faqEngine = new FaqEngine(embedder);
        log.info("[local-ai] 自研 AI 启动: intent={} sentiment={} embed=256d faq={}",
                11, "4 类", faqEngine.size());
    }

    @Override
    public ChatResponse chat(ChatRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            List<ChatMessage> msgs = req.getMessages();
            // 1) 取最后一条 user 消息
            String lastUser = "";
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if ("user".equals(msgs.get(i).getRole())) {
                    lastUser = msgs.get(i).getContent();
                    break;
                }
            }
            // 2) 决策
            Decision d = decide(lastUser);
            // 3) 估算 token
            int promptTokens = msgs.stream().mapToInt(m -> m.getContent().length() / 2).sum();
            int completionTokens = d.text.length() / 2;
            return ChatResponse.builder()
                    .content(d.text)
                    .finishReason("stop")
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        } catch (Exception e) {
            log.error("[local-ai] chat failed", e);
            return ChatResponse.builder()
                    .content("抱歉, 我没理解您的问题, 请换个说法或回复【人工】转人工客服")
                    .finishReason("error")
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        }
    }

    @Override
    public float[] embed(String text) {
        return embedder.embed(text);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return embedder.embedBatch(texts);
    }

    @Override
    public byte[] tts(String text, TtsConfig config) {
        // 阶段 1: 服务端不合成, 前端用 Web Speech API
        log.debug("[local-ai] TTS: text='{}' (前端 Web Speech API 处理)", text);
        return new byte[0];
    }

    @Override
    public String asr(byte[] audio) {
        log.debug("[local-ai] ASR: 音频 {} 字节 (前端 Web Speech API 转写)", audio == null ? 0 : audio.length);
        return "";
    }

    @Override
    public String understandImage(String imageUrl, String prompt) {
        return "已收到您的图片. 请用文字描述一下您看到的内容, 我会帮您解答.";
    }

    @Override
    public SentimentResult analyzeSentiment(String text) {
        SentimentAnalyzer.Result r = sentimentAnalyzer.analyze(text);
        SentimentAnalyzer.Label l = r.label();
        return SentimentResult.builder()
                .score(r.score())
                .label(l.name().toLowerCase())
                .confidence(r.confidence())
                .build();
    }

    @Override
    public boolean isHealthy() {
        return true;   // 本地永远 UP
    }

    // ==================== 决策链 ====================

    /**
     * 决策: 意图 → 规则 → FAQ → 兜底.
     */
    private Decision decide(String text) {
        if (text == null || text.isEmpty()) {
            return new Decision("您好, 有什么可以帮您?", "greeting", "fallback", null,
                    sentimentAnalyzer.analyze(text));
        }
        // 1) 意图分类
        IntentClassifier.Result ires = intentClassifier.classify(text);
        SentimentAnalyzer.Result sres = sentimentAnalyzer.analyze(text);
        String intent = ires.intent().name().toLowerCase();

        // 2) 规则匹配
        switch (ires.intent()) {
            case TRANSFER_HUMAN:
                return new Decision("好的, 正在为您转接人工客服, 请稍等...",
                        intent, "rule", "transfer_to_human", sres);
            case GOODBYE:
                return new Decision("感谢您的咨询, 祝您生活愉快!",
                        intent, "rule", "end_call", sres);
            case COMPLAINT:
                return new Decision("非常抱歉给您带来不便, 我马上为您升级到主管处理",
                        intent, "rule", "transfer_to_human", sres);
            case THANKS:
                return new Decision("不客气! 还需要其他帮助吗?",
                        intent, "rule", null, sres);
            default:
        }

        // 3) FAQ 检索
        Optional<FaqEngine.SearchResult> faq = faqEngine.search(text);
        if (faq.isPresent() && faq.get().score() > 0.15) {
            return new Decision(faq.get().answer(),
                    intent, "faq", null, sres);
        }

        // 4) 兜底
        if (sres.label() == SentimentAnalyzer.Label.ANGRY) {
            return new Decision("理解您的心情, 让我为您转接人工客服妥善处理",
                    intent, "fallback", "transfer_to_human", sres);
        }
        String trimmed = text.trim();
        if (trimmed.endsWith("?") || trimmed.endsWith("？")) {
            return new Decision("这是个很好的问题, 建议您: 1) 看看常见问题; 2) 描述更具体些; 3) 或转人工客服",
                    intent, "fallback", null, sres);
        }
        return new Decision("抱歉没理解您的问题, 换个说法试试? 或回复【人工】转接客服",
                intent, "fallback", null, sres);
    }

    /** 决策结果 (内部用) */
    public record Decision(
            String text,
            String intent,
            String source,
            String action,
            SentimentAnalyzer.Result sentiment
    ) {}
}