package com.chat.cdp.service;

import com.chat.cdp.entity.CdpTag;                                             // 客户标签实体
import com.chat.cdp.entity.CustomerProfile;                                    // 客户档案实体
import com.chat.cdp.mapper.CustomerProfileMapper;                              // 档案 DAO
import com.fasterxml.jackson.databind.ObjectMapper;                            // JSON 序列化 (留扩展)
import lombok.RequiredArgsConstructor;                                        // final 字段构造注入
import lombok.extern.slf4j.Slf4j;                                              // 日志
import org.springframework.stereotype.Service;                                 // Spring Bean
import org.springframework.transaction.annotation.Transactional;                // 事务边界

import java.util.HashMap;                                                       // 360 视图容器
import java.util.List;                                                          // 标签列表
import java.util.Map;                                                           // 标签 Map
import java.util.stream.Collectors;                                             // List → Map

/**
 * ProfileService - 客户档案 (CDP 数字孪生) 服务.
 * ----------------------------------------------------------------------------
 * 提供客户 360 视图, 一次性返回画像 + 标签 + 偏好, 供前端 Agent.vue / Customer.vue 展示.
 *
 * 核心能力:
 *   - getProfile: 拿 360 档案 (含标签聚合, 缺标签时自动触发计算)
 *   - recompute: 触发画像重算 (事件上报后异步调用)
 *   - touchActive: 更新活跃时间 (心跳/事件时)
 *   - assemble: 组装 360 视图 (内部用)
 *   - vipLabel/churnLabel/healthLabel: 等级/风险/健康度的文字映射
 *
 * 数据模型:
 *   - customer_profile: 主表, 1 行/用户
 *   - cdp_tag: 标签表, N 行/用户 (key-value 形式)
 *
 * 设计意图:
 *   - 360 视图聚合: 单次查询拉所有画像信息, 减少前端多次请求
 *   - 懒计算: 没标签时自动触发计算, 避免定时任务开销
 *   - 标签转 Map: tags list → Map<key, value> 便于前端 O(1) 查
 *   - 等级映射: 数字 → 文字 (前端展示友好)
 *
 * @author V3 Team
 * @since 2025-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    /** 客户档案 DAO */
    private final CustomerProfileMapper profileMapper;
    /** 标签服务 (计算 + 查询) */
    private final TagService tagService;
    /** JSON 序列化 (留扩展, 暂未用) */
    private final ObjectMapper mapper;

    /**
     * 拿客户 360 档案 (核心方法).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 查 customer_profile (无则返回默认空档案)
     *   step 2: 查 cdp_tag (空则触发一次计算)
     *   step 3: 组装 360 视图 (assemble)
     *
     * @param userId 客户 ID. 业务含义: 要查询的用户
     *               取值范围: Long > 0
     *               影响: 决定返回哪个客户的画像
     * @return Map (key=userId/nickname/vipLevel/tags 等), 前端可 JSON.parse 直接用
     *         - 必含: userId, vipLevel, churnRisk, healthScore, tags (Map<key,value>)
     *         - 标签为空时自动重算
     */
    public Map<String, Object> getProfile(Long userId) {
        // step 1: 查主档案, 不存在则返回默认空档案 (首次访问)
        CustomerProfile profile = profileMapper.selectById(userId);
        if (profile == null) {
            // 首次访问, 初始化默认值 (健康分 100, 流失风险 0, 普通会员)
            profile = new CustomerProfile();
            profile.setUserId(userId);
            profile.setHealthScore(100);                                       // 默认健康
            profile.setChurnRisk(0);                                           // 默认无风险
            profile.setVipLevel(0);                                            // 默认普通
        }
        // step 2: 拿标签列表
        List<CdpTag> tags = tagService.getTags(userId);
        if (tags.isEmpty()) {
            // 无标签 → 触发一次计算 (懒加载)
            tags = tagService.computeAndSaveTags(userId);
        }
        // step 3: 组装 360 视图
        return assemble(profile, tags);
    }

    /**
     * 触发画像重算 (事件上报后异步调用, 用于刷新标签).
     * <p>
     * 调用场景: 客户完成订单/提交工单/发送消息等事件后, 异步刷新其标签.
     *
     * @param userId 客户 ID. 业务含义: 要重算画像的用户
     *               取值范围: Long > 0
     *               影响: 异步触发标签重算, 不阻塞主流程
     */
    @Transactional
    public void recompute(Long userId) {
        // 委托给 tagService 实际执行计算 + 持久化
        tagService.computeAndSaveTags(userId);
    }

    /**
     * 更新活跃时间 (心跳/事件时调用, 用于"最近活跃"展示).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 查档案, 无则初始化默认值
     *   step 2: 更新 last_active_at
     *   step 3: 若 register_at 为空, 同时设置 (首次活跃)
     *   step 4: insert 或 update (根据档案是否存在)
     *
     * @param userId 客户 ID. 业务含义: 心跳来源用户
     *               取值范围: Long > 0
     *               影响: 写 last_active_at, 用于活跃度计算
     */
    public void touchActive(Long userId) {
        // step 1: 查或初始化
        CustomerProfile p = profileMapper.selectById(userId);
        if (p == null) {
            p = new CustomerProfile();
            p.setUserId(userId);
            p.setHealthScore(100);
            p.setVipLevel(0);
            p.setChurnRisk(0);
        }
        // step 2: 更新活跃时间
        p.setLastActiveAt(java.time.LocalDateTime.now());
        // step 3: 首次活跃时设置注册时间
        if (p.getRegisterAt() == null) p.setRegisterAt(java.time.LocalDateTime.now());
        // step 4: 存在则更新, 不存在则插入
        if (profileMapper.selectById(userId) == null) {
            profileMapper.insert(p);
        } else {
            profileMapper.updateById(p);
        }
    }

    /**
     * 组装 360 视图 (内部用, 将实体 → Map).
     * <p>
     * 输出字段:
     *   - userId/nickname/avatarUrl: 基础身份
     *   - vipLevel/vipLabel: VIP 等级 (数字 + 文字)
     *   - totalOrders/totalAmount: 订单汇总
     *   - avgCsat: 平均 CSAT 评分
     *   - totalSessions: 总会话数
     *   - churnRisk/churnLabel: 流失风险 (数字 + 文字)
     *   - healthScore/healthLabel: 健康分 (数字 + 文字)
     *   - lastActiveAt: 最近活跃时间
     *   - tags: Map<key, value> (e.g. {"preference":"tech", "vip":"gold"})
     *
     * @param p    客户档案实体
     * @param tags 标签列表
     * @return 360 视图 Map
     */
    private Map<String, Object> assemble(CustomerProfile p, List<CdpTag> tags) {
        Map<String, Object> view = new HashMap<>();
        // 基础身份
        view.put("userId", p.getUserId());
        view.put("nickname", p.getNickname());
        view.put("avatarUrl", p.getAvatarUrl());
        // VIP 等级
        view.put("vipLevel", p.getVipLevel());
        view.put("vipLabel", vipLabel(p.getVipLevel()));                      // 数字 → 文字
        // 订单汇总
        view.put("totalOrders", p.getTotalOrders());
        view.put("totalAmount", p.getTotalAmount());
        view.put("avgCsat", p.getAvgCsat());
        view.put("totalSessions", p.getTotalSessions());
        // 流失风险
        view.put("churnRisk", p.getChurnRisk());
        view.put("churnLabel", churnLabel(p.getChurnRisk()));
        // 健康分
        view.put("healthScore", p.getHealthScore());
        view.put("healthLabel", healthLabel(p.getHealthScore()));
        // 活跃时间
        view.put("lastActiveAt", p.getLastActiveAt());
        // 标签: list → Map<key, value> (key 冲突时取第一个)
        view.put("tags", tags.stream()
                .collect(Collectors.toMap(CdpTag::getTagKey, CdpTag::getTagValue, (a, b) -> a)));
        return view;
    }

    /**
     * VIP 等级数字 → 文字映射.
     *
     * @param level 等级 0/1/2/3
     * @return "普通会员" / "银卡会员" / "金卡会员" / "钻石会员"
     */
    private String vipLabel(Integer level) {
        if (level == null) return "普通";
        return switch (level) {
            case 1 -> "银卡会员";
            case 2 -> "金卡会员";
            case 3 -> "钻石会员";
            default -> "普通会员";
        };
    }

    /**
     * 流失风险数字 → 文字映射.
     *
     * @param risk 风险 0/1/2/3
     * @return "健康" / "关注" / "风险" / "流失"
     */
    private String churnLabel(Integer risk) {
        if (risk == null) return "健康";
        return switch (risk) {
            case 1 -> "关注";
            case 2 -> "风险";
            case 3 -> "流失";
            default -> "健康";
        };
    }

    /**
     * 健康分数字 → 文字映射.
     *
     * @param score 健康分 0-100
     * @return "未知" / "健康" (>=80) / "良好" (>=60) / "关注" (>=40) / "风险" (<40)
     */
    private String healthLabel(Integer score) {
        if (score == null) return "未知";
        if (score >= 80) return "健康";
        if (score >= 60) return "良好";
        if (score >= 40) return "关注";
        return "风险";
    }
}
