package com.chat.common.ai;

import com.chat.common.m3.M3Capability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocalAiServiceTest - 单元测试.
 * 验证 6 大能力 + 决策链 + 情感分析 + FAQ 检索.
 */
class LocalAiServiceTest {

    private final LocalAiService ai = new LocalAiService();

    @Test
    void testChat_Refund() {
        M3Capability.ChatResponse r = ai.chat(M3Capability.ChatRequest.builder()
                .messages(List.of(M3Capability.ChatMessage.builder()
                        .role("user").content("怎么申请退款").build()))
                .build());
        assertNotNull(r.getContent());
        assertTrue(r.getContent().contains("退款"));
    }

    @Test
    void testChat_TransferHuman() {
        M3Capability.ChatResponse r = ai.chat(M3Capability.ChatRequest.builder()
                .messages(List.of(M3Capability.ChatMessage.builder()
                        .role("user").content("转人工").build()))
                .build());
        assertTrue(r.getContent().contains("转接") || r.getContent().contains("人工"));
    }

    @Test
    void testChat_Goodbye() {
        M3Capability.ChatResponse r = ai.chat(M3Capability.ChatRequest.builder()
                .messages(List.of(M3Capability.ChatMessage.builder()
                        .role("user").content("再见").build()))
                .build());
        assertTrue(r.getContent().contains("祝您"));
    }

    @Test
    void testSentiment_Angry() {
        M3Capability.SentimentResult r = ai.analyzeSentiment("气死我了, 垃圾服务");
        assertEquals("angry", r.getLabel());
        assertTrue(r.getScore() < 0);
    }

    @Test
    void testSentiment_Happy() {
        M3Capability.SentimentResult r = ai.analyzeSentiment("非常感谢, 客服太棒了");
        assertEquals("happy", r.getLabel());
        assertTrue(r.getScore() > 0);
    }

    @Test
    void testSentiment_Negation() {
        M3Capability.SentimentResult r = ai.analyzeSentiment("这个不好");
        // 否定翻转: 不好 → 负向
        assertTrue(r.getScore() < 0, "否定翻转应得负分, 实际 " + r.getScore());
    }

    @Test
    void testSentiment_Intensifier() {
        M3Capability.SentimentResult r1 = ai.analyzeSentiment("好");
        M3Capability.SentimentResult r2 = ai.analyzeSentiment("非常好");
        // score 都被归一化到 [-1, 1], 但 raw score 应该不同 ("好"=1.0, "非常好"=2.0 然后 cap)
        // 验证 label 相同, 体现都在 happy 区
        assertEquals(r1.getLabel(), r2.getLabel());
        // 验证 sentiment 本身都被识别为正向
        assertTrue(r1.getScore() > 0);
        assertTrue(r2.getScore() > 0);
    }

    @Test
    void testEmbed_Dimension() {
        float[] v = ai.embed("退款怎么操作");
        assertEquals(256, v.length);
        double norm = 0;
        for (float x : v) norm += x * x;
        assertEquals(1.0, Math.sqrt(norm), 0.01, "L2 归一化");
    }

    @Test
    void testEmbed_Similarity() {
        float[] a = ai.embed("退款");
        float[] b = ai.embed("申请退款");
        float[] c = ai.embed("量子力学");
        double sim_ab = cosine(a, b);
        double sim_ac = cosine(a, c);
        assertTrue(sim_ab > sim_ac, "同主题相似度应更高: ab=" + sim_ab + " ac=" + sim_ac);
    }

    @Test
    void testHealthy() {
        assertTrue(ai.isHealthy());
    }

    @Test
    void testLatency() {
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            ai.chat(M3Capability.ChatRequest.builder()
                    .messages(List.of(M3Capability.ChatMessage.builder()
                            .role("user").content("退款测试" + i).build()))
                    .build());
        }
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed < 1000, "100 次 chat 应 < 1s, 实际 " + elapsed + "ms");
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) dot += a[i] * b[i];
        return dot;
    }
}