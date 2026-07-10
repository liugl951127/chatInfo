package com.chat.cdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CustomerProfile - 客户主档案 (数字孪生 360).
 * ----------------------------------------------------------------------------
 * 与 user 表 1:1, 但承载运营侧的画像数据 (vip/订单/情绪/健康分/标签).
 * user 表不动 (auth 用), 这里做画像扩展.
 */
@Data
@TableName("cdp_customer_profile")
public class CustomerProfile {

    /** 用户 ID (PK, 1:1 with user.id) */
    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 昵称 (冗余 user.nickname, 避免 join) */
    private String nickname;

    /** 头像 URL */
    private String avatarUrl;

    /** VIP 等级: 0=普通 1=银 2=金 3=钻石 */
    private Integer vipLevel;

    /** 注册时间 */
    private LocalDateTime registerAt;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveAt;

    /** 历史订单数 */
    private Integer totalOrders;

    /** 历史消费金额 */
    private BigDecimal totalAmount;

    /** 平均满意度 (0.0-5.0) */
    private BigDecimal avgCsat;

    /** 历史会话数 */
    private Integer totalSessions;

    /** 流失风险: 0=健康 1=关注 2=风险 3=流失 */
    private Integer churnRisk;

    /** 健康分 (0-100) */
    private Integer healthScore;

    /** 标签数组 (JSON) */
    private String tags;

    /** 偏好 (JSON: 时间段/渠道/语言) */
    private String preferences;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}