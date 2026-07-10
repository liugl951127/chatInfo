package com.chat.success.service;

import com.chat.success.entity.HealthScoreHistory;
import com.chat.success.mapper.HealthScoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * HealthScoreService - 客户健康分计算.
 * ----------------------------------------------------------------------------
 * 健康分公式 (阶段 1 简化版):
 *   score = 0.3 * login_freq_score    // 30% 登录频率
 *         + 0.3 * feature_usage_score  // 30% 功能使用 (这里用活跃天数简化)
 *         + 0.2 * support_score        // 20% 工单数 (越少越高, 阶段 1 用固定 100)
 *         + 0.2 * csat_score           // 20% 满意度
 *
 * Tier (按分数):
 *   >= 80: CHAMPION
 *   >= 60: HEALTHY
 *   >= 40: AT_RISK
 *   <  40: CHURNED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthScoreService {

    private final HealthScoreMapper mapper;
    private final ObjectMapper jackson;

    public record HealthResult(Long userId, int score, String tier, Map<String, Integer> components) {}

    /**
     * 计算并保存健康分.
     */
    @Transactional
    public HealthResult computeAndSave(Long userId, int loginScore, int usageScore, int csatScore) {
        // 阶段 1: 固定 supportScore=80 (无工单数据, 默认无问题)
        int supportScore = 80;
        int total = (int) Math.round(
            loginScore * 0.3 + usageScore * 0.3 + supportScore * 0.2 + csatScore * 0.2);

        String tier;
        if (total >= 80) tier = "CHAMPION";
        else if (total >= 60) tier = "HEALTHY";
        else if (total >= 40) tier = "AT_RISK";
        else tier = "CHURNED";

        Map<String, Integer> components = new LinkedHashMap<>();
        components.put("login", loginScore);
        components.put("usage", usageScore);
        components.put("support", supportScore);
        components.put("csat", csatScore);

        HealthScoreHistory h = new HealthScoreHistory();
        h.setUserId(userId);
        h.setScore(total);
        h.setTier(tier);
        try {
            h.setComponents(jackson.writeValueAsString(components));
        } catch (Exception e) { /* ignore */ }
        h.setCreatedAt(LocalDateTime.now());
        mapper.insert(h);

        return new HealthResult(userId, total, tier, components);
    }

    public HealthScoreHistory findLatest(Long uid) {
        return mapper.findLatest(uid);
    }

    public List<HealthScoreHistory> findHistory(Long uid) {
        return mapper.findHistory(uid);
    }

    /**
     * 从 cdp_event 简单推导分数 (阶段 1 用 0 事件, 后续接 cdp).
     */
    public HealthResult computeFromEvents(Long userId, long activeDays, double avgCsat) {
        int loginScore = Math.min(100, (int) (activeDays * 10));
        int usageScore = Math.min(100, (int) (activeDays * 15));
        int csatScore = (int) Math.max(0, Math.min(100, avgCsat * 20));
        return computeAndSave(userId, loginScore, usageScore, csatScore);
    }
}