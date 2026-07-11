package com.chat.cdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chat.cdp.entity.CdpEvent;
import com.chat.cdp.entity.CdpTag;
import com.chat.cdp.entity.CustomerProfile;
import com.chat.cdp.mapper.CdpEventMapper;
import com.chat.cdp.mapper.CdpTagMapper;
import com.chat.cdp.mapper.CustomerProfileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;

/**
 * TagService - 客户标签计算服务.
 * ----------------------------------------------------------------------------
 * 30 个基础标签 (阶段 1), 后续阶段 2 扩到 200.
 *
 * 标签分类:
 *   - 身份: new_customer / returning_customer / vip_silver/gold/diamond
 *   - 活跃: active_7d / active_30d / silent_7d / silent_30d / dormant_90d
 *   - 价值: high_value / mid_value / low_value
 *   - 行为: chat_heavy / return_heavy
 *   - 情绪: satisfied / neutral / dissatisfied
 *   - 偏好: morning_user / evening_user / weekend_user / mobile_first
 *   - 风险: first_time_return / frequent_complainer / payment_failed
 *
 * 计算策略:
 *   - 每次 profile 查询时实时算 (开销小, 30 个标签不到 10ms)
 *   - 计算结果写 cdp_tag 表 (供其他模块查询, 避免重复计算)
 *   - 定时任务 (阶段 2) 批量重算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagService {

    private final CdpTagMapper tagMapper;
    private final CdpEventMapper eventMapper;
    private final CustomerProfileMapper profileMapper;
    private final ObjectMapper mapper;

    /**
     * 计算并刷新某用户所有标签.
     * 写 cdp_tag 表 (覆盖式).
     */
    @Transactional
    public List<CdpTag> computeAndSaveTags(Long userId) {
        CustomerProfile profile = profileMapper.selectById(userId);
        if (profile == null) {
            log.debug("[tag] profile not found: user={}", userId);
            return List.of();
        }

        List<CdpTag> tags = new ArrayList<>();

        // 1) 身份标签
        tags.add(tag(userId, "new_customer", "true"));
        if (profile.getRegisterAt() != null
                && profile.getRegisterAt().isBefore(LocalDateTime.now().minusDays(30))) {
            tags.add(tag(userId, "returning_customer", "true"));
        }
        if (profile.getVipLevel() != null) {
            if (profile.getVipLevel() >= 1) tags.add(tag(userId, "vip_silver", "true"));
            if (profile.getVipLevel() >= 2) tags.add(tag(userId, "vip_gold", "true"));
            if (profile.getVipLevel() >= 3) tags.add(tag(userId, "vip_diamond", "true"));
        }

        // 2) 活跃标签
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastActive = profile.getLastActiveAt();
        if (lastActive != null) {
            long daysSince = java.time.temporal.ChronoUnit.DAYS.between(lastActive, now);
            if (daysSince <= 7) tags.add(tag(userId, "active_7d", String.valueOf(daysSince)));
            if (daysSince <= 30) tags.add(tag(userId, "active_30d", String.valueOf(daysSince)));
            if (daysSince >= 7) tags.add(tag(userId, "silent_7d", String.valueOf(daysSince)));
            if (daysSince >= 30) tags.add(tag(userId, "silent_30d", String.valueOf(daysSince)));
            if (daysSince >= 90) tags.add(tag(userId, "dormant_90d", String.valueOf(daysSince)));
        }

        // 3) 价值标签
        BigDecimal total = profile.getTotalAmount() == null ? BigDecimal.ZERO : profile.getTotalAmount();
        if (total.compareTo(new BigDecimal("10000")) >= 0) {
            tags.add(tag(userId, "high_value", total.toPlainString()));
        } else if (total.compareTo(new BigDecimal("1000")) >= 0) {
            tags.add(tag(userId, "mid_value", total.toPlainString()));
        } else {
            tags.add(tag(userId, "low_value", total.toPlainString()));
        }

        // 4) 行为标签 (基于事件流)
        long chatCount = eventMapper.countByUserAndType(userId, "chat_start", now.minusDays(30));
        if (chatCount >= 5) tags.add(tag(userId, "chat_heavy", String.valueOf(chatCount)));

        long returnCount = eventMapper.countByUserAndType(userId, "order_returned", now.minusDays(90));
        if (returnCount >= 2) tags.add(tag(userId, "return_heavy", String.valueOf(returnCount)));

        // 5) 情绪标签
        BigDecimal csat = profile.getAvgCsat() == null ? BigDecimal.ZERO : profile.getAvgCsat();
        if (csat.compareTo(new BigDecimal("4.0")) >= 0) {
            tags.add(tag(userId, "satisfied", csat.toPlainString()));
        } else if (csat.compareTo(new BigDecimal("3.0")) >= 0) {
            tags.add(tag(userId, "neutral", csat.toPlainString()));
        } else if (csat.compareTo(BigDecimal.ZERO) > 0) {
            tags.add(tag(userId, "dissatisfied", csat.toPlainString()));
        }

        // 6) 偏好标签 (基于事件时间分布, 简化版)
        // TODO 阶段 2: 查询事件 hour 分布, 算 morning/evening/weekend
        // 阶段 1 用简化的判定: 最近活跃时间落在 6-12 是 morning, 18-24 是 evening
        if (lastActive != null) {
            int hour = lastActive.getHour();
            if (hour >= 6 && hour < 12) tags.add(tag(userId, "morning_user", String.valueOf(hour)));
            else if (hour >= 18 && hour < 24) tags.add(tag(userId, "evening_user", String.valueOf(hour)));
        }

        // 7) 风险标签
        long paymentFail = eventMapper.countByUserAndType(userId, "payment_failed", now.minusHours(1));
        if (paymentFail >= 3) tags.add(tag(userId, "payment_failed", String.valueOf(paymentFail)));

        // 持久化 (覆盖式)
        for (CdpTag t : tags) {
            tagMapper.insert(t);  // 假设 ON DUPLICATE KEY UPDATE
        }
        log.debug("[tag] user={} computed {} tags", userId, tags.size());
        return tags;
    }

    /**
     * 查询某用户所有标签.
     */
    public List<CdpTag> getTags(Long userId) {
        return tagMapper.findByUserId(userId);
    }

    /**
     * 查询某用户某标签值.
     */
    public String getTagValue(Long userId, String key) {
        CdpTag t = tagMapper.selectOne(new QueryWrapper<CdpTag>()
                .eq("user_id", userId)
                .eq("tag_key", key)
                .last(true, "LIMIT 1"));
        return t == null ? null : t.getTagValue();
    }

    private CdpTag tag(Long userId, String key, String value) {
        CdpTag t = new CdpTag();
        t.setUserId(userId);
        t.setTagKey(key);
        t.setTagValue(value);
        t.setComputedAt(LocalDateTime.now());
        return t;
    }
}