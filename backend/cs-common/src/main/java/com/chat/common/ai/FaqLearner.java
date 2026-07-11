package com.chat.common.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * FaqLearner - FAQ 自学习 (V3.1 新增).
 * ----------------------------------------------------------------------------
 * 业务场景: 客户问的问题没有 FAQ 答案时 (score < 0.15), 自动加入候选池.
 * 运营人员可批量审核入库, 提升覆盖率.
 *
 * 池结构: Redis ZSET
 *   - key:   "faq:candidate:pool"
 *   - score: 出现次数 (frequency)
 *   - value: 问题原文
 *
 * 用法:
 *   - 兜底时: learner.record(question)  // +1 计数
 *   - 审核:   learner.topN(100)  // 取 Top 100 候选
 *   - 入库:   faqEngine.add(question, answer)
 *
 * 清理: 定期任务清除 score < 3 的低频候选 (避免污染池)
 *
 * 性能: Redis ZADD + ZINCRBY 均为 O(log N)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaqLearner {

    private static final String POOL_KEY = "faq:candidate:pool";
    private static final long MAX_POOL_SIZE = 5000;  // 候选池上限

    private final StringRedisTemplate redis;

    /**
     * 记录候选问题 (无答案时调用).
     * 每次调用 +1, 自动清理低频.
     */
    public void record(String question) {
        if (question == null || question.isBlank()) return;
        String q = question.trim();
        if (q.length() < 2 || q.length() > 100) return;  // 过滤太短/太长
        try {
            Double cnt = redis.opsForZSet().incrementScore(POOL_KEY, q, 1);
            // 池过大时清理低频
            if (cnt != null && cnt > MAX_POOL_SIZE) {
                cleanup(2);  // 清理 < 2 次的
            }
        } catch (Exception e) {
            log.warn("[faq-learner] record failed: {}", q, e);
        }
    }

    /**
     * 取 Top N 候选 (按频次倒序).
     */
    public Set<Map<String, Object>> topN(int n) {
        try {
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> set =
                redis.opsForZSet().reverseRangeWithScores(POOL_KEY, 0, n - 1);
            if (set == null) return Set.of();
            return set.stream()
                .map(t -> Map.<String, Object>of(
                    "question", t.getValue(),
                    "count", t.getScore() != null ? t.getScore().longValue() : 0L
                ))
                .collect(java.util.stream.Collectors.toSet());
        } catch (Exception e) {
            log.warn("[faq-learner] topN failed", e);
            return Set.of();
        }
    }

    /**
     * 清理低频候选 (< threshold 次的).
     */
    public long cleanup(int threshold) {
        try {
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> low =
                redis.opsForZSet().rangeByScoreWithScores(POOL_KEY, 0, threshold);
            if (low == null || low.isEmpty()) return 0;
            String[] keys = low.stream()
                .map(org.springframework.data.redis.core.ZSetOperations.TypedTuple::getValue)
                .toArray(String[]::new);
            Long removed = redis.opsForZSet().remove(POOL_KEY, (Object[]) keys);
            return removed != null ? removed : 0;
        } catch (Exception e) {
            log.warn("[faq-learner] cleanup failed", e);
            return 0;
        }
    }
}
