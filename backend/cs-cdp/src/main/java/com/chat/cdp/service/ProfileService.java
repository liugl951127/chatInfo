package com.chat.cdp.service;

import com.chat.cdp.entity.CdpTag;
import com.chat.cdp.entity.CustomerProfile;
import com.chat.cdp.mapper.CustomerProfileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ProfileService - 客户档案服务.
 * ----------------------------------------------------------------------------
 * 提供 360 视图, 一次性返回画像 + 标签 + 偏好.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final CustomerProfileMapper profileMapper;
    private final TagService tagService;
    private final ObjectMapper mapper;

    /**
     * 拿客户 360 档案.
     * @return 包含 profile + tags[] + preferences 的 Map
     */
    public Map<String, Object> getProfile(Long userId) {
        CustomerProfile profile = profileMapper.selectById(userId);
        if (profile == null) {
            // 首次访问, 初始化空档案
            profile = new CustomerProfile();
            profile.setUserId(userId);
            profile.setHealthScore(100);
            profile.setChurnRisk(0);
            profile.setVipLevel(0);
        }
        List<CdpTag> tags = tagService.getTags(userId);
        if (tags.isEmpty()) {
            // 触发一次计算
            tags = tagService.computeAndSaveTags(userId);
        }
        return assemble(profile, tags);
    }

    /**
     * 触发画像重算 (事件上报后调用, 异步).
     */
    @Transactional
    public void recompute(Long userId) {
        tagService.computeAndSaveTags(userId);
    }

    /**
     * 更新活跃时间 (每次心跳/事件时).
     */
    public void touchActive(Long userId) {
        CustomerProfile p = profileMapper.selectById(userId);
        if (p == null) {
            p = new CustomerProfile();
            p.setUserId(userId);
            p.setHealthScore(100);
            p.setVipLevel(0);
            p.setChurnRisk(0);
        }
        p.setLastActiveAt(java.time.LocalDateTime.now());
        if (p.getRegisterAt() == null) p.setRegisterAt(java.time.LocalDateTime.now());
        if (profileMapper.selectById(userId) == null) {
            profileMapper.insert(p);
        } else {
            profileMapper.updateById(p);
        }
    }

    private Map<String, Object> assemble(CustomerProfile p, List<CdpTag> tags) {
        Map<String, Object> view = new HashMap<>();
        view.put("userId", p.getUserId());
        view.put("nickname", p.getNickname());
        view.put("avatarUrl", p.getAvatarUrl());
        view.put("vipLevel", p.getVipLevel());
        view.put("vipLabel", vipLabel(p.getVipLevel()));
        view.put("totalOrders", p.getTotalOrders());
        view.put("totalAmount", p.getTotalAmount());
        view.put("avgCsat", p.getAvgCsat());
        view.put("totalSessions", p.getTotalSessions());
        view.put("churnRisk", p.getChurnRisk());
        view.put("churnLabel", churnLabel(p.getChurnRisk()));
        view.put("healthScore", p.getHealthScore());
        view.put("healthLabel", healthLabel(p.getHealthScore()));
        view.put("lastActiveAt", p.getLastActiveAt());
        view.put("tags", tags.stream()
                .collect(Collectors.toMap(CdpTag::getTagKey, CdpTag::getTagValue, (a, b) -> a)));
        return view;
    }

    private String vipLabel(Integer level) {
        if (level == null) return "普通";
        return switch (level) {
            case 1 -> "银卡会员";
            case 2 -> "金卡会员";
            case 3 -> "钻石会员";
            default -> "普通会员";
        };
    }

    private String churnLabel(Integer risk) {
        if (risk == null) return "健康";
        return switch (risk) {
            case 1 -> "关注";
            case 2 -> "风险";
            case 3 -> "流失";
            default -> "健康";
        };
    }

    private String healthLabel(Integer score) {
        if (score == null) return "未知";
        if (score >= 80) return "健康";
        if (score >= 60) return "良好";
        if (score >= 40) return "关注";
        return "风险";
    }
}