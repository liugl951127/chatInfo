package com.chat.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * AgentStatsView - 坐席统计视图 (返回给 cs-customer-success 调用方).
 * ----------------------------------------------------------------------------
 * 字段:
 *   - agentId               坐席 ID
 *   - todaySessions         当日 (按 created_at) 该坐席的会话数
 *   - todayAvgResponseSec   当日平均响应时长 (秒) — 客户首条消息 -> 坐席首条回复
 *   - todayAvgCsat          当日平均 CSAT 评分 (0-5, 按 updated_at 在当日且 rating 非空)
 *   - monthSessions         当月累计会话数
 *   - monthAvgCsat          当月平均 CSAT
 *   - activeDays            近 30 天有活动天数
 *   - last7Days             近 7 天趋势 (按 created_at 分组, 升序: 老->新)
 *   - skills                技能 / 能力 评分 (按 skill_tag 聚合近 30 天)
 *   - dataSource            数据源标识 (REAL / FALLBACK)
 *   - generatedAt           服务端生成时间
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStatsView {

    private Long agentId;

    // ---- 当日 ----
    private int todaySessions;
    private int todayAvgResponseSec;
    private double todayAvgCsat;

    // ---- 当月 ----
    private int monthSessions;
    private double monthAvgCsat;

    // ---- 活跃 ----
    private int activeDays;

    // ---- 7 天趋势 ----
    private List<DailyPoint> last7Days;

    // ---- 能力 ----
    private List<SkillScore> skills;

    // ---- 元信息 ----
    private String dataSource;
    private String generatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyPoint {
        /** 日期 (yyyy-MM-dd) */
        private String date;
        /** LocalDate 类型, 方便排序 */
        private LocalDate day;
        private int count;
        private int avgResponseSec;
        private double avgCsat;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillScore {
        /** 技能标签 (如 "退款处理", "订单查询") */
        private String name;
        /** 0-100 评分 (基于 CSAT + 量级综合) */
        private int score;
        /** beginner / intermediate / advanced / expert */
        private String level;
        /** 该技能近 30 天的会话数 (用于推断熟练度) */
        private int volume;
        /** 该技能近 30 天的平均 CSAT */
        private double avgCsat;
    }
}
