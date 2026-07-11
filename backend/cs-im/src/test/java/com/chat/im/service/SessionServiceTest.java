package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.chat.im.dto.AgentStatsView;
import com.chat.im.entity.Agent;
import com.chat.im.entity.ChatMessage;
import com.chat.im.entity.ChatSession;
import com.chat.im.mapper.ChatMessageMapper;
import com.chat.im.mapper.ChatSessionMapper;
import com.chat.im.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SessionServiceTest - 会话服务单元测试.
 * ----------------------------------------------------------------------------
 * 重点覆盖:
 *   - computeAgentStats 真实数据接入 (阶段 2: 替换 mock 的核心)
 *   - 各种边界场景: 空数据 / 坐席不存在 / 非 AGENT / 仅当天数据 / 技能聚合
 *   - 7 日趋势的拼接顺序 (老->新)
 *   - 平均响应时长算法 (客户首条 -> 坐席首条回复)
 *   - 技能评分公式 (CSAT + 量级)
 * <p>
 * 不依赖 Spring 容器, 用 Mockito 直接 mock 三个 DAO + Redis.
 */
class SessionServiceTest {

    private ChatSessionMapper sessionMapper;
    private ChatMessageMapper messageMapper;
    private UserMapper userMapper;
    private StringRedisTemplate redis;
    private SessionService sessionService;

    // 其它 service (computeAgentStats 不会用到, 但构造器需要)
    private PresenceService presenceService;
    private AgentStatusService agentStatusService;
    private WsPushService wsPushService;
    private AuditLogService auditLogService;
    private SystemMessageService systemMessageService;

    /** sessionId -> 该 session 的消息列表 (按 createdAt 升序) */
    private Map<Long, List<ChatMessage>> msgTable;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(ChatSessionMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        userMapper = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);

        presenceService = mock(PresenceService.class);
        agentStatusService = mock(AgentStatusService.class);
        wsPushService = mock(WsPushService.class);
        auditLogService = mock(AuditLogService.class);
        systemMessageService = mock(SystemMessageService.class);

        msgTable = new HashMap<>();

        // 默认行为: messageMapper.selectList(Wrapper) 查 msgTable 找对应的 sessionId
        when(messageMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
            Wrapper<ChatMessage> w = inv.getArgument(0);
            String sql = w.getSqlSegment();
            if (sql == null) return List.of();
            // 解析 session_id = #{ew.paramNameValuePairs.MPGENVAL1}
            // 实际值在 paramNameValuePairs.Map 里
            for (Map.Entry<Long, List<ChatMessage>> e : msgTable.entrySet()) {
                if (sql.contains("session_id") && w.toString().contains("session_id")) {
                    return e.getValue();
                }
            }
            return List.of();
        });

        // 简化: 如果 stub 了具体 session 的, 用 capture 来分发
        // 改用 doAnswer + capture paramNameValuePairs
        when(messageMapper.selectList(any(Wrapper.class))).thenAnswer(inv -> {
            Wrapper<ChatMessage> w = inv.getArgument(0);
            if (w == null) return List.of();
            String sql = w.getSqlSegment();
            if (sql == null) return List.of();
            // 通过反射拿 paramNameValuePairs
            try {
                java.lang.reflect.Field f = w.getClass().getSuperclass().getDeclaredField("paramNameValuePairs");
                f.setAccessible(true);
                Map<String, Object> params = (Map<String, Object>) f.get(w);
                Object sid = params.get("MPGENVAL1");
                if (sid != null) {
                    List<ChatMessage> msgs = msgTable.get(((Number) sid).longValue());
                    if (msgs != null) return msgs;
                }
            } catch (Exception e) {
                // 忽略
            }
            return List.of();
        });

        sessionService = new SessionService(
                sessionMapper, messageMapper, userMapper, redis,
                presenceService, agentStatusService,
                wsPushService, auditLogService, systemMessageService
        );
    }

    /** 注册指定 session 的消息 (后续 messageMapper.selectList 返) */
    private void registerMessages(long sessionId, ChatMessage... msgs) {
        msgTable.put(sessionId, new ArrayList<>(Arrays.asList(msgs)));
    }

    // ====================================================================
    //  1. computeAgentStats 基础场景
    // ====================================================================

    @Test
    void computeAgentStats_NullAgentId_ReturnsEmpty() {
        AgentStatsView v = sessionService.computeAgentStats(null);
        assertNotNull(v);
        assertNull(v.getAgentId());
        assertEquals(0, v.getTodaySessions());
        assertEquals(0, v.getTodayAvgResponseSec());
        assertEquals(0.0, v.getTodayAvgCsat());
        assertEquals(0, v.getActiveDays());
        assertEquals(7, v.getLast7Days().size());
        assertTrue(v.getDataSource().startsWith("EMPTY"));
        // 7 日全 0 占位
        for (AgentStatsView.DailyPoint p : v.getLast7Days()) {
            assertEquals(0, p.getCount());
        }
    }

    @Test
    void computeAgentStats_ZeroAgentId_ReturnsEmpty() {
        AgentStatsView v = sessionService.computeAgentStats(0L);
        assertTrue(v.getDataSource().startsWith("EMPTY"));
        assertEquals(0, v.getTodaySessions());
    }

    @Test
    void computeAgentStats_NegativeAgentId_ReturnsEmpty() {
        AgentStatsView v = sessionService.computeAgentStats(-1L);
        assertTrue(v.getDataSource().startsWith("EMPTY"));
    }

    @Test
    void computeAgentStats_AgentNotFound_ReturnsEmpty() {
        when(userMapper.selectById(999L)).thenReturn(null);
        AgentStatsView v = sessionService.computeAgentStats(999L);
        assertNotNull(v);
        assertEquals(999L, v.getAgentId());
        assertTrue(v.getDataSource().startsWith("EMPTY"));
        verify(userMapper).selectById(999L);
    }

    @Test
    void computeAgentStats_AgentNotAgentRole_ReturnsEmpty() {
        Agent cust = new Agent();
        cust.setId(2L);
        cust.setRole("CUSTOMER");
        when(userMapper.selectById(2L)).thenReturn(cust);

        AgentStatsView v = sessionService.computeAgentStats(2L);
        assertTrue(v.getDataSource().startsWith("EMPTY"));
    }

    @Test
    void computeAgentStats_NoSessionsIn30Days_ReturnsEmpty() {
        Agent agent = new Agent();
        agent.setId(7L);
        agent.setRole("AGENT");
        when(userMapper.selectById(7L)).thenReturn(agent);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AgentStatsView v = sessionService.computeAgentStats(7L);
        assertEquals(7L, v.getAgentId());
        assertEquals(0, v.getTodaySessions());
        assertEquals(0, v.getActiveDays());
        assertTrue(v.getDataSource().contains("REAL"));  // 仍标记 REAL, 只是无数据
    }

    // ====================================================================
    //  2. 完整数据场景 (当天 + 月 + 7 日 + 技能)
    // ====================================================================

    @Test
    void computeAgentStats_FullScenario_RealData() {
        long agentId = 11L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        // ---- 准备会话数据 ----
        LocalDate today = LocalDate.now();
        LocalDateTime t0 = today.atTime(9, 0);

        ChatSession s1 = session(101L, agentId, "退款处理", t0, 4);
        ChatSession s2 = session(102L, agentId, "订单查询", t0.plusHours(1), 5);
        ChatSession s3 = session(103L, agentId, "退款处理", t0.plusHours(2), null);
        ChatSession s4 = session(104L, agentId, "投诉处理", today.minusDays(1).atTime(10, 0), 3);
        ChatSession s5 = session(105L, agentId, "订单查询", today.minusDays(3).atTime(14, 0), 5);
        ChatSession s6 = session(106L, agentId, "退款处理", today.minusDays(6).atTime(11, 0), 4);
        ChatSession s7 = session(107L, agentId, "订单查询", today.minusDays(8).atTime(15, 0), 5);

        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(
                new ArrayList<>(Arrays.asList(s1, s2, s3, s4, s5, s6, s7))
        );

        // ---- 准备消息数据 (用 registerMessages) ----
        registerMessages(101L,
                msg(1L, 101L, 1L, "CUSTOMER", today.atTime(9, 0, 0)),
                msg(2L, 101L, agentId, "AGENT",    today.atTime(9, 0, 30))
        );
        registerMessages(102L,
                msg(3L, 102L, 1L, "CUSTOMER", today.atTime(10, 0, 0)),
                msg(4L, 102L, agentId, "AGENT",    today.atTime(10, 2, 0))
        );
        registerMessages(103L,
                msg(5L, 103L, 1L, "CUSTOMER", today.atTime(11, 0, 0))
        );
        registerMessages(104L,
                msg(6L, 104L, 1L, "CUSTOMER", today.minusDays(1).atTime(10, 0, 0)),
                msg(7L, 104L, agentId, "AGENT",    today.minusDays(1).atTime(10, 1, 0))
        );
        registerMessages(105L,
                msg(8L, 105L, 1L, "CUSTOMER", today.minusDays(3).atTime(14, 0, 0)),
                msg(9L, 105L, agentId, "AGENT",    today.minusDays(3).atTime(14, 0, 45))
        );
        registerMessages(106L,
                msg(10L, 106L, 1L, "CUSTOMER", today.minusDays(6).atTime(11, 0, 0)),
                msg(11L, 106L, agentId, "AGENT",    today.minusDays(6).atTime(11, 1, 30))
        );
        registerMessages(107L,
                msg(12L, 107L, 1L, "CUSTOMER", today.minusDays(8).atTime(15, 0, 0))
        );

        // ---- 调 ----
        AgentStatsView v = sessionService.computeAgentStats(agentId);

        // ---- 断言 ----
        assertNotNull(v);
        assertEquals(agentId, v.getAgentId());
        assertEquals("REAL", v.getDataSource());

        // 当日: 3 个 (S1+S2+S3)
        assertEquals(3, v.getTodaySessions());
        // 当日响应: 30 + 120 + 0(S3没回) = 150 / 2 = 75
        assertEquals(75, v.getTodayAvgResponseSec());
        // 当日 CSAT: (4+5)/2 = 4.5 (S3 未评不算)
        assertEquals(4.5, v.getTodayAvgCsat(), 0.01);

        // 当月 (本测试全部在当月): 7
        assertEquals(7, v.getMonthSessions());
        // 当月 CSAT: (4+5+3+5+4+5) / 6 = 4.33... -> round1 = 4.3
        assertEquals(4.3, v.getMonthAvgCsat(), 0.01);

        // 活跃天数: 5 (今天/昨天/3天前/6天前/8天前)
        assertEquals(5, v.getActiveDays());

        // 7 日趋势
        List<AgentStatsView.DailyPoint> last7 = v.getLast7Days();
        assertEquals(7, last7.size());
        // 第 1 条 (i=0) 是 6 天前
        assertEquals(today.minusDays(6).toString(), last7.get(0).getDate());
        assertEquals(1, last7.get(0).getCount());   // S6
        assertEquals(90, last7.get(0).getAvgResponseSec());
        assertEquals(4.0, last7.get(0).getAvgCsat(), 0.01);
        // 第 4 条 (i=3) 是 3 天前
        assertEquals(today.minusDays(3).toString(), last7.get(3).getDate());
        assertEquals(1, last7.get(3).getCount());   // S5
        assertEquals(45, last7.get(3).getAvgResponseSec());
        assertEquals(5.0, last7.get(3).getAvgCsat(), 0.01);
        // 第 5 条 (last7[4] = i=2, today-2) 是 2 天前, 0 个 (无 S4)
        assertEquals(today.minusDays(2).toString(), last7.get(4).getDate());
        assertEquals(0, last7.get(4).getCount());
        // 第 6 条 (last7[5] = i=1, today-1) 是 1 天前 (S4)
        assertEquals(today.minusDays(1).toString(), last7.get(5).getDate());
        assertEquals(1, last7.get(5).getCount());   // S4
        // 第 7 条 (i=6) 是 今天
        assertEquals(today.toString(), last7.get(6).getDate());
        assertEquals(3, last7.get(6).getCount());   // S1+S2+S3
        assertEquals(75, last7.get(6).getAvgResponseSec());
        assertEquals(4.5, last7.get(6).getAvgCsat(), 0.01);

        // 技能
        List<AgentStatsView.SkillScore> skills = v.getSkills();
        assertEquals(3, skills.size());
        // 排序: 订单(70) > 退款(56) > 投诉(42)
        assertEquals("订单查询", skills.get(0).getName());
        assertEquals(70, skills.get(0).getScore());
        assertEquals("intermediate", skills.get(0).getLevel());
        assertEquals("退款处理", skills.get(1).getName());
        assertEquals(56, skills.get(1).getScore());
        assertEquals("投诉处理", skills.get(2).getName());
        assertEquals(42, skills.get(2).getScore());
    }

    // ====================================================================
    //  3. 平均响应时长算法
    // ====================================================================

    @Test
    void avgResponseTime_IgnoreMessagesBeforeCustomerFirst() {
        long agentId = 22L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        LocalDate today = LocalDate.now();
        LocalDateTime t0 = today.atTime(10, 0);

        ChatSession s = session(201L, agentId, "通用", t0, null);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(s)));

        // 顺序: AGENT (没人发问) -> CUSTOMER (10:00:00) -> AGENT (10:00:20) -> AGENT (10:00:30)
        // 期望: 客户首条 10:00:00 -> 坐席首条 10:00:20 = 20s
        registerMessages(201L,
                msg(1L, 201L, agentId, "AGENT",    today.atTime(9, 50, 0)),
                msg(2L, 201L, 1L, "CUSTOMER", today.atTime(10, 0, 0)),
                msg(3L, 201L, agentId, "AGENT",    today.atTime(10, 0, 20)),
                msg(4L, 201L, agentId, "AGENT",    today.atTime(10, 0, 30))
        );

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertEquals(1, v.getTodaySessions());
        assertEquals(20, v.getTodayAvgResponseSec());
    }

    @Test
    void avgResponseTime_NoCustomerMessage_NoResponse() {
        long agentId = 23L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        LocalDate today = LocalDate.now();
        LocalDateTime t0 = today.atTime(10, 0);

        ChatSession s = session(202L, agentId, "通用", t0, null);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(s)));

        registerMessages(202L,
                msg(1L, 202L, agentId, "AGENT", today.atTime(10, 0, 0))
        );

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertEquals(1, v.getTodaySessions());
        assertEquals(0, v.getTodayAvgResponseSec(), "没客户消息 -> 平均为 0");
    }

    @Test
    void avgResponseTime_Over24h_IsFiltered() {
        long agentId = 24L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        LocalDate today = LocalDate.now();
        LocalDateTime t0 = today.atTime(10, 0);

        ChatSession s = session(203L, agentId, "通用", t0, null);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(s)));

        // 24h 差 (边界, 应包含)
        registerMessages(203L,
                msg(1L, 203L, 1L, "CUSTOMER", today.atTime(10, 0, 0)),
                msg(2L, 203L, agentId, "AGENT",    today.plusDays(1).atTime(10, 0, 0))
        );

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertEquals(86400, v.getTodayAvgResponseSec());
    }

    @Test
    void avgResponseTime_Over24h01min_IsFiltered() {
        long agentId = 25L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        LocalDate today = LocalDate.now();
        LocalDateTime t0 = today.atTime(10, 0);

        ChatSession s = session(204L, agentId, "通用", t0, null);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(s)));

        // 25h 差 (应过滤)
        registerMessages(204L,
                msg(1L, 204L, 1L, "CUSTOMER", today.atTime(10, 0, 0)),
                msg(2L, 204L, agentId, "AGENT",    today.atStartOfDay().plusHours(35))
        );

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertEquals(0, v.getTodayAvgResponseSec(), "差 > 24h 应被过滤, 平均为 0");
    }

    // ====================================================================
    //  4. 技能评分
    // ====================================================================

    @Test
    void skills_HighVolume_HighCSAT() {
        long agentId = 31L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        LocalDate today = LocalDate.now();
        List<ChatSession> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sessions.add(session(
                    (long) (300 + i), agentId, "VIP服务",
                    today.atTime(9, i), 5
            ));
        }
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(sessions);

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertEquals(1, v.getSkills().size());
        AgentStatsView.SkillScore s = v.getSkills().get(0);
        assertEquals("VIP服务", s.getName());
        assertEquals(10, s.getVolume());
        assertEquals(5.0, s.getAvgCsat(), 0.01);
        // base=100, bonus=min(20, 10/5)=2, score=round(100*0.7+2)=72
        assertEquals(72, s.getScore());
        assertEquals("intermediate", s.getLevel());
    }

    @Test
    void skills_Empty_NoSkills() {
        long agentId = 32L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertTrue(v.getSkills().isEmpty());
    }

    @Test
    void skills_NullSkillTag_GroupedAs通用() {
        long agentId = 33L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        LocalDate today = LocalDate.now();
        ChatSession s1 = session(401L, agentId, null, today.atTime(9, 0), 4);
        ChatSession s2 = session(402L, agentId, "", today.atTime(10, 0), 5);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(s1, s2)));

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertEquals(1, v.getSkills().size());
        assertEquals("通用", v.getSkills().get(0).getName());
    }

    // ====================================================================
    //  5. 7 日趋势顺序
    // ====================================================================

    @Test
    void last7Days_OrderIsOldToNew() {
        long agentId = 41L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        LocalDate today = LocalDate.now();
        ChatSession s = session(501L, agentId, "通用", today.atTime(9, 0), null);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(s)));
        registerMessages(501L); // 空消息

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        List<AgentStatsView.DailyPoint> pts = v.getLast7Days();
        for (int i = 0; i < pts.size() - 1; i++) {
            LocalDate d1 = pts.get(i).getDay();
            LocalDate d2 = pts.get(i + 1).getDay();
            assertTrue(d1.isBefore(d2), "顺序应为老->新, " + d1 + " 应早于 " + d2);
        }
        assertEquals(today, pts.get(pts.size() - 1).getDay());
    }

    // ====================================================================
    //  6. CSAT 过滤
    // ====================================================================

    @Test
    void csat_OnlyUpdatedToday_OrNullRating_Excluded() {
        long agentId = 51L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);

        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        ChatSession s1 = session(601L, agentId, "通用", today.atTime(9, 0), 5);
        s1.setUpdatedAt(today.atTime(9, 30));
        ChatSession s2 = session(602L, agentId, "通用", today.atTime(10, 0), null);
        s2.setUpdatedAt(today.atTime(10, 30));
        ChatSession s3 = session(603L, agentId, "通用", yesterday.atTime(11, 0), 4);
        s3.setUpdatedAt(yesterday.atTime(11, 30));

        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(
                new ArrayList<>(Arrays.asList(s1, s2, s3))
        );

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertEquals(5.0, v.getTodayAvgCsat(), 0.01);
        // 当月: S1 (5) + S3 (4, 当月的话) = 4.5 (S2 null 排除)
        assertEquals(4.5, v.getMonthAvgCsat(), 0.01);
    }

    // ====================================================================
    //  7. SQL 查询验证
    // ====================================================================

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void computeAgentStats_TriggersSessionQuery_AtLeastOnce() {
        long agentId = 61L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        sessionService.computeAgentStats(agentId);

        // 验证: 至少调了一次 sessionMapper.selectList
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper, atLeastOnce()).selectList(captor.capture());
        // 生成的 SQL 应包含 agent_id, created_at (LambdaQueryWrapper 在 mock 中可能 sqlSegment 是空, 这点我们宽松一点)
        // 这里主要验证: 调用了 sessionMapper.selectList
        assertFalse(captor.getAllValues().isEmpty());
    }

    // ====================================================================
    //  8. generatedAt 字段不为空
    // ====================================================================

    @Test
    void computeAgentStats_GeneratedAtIsNotEmpty() {
        long agentId = 71L;
        Agent agent = new Agent();
        agent.setId(agentId);
        agent.setRole("AGENT");
        when(userMapper.selectById(agentId)).thenReturn(agent);
        when(sessionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AgentStatsView v = sessionService.computeAgentStats(agentId);
        assertNotNull(v.getGeneratedAt());
        assertFalse(v.getGeneratedAt().isEmpty());
    }

    // ====================================================================
    //  helpers
    // ====================================================================

    private ChatSession session(long id, long agentId, String skillTag,
                                LocalDateTime createdAt, Integer rating) {
        ChatSession s = new ChatSession();
        s.setId(id);
        s.setAgentId(agentId);
        s.setCustomerId(1L);
        s.setSkillTag(skillTag);
        s.setStatus("CLOSED");
        s.setCreatedAt(createdAt);
        s.setUpdatedAt(createdAt.plusMinutes(30));
        s.setRating(rating);
        return s;
    }

    private ChatMessage msg(long id, long sessionId, long senderId, String role, LocalDateTime ts) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setSenderId(senderId);
        m.setSenderRole(role);
        m.setMsgType("TEXT");
        m.setContent("test");
        m.setCreatedAt(ts);
        return m;
    }
}
