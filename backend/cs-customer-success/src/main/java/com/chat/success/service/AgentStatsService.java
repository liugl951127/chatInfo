package com.chat.success.service;

import com.chat.success.entity.HealthScoreHistory;
import com.chat.success.mapper.HealthScoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * AgentStatsService - 坐席统计数据.
 * ----------------------------------------------------------------------------
 * 阶段 1: 基于 HealthScoreHistory + 简单计算生成 dashboard 数据.
 * 阶段 2: 接 chat_message / chat_session 真实统计.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStatsService {

    private final HealthScoreMapper healthMapper;

    public record DailyPoint(String date, int count, int avgResponseSec, double avgCsat) {}

    public record AgentStats(
            int todaySessions,
            int todayAvgResponseSec,    // 秒
            double todayAvgCsat,         // 0-5
            int monthSessions,
            int activeDays,
            double totalCsat,
            int resolvedCount,
            int pendingCount,
            List<DailyPoint> last7Days,
            List<SkillScore> skills
    ) {}

    public record SkillScore(String name, int score, String level) {}

    /**
     * 拿某坐席 dashboard 统计 (阶段 1 用 mock + 部分真数据).
     */
    public AgentStats getStats(Long agentId) {
        // 阶段 1: 简化版, 用 health history 计算活跃天数 + mock 其他
        // 阶段 2: 接 chat_message 真实统计
        List<HealthScoreHistory> history = healthMapper.findHistory(agentId);
        int activeDays = history.size();

        Random r = new Random(agentId == null ? 0 : agentId);
        int todaySessions = 18 + r.nextInt(15);
        int avgResponse = 12 + r.nextInt(20);
        double avgCsat = 4.2 + r.nextDouble() * 0.7;
        int monthSessions = 200 + r.nextInt(200);
        double totalCsat = 4.5 + r.nextDouble() * 0.4;
        int resolved = todaySessions - 2;
        int pending = 2 + r.nextInt(3);

        // 7 天趋势
        List<DailyPoint> last7 = new ArrayList<>();
        String[] days = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        for (int i = 0; i < 7; i++) {
            int cnt = 20 + r.nextInt(25);
            int resp = 10 + r.nextInt(25);
            double csat = 4.0 + r.nextDouble() * 0.9;
            last7.add(new DailyPoint(days[i], cnt, resp, round1(csat)));
        }

        // 能力评分 (基于 health score 衍生)
        List<SkillScore> skills = new ArrayList<>();
        skills.add(new SkillScore("退款处理", 85 + r.nextInt(10), "expert"));
        skills.add(new SkillScore("订单查询", 88 + r.nextInt(8), "expert"));
        skills.add(new SkillScore("投诉处理", 70 + r.nextInt(15), "advanced"));
        skills.add(new SkillScore("建议反馈", 60 + r.nextInt(15), "intermediate"));

        return new AgentStats(
                todaySessions, avgResponse, round1(avgCsat),
                monthSessions, activeDays, round1(totalCsat),
                resolved, pending,
                last7, skills
        );
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}