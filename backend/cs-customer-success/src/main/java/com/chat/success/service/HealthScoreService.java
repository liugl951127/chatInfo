package com.chat.success.service;

import com.chat.success.entity.HealthScoreHistory;                            // 健康分历史实体
import com.chat.success.mapper.HealthScoreMapper;                             // 健康分历史 DAO
import com.fasterxml.jackson.databind.ObjectMapper;                            // JSON 序列化
import lombok.RequiredArgsConstructor;                                        // final 注入
import lombok.extern.slf4j.Slf4j;                                              // 日志
import org.springframework.stereotype.Service;                                 // Spring Bean
import org.springframework.transaction.annotation.Transactional;                // 事务

import java.time.LocalDateTime;                                                 // 时间戳
import java.util.*;                                                            // Map/List 等

/**
 * HealthScoreService - 客户健康分计算 (客户成功核心指标).
 * ----------------------------------------------------------------------------
 * 健康分公式 (阶段 1 简化版):
 *   score = 0.3 * login_freq_score    // 30% 登录频率
 *         + 0.3 * feature_usage_score  // 30% 功能使用 (这里用活跃天数简化)
 *         + 0.2 * support_score        // 20% 工单数 (越少越高, 阶段 1 用固定 100)
 *         + 0.2 * csat_score           // 20% 满意度
 *
 * 权重说明:
 *   - login (30%): 登录频率分, 越高表示客户越活跃
 *   - usage (30%): 功能使用分, 阶段 1 用活跃天数近似
 *   - support (20%): 工单健康分, 阶段 1 固定 80 (后续接工单数)
 *   - csat (20%): 满意度分, 来自客户评分
 *
 * Tier (按总分):
 *   >= 80: CHAMPION   冠军客户 (高活跃 + 高满意)
 *   >= 60: HEALTHY    健康 (正常)
 *   >= 40: AT_RISK    风险 (需关注)
 *   <  40: CHURNED    已流失 (需挽回)
 *
 * @author V3 Team
 * @since 2025-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthScoreService {

    /** 健康分历史 DAO */
    private final HealthScoreMapper mapper;
    /** JSON 序列化 (用于存 components 快照) */
    private final ObjectMapper jackson;

    /**
     * 健康分结果记录 (对外返回).
     *
     * @param userId     客户 ID
     * @param score      总分 0-100
     * @param tier       等级 (CHAMPION/HEALTHY/AT_RISK/CHURNED)
     * @param components 4 维分量 (login/usage/support/csat 各 0-100)
     */
    public record HealthResult(Long userId, int score, String tier, Map<String, Integer> components) {}

    /**
     * 计算并保存健康分 (核心方法, 给定 4 个分量).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 固定 supportScore=80 (阶段 1 无工单数据)
     *   step 2: 加权求和: total = round(login*0.3 + usage*0.3 + support*0.2 + csat*0.2)
     *   step 3: 分级: >=80 CHAMPION / >=60 HEALTHY / >=40 AT_RISK / <40 CHURNED
     *   step 4: 写 health_score_history (含 components JSON 快照)
     *
     * @param userId     客户 ID. 业务含义: 计算目标
     *                   取值范围: Long > 0
     *                   影响: 写入 history 表
     * @param loginScore 登录频率分. 业务含义: 客户活跃度
     *                   取值范围: 0-100
     *                   影响: 总分 30% 权重
     * @param usageScore 功能使用分. 业务含义: 深度使用程度
     *                   取值范围: 0-100
     *                   影响: 总分 30% 权重
     * @param csatScore  满意度分. 业务含义: 客户对服务的评价
     *                   取值范围: 0-100 (由 1-5 星 × 20 转换)
     *                   影响: 总分 20% 权重
     * @return HealthResult (userId, score, tier, components)
     *         - score: 0-100 的最终分数
     *         - tier: CHAMPION/HEALTHY/AT_RISK/CHURNED
     *         - components: 4 维分量快照
     */
    @Transactional
    public HealthResult computeAndSave(Long userId, int loginScore, int usageScore, int csatScore) {
        // step 1: 固定 supportScore (阶段 1 简化)
        int supportScore = 80;                                                 // 默认无工单

        // step 2: 加权求和 (保留精度后四舍五入)
        int total = (int) Math.round(
            loginScore * 0.3 + usageScore * 0.3 + supportScore * 0.2 + csatScore * 0.2);
        // 防御: clamp 到 [0, 100]
        total = Math.max(0, Math.min(100, total));

        // step 3: 分级 (按总分)
        String tier;
        if (total >= 80) tier = "CHAMPION";                                  // 冠军
        else if (total >= 60) tier = "HEALTHY";                               // 健康
        else if (total >= 40) tier = "AT_RISK";                               // 风险
        else tier = "CHURNED";                                                // 已流失

        // step 4: 4 维分量快照 (LinkedHashMap 保持顺序)
        Map<String, Integer> components = new LinkedHashMap<>();
        components.put("login", loginScore);
        components.put("usage", usageScore);
        components.put("support", supportScore);
        components.put("csat", csatScore);

        // 写 history (含 components JSON 快照, 后续可重算)
        HealthScoreHistory h = new HealthScoreHistory();
        h.setUserId(userId);
        h.setScore(total);
        h.setTier(tier);
        try {
            h.setComponents(jackson.writeValueAsString(components));
        } catch (Exception e) { /* ignore - JSON 序列化失败不影响主流程 */ }
        h.setCreatedAt(LocalDateTime.now());
        mapper.insert(h);

        return new HealthResult(userId, total, tier, components);
    }

    /**
     * 查最近一次健康分.
     *
     * @param uid 客户 ID. 业务含义: 要查的客户
     *            取值范围: Long > 0
     *            影响: 返该客户最新一条 history
     * @return HealthScoreHistory (无则 null)
     */
    public HealthScoreHistory findLatest(Long uid) {
        return mapper.findLatest(uid);
    }

    /**
     * 查健康分历史 (趋势用).
     *
     * @param uid 客户 ID. 业务含义: 要查的客户
     *            取值范围: Long > 0
     *            影响: 返该客户所有 history (按时间)
     * @return HealthScoreHistory 列表
     */
    public List<HealthScoreHistory> findHistory(Long uid) {
        return mapper.findHistory(uid);
    }

    /**
     * 从事件推算分数 (便捷方法, 用活跃天数 + CSAT).
     * ----------------------------------------------------------------------------
     * 算法 (各维分计算):
     *   - loginScore = min(100, activeDays * 10)
     *     (10 天登录 → 100 分封顶)
     *   - usageScore = min(100, activeDays * 15)
     *     (7 天深度用 → 100 分封顶, 比 login 系数高表示使用更深度)
     *   - csatScore = clamp(avgCsat * 20, 0, 100)
     *     (CSAT 5 星 → 100 分; 1 星 → 20 分; 0 → 0 分)
     *
     * @param userId     客户 ID. 业务含义: 计算目标
     *                   取值范围: Long > 0
     *                   影响: 写入 history
     * @param activeDays 近 30 天活跃天数. 业务含义: 客户活跃度
     *                   取值范围: 0-30
     *                   影响: loginScore + usageScore
     * @param avgCsat    平均 CSAT 评分. 业务含义: 客户满意度
     *                   取值范围: 0-5 (0 表示无评分)
     *                   影响: csatScore = avgCsat * 20
     * @return HealthResult
     */
    public HealthResult computeFromEvents(Long userId, long activeDays, double avgCsat) {
        // 活跃天数 → 登录频率分 (1 天 = 10 分, 10 天封顶)
        int loginScore = Math.min(100, (int) (activeDays * 10));
        // 活跃天数 → 功能使用分 (1 天 = 15 分, 7 天封顶, 系数更高)
        int usageScore = Math.min(100, (int) (activeDays * 15));
        // CSAT → 满意度分 (0-5 星 × 20 = 0-100)
        int csatScore = (int) Math.max(0, Math.min(100, avgCsat * 20));
        return computeAndSave(userId, loginScore, usageScore, csatScore);
    }
}
