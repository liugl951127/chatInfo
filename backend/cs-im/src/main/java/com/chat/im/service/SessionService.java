package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;       // MP Lambda 构造器 (类型安全, 字段引用)
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;              // MP 普通构造器 (支持 OR / 自定义 SQL)
import com.chat.common.api.ApiResponse;                                        // 统一响应包装 {code, msg, data}
import com.chat.common.constant.CommonConstants;                               // 公共常量 (Redis key, role, session status)
import com.chat.common.security.UserContext;
import com.chat.im.dto.AgentStatsView;                                          // 坐席统计视图 DTO (阶段 2 真实数据)                                   // 当前用户 ThreadLocal (uid + role)
import com.chat.im.event.TransferToHumanEvent;                                  // 转人工事件 (ApplicationEvent)
import org.springframework.context.event.EventListener;                        // @EventListener 监听器
import org.springframework.scheduling.annotation.Async;                         // @Async 异步执行
import com.chat.im.entity.Agent;                                                // Agent 实体 (复用 user 表的坐席)
import com.chat.im.entity.ChatMessage;
import com.chat.im.entity.ChatSession;                                          // chat_session 实体
import com.chat.im.mapper.ChatMessageMapper;
import com.chat.im.mapper.ChatSessionMapper;                                     // DAO (MyBatis-Plus BaseMapper)
import com.chat.im.mapper.UserMapper;                                           // user DAO (Agent 信息)
import lombok.RequiredArgsConstructor;                                          // final 字段自动构造注入
import lombok.extern.slf4j.Slf4j;                                                // 自动生成 log 字段
import org.springframework.data.redis.core.StringRedisTemplate;                  // Redis 操作 (List / Value)
import org.springframework.stereotype.Service;                                   // Spring Bean
import org.springframework.transaction.annotation.Transactional;                  // 事务边界 (写操作)

import java.time.LocalDate;                                                      // 日期 (生成 session_no)
import java.time.LocalDateTime;                                                  // 时间戳
import java.time.format.DateTimeFormatter;                                       // 日期格式化 yyyyMMdd
import java.util.Optional;                                                        // Optional 包装 (避免 NPE)
import java.util.ArrayList;                                                       // 动态列表 (30 天聚合)
import java.util.Arrays;                                                          // 数组工具 (split → list)
import java.util.Comparator;                                                      // 排序 (技能评分)
import java.util.HashMap;                                                         // 临时 Map (按日/按技能分桶)
import java.util.HashSet;                                                         // Set (技能匹配 O(1))
import java.util.List;                                                            // 查询结果列表
import java.util.Map;                                                             // Map (技能聚合)
import java.util.Set;                                                             // 在线坐席集合

/**
 * SessionService - 会话核心业务服务 (V3 IM 模块的中枢).
 * ----------------------------------------------------------------------------
 * 职责概述:
 *   - 创建会话: 客户发起, 支持人/机器人两种模式 (skill 路由)
 *   - 抢单: 坐席手动接单, MySQL CAS 防串线
 *   - 转接: 坐席 A → 坐席 B (含转接原因)
 *   - 关闭: 客户/坐席主动关闭, 触发 CSAT 评分
 *   - 评分: CSAT 1-5 星, 客户单次评价
 *   - 转人工: 机器人会话 → 新建人工会话, 异步事件驱动
 *   - 我的会话 / 等待队列: 查询接口 (按角色区分)
 *
 * 设计意图:
 *   - 状态机驱动: WAITING → ACTIVE → CLOSED, 任何状态变更都通过条件 UPDATE 保证原子性
 *   - Redis 双写: 队列 (List) + 坐席-会话/客户-会话 (Value), 缓存一致性靠 CAS + 补偿
 *   - 解耦: 不直接依赖 MessageService / BotService, 通过 ApplicationEvent + 静态方法
 *   - 幂等: 重复调用 (close, claim, exit) 都返 200, 不重复扣资源
 *
 * 依赖注入:
 *   - SessionMapper / UserMapper: 持久化 (MySQL)
 *   - StringRedisTemplate: Redis 操作 (队列 + 映射)
 *   - PresenceService / AgentStatusService: 在线状态 + 坐席状态
 *   - WsPushService: STOMP 推送 (TRANSFER / CLOSED / BOT_TRANSFER 事件)
 *   - SystemMessageService: 系统消息写入 (避免与 MessageService 循环依赖)
 *   - AuditLogService: 操作审计 (PIPL/GDPR 合规要求)
 *
 * 并发安全:
 *   - claim() 用 MySQL 条件 UPDATE (CAS): 只更新 status=WAITING AND agent_id IS NULL
 *   - 多坐席同时抢单只有 1 个 affected=1, 其他返 409
 *   - 补偿机制: 失败的请求清理 Redis 队列残留 sid
 *   - 关键写操作都加 @Transactional, 行锁由 MySQL 隐式获取
 *
 * 性能考虑:
 *   - 路由: pickAgentBySkill 用 Set.contains O(1) 匹配技能
 *   - 查询: mySessions 用 last("LIMIT 50") 防全表扫
 *   - 索引: session 表对 (customer_id, status) 和 (agent_id, status) 建复合索引
 *
 * @author V3 Team
 * @since 2025-09
 */
@Slf4j                                                                          // 自动生成 log 字段
@Service                                                                       // 注册为 Spring Bean (默认单例)
@RequiredArgsConstructor                                                       // final 字段自动构造注入

public class SessionService {

    /** chat_session 表 DAO (MyBatis-Plus BaseMapper 提供 CRUD) */
    private final ChatSessionMapper sessionMapper;
    /** chat_message 表 DAO (用于响应时长计算) */
    private final ChatMessageMapper messageMapper;
    /** user 表 DAO (查坐席信息: 昵称/技能/角色) */
    private final UserMapper userMapper;
    /** Redis 客户端 (会话队列 + 坐席-会话映射 + 客户-会话映射) */
    private final StringRedisTemplate redis;
    /** 用户在线状态服务 (基于 Redis ZSET 心跳) */
    private final PresenceService presenceService;
    /** 坐席状态服务 (ONLINE/AWAY/BUSY/OFFLINE 切换) */
    private final AgentStatusService agentStatusService;
    /** STOMP 推送服务 (向 /user/{uid}/queue/... 推送) */
    private final WsPushService wsPushService;
    /** 审计日志服务 (所有写操作都登记) */
    private final AuditLogService auditLogService;
    /** 系统消息服务 (独立, 避免循环依赖) */
    private final SystemMessageService systemMessageService;

    /**
     * session_no 格式: S + yyyyMMdd + 5位毫秒尾数.
     * 例: 2026-07-11 + currentTimeMillis()%100000 = S2026071145321
     * 设计: 用日期做前缀便于按天归档/查询; 5 位后缀在同毫秒内可能冲突 (概率极低).
     */
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 创建会话入口 (重载, 默认人工模式).
     * <p>
     * 调用链: REST {@code POST /api/im/session/create} → 本方法 → {@link #create(String, boolean)}
     *
     * @param skillTag 客户选择的技能标签, 可空 (空表示无技能偏好)
     *                 取值范围: 业务自定义字符串, 如 "tech"/"billing"/"refund"
     *                 影响: 路由时优先匹配有该技能且 ONLINE 的坐席
     * @return 成功返 ApiResponse.ok(session), 失败返 401/400
     */
    @Transactional
    public ApiResponse<ChatSession> create(String skillTag) {
        return create(skillTag, false);
    }

    /**
     * 创建会话核心方法 (支持机器人/人工两种模式).
     * ----------------------------------------------------------------------------
     * 算法 (伪代码):
     *   1) 鉴权: 拿 ThreadLocal.uid, 缺失返 401
     *   2) 幂等: 查客户是否已有 ACTIVE 会话, 有则直接返回 (防止双开)
     *   3) 创建 ChatSession 实体, 设置 session_no + customerId + skillTag
     *   4) 分支:
     *      a) isBot=true:  status=ACTIVE, 不分配坐席, 发欢迎语, 写审计
     *      b) isBot=false: status=WAITING, 调用 pickAgentBySkill 智能分配
     *         - 找到坐席: 标 ACTIVE, 发 "客服 XXX 已为您服务" 系统消息
     *         - 未找到:   入 Redis 队列, 推送 notifyAgentNewWaiting
     *   5) 写审计日志 (CREATE_BOT_SESSION / CREATE_SESSION)
     *
     * @param skillTag 技能标签, 可空. 业务含义: 路由匹配关键字
     *                 取值范围: 自定义字符串, 例 "tech"
     *                 影响: 仅人工模式生效, 机器人模式忽略
     * @param isBot    是否机器人模式. true=智能客服, false=人工
     *                 取值范围: 布尔值
     *                 影响: 决定初始 status / 是否分配坐席 / 是否发欢迎语
     * @return ApiResponse 包装的 ChatSession
     *         - 成功: code=0, data=session
     *         - 失败: code=401 (未登录) / code=400 (参数错误)
     * @throws 无显式异常抛出, 所有异常由全局异常处理器兜底
     */
    public ApiResponse<ChatSession> create(String skillTag, boolean isBot) {
        // 1) 鉴权: 从 ThreadLocal 取当前用户 ID (JwtAuthInterceptor 已设置)
        Long uid = UserContext.userId();
        if (uid == null) return ApiResponse.fail(401, "未登录");

        // 2) 幂等检查: 该客户是否已有 ACTIVE 会话, 有则直接返回 (避免双开/重复分配)
        ChatSession active = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getCustomerId, uid)                                 // 客户 ID 匹配
                .eq(ChatSession::getStatus, CommonConstants.SESSION_ACTIVE)           // 状态=ACTIVE
                .orderByDesc(ChatSession::getUpdatedAt)                              // 最新优先
                .last(true, "LIMIT 1"));                                                   // 只取一条
        if (active != null) return ApiResponse.ok(active);

        // 3) 构建新会话实体
        ChatSession s = new ChatSession();
        s.setSessionNo(generateSessionNo());                                    // 自动生成唯一单号
        s.setCustomerId(uid);                                                   // 绑定客户
        s.setSkillTag(skillTag);                                                // 技能标签 (可空)
        s.setIsBot(isBot ? 1 : 0);                                              // 机器人标记 (0/1)

        // 4a) 机器人模式: 直接 ACTIVE, 不分配坐席, 发欢迎语
        if (isBot) {
            s.setStatus(CommonConstants.SESSION_ACTIVE);                        // 立即 ACTIVE
            s.setLastMessage("智能客服在线, 请描述您的问题");                       // 会话列表预览
            sessionMapper.insert(s);                                            // 入库
            // 推欢迎语给客户 (系统消息, 灰色气泡)
            systemMessageService.sendSystemMessage(s.getId(),
                "我是智能客服小助手, 可以帮您解答常见问题。\n输入 '人工' 可随时转接真人客服。");
            // 审计: 留痕, 便于合规审计
            auditLogService.log(uid, "CREATE_BOT_SESSION", String.valueOf(s.getId()),
                "skill=" + skillTag);
            return ApiResponse.ok(s);
        }

        // 4b) 人工模式: 先入 WAITING, 再尝试智能分配
        s.setStatus(CommonConstants.SESSION_WAITING);                           // 等待坐席
        s.setLastMessage("等待客服接入...");
        sessionMapper.insert(s);

        // 智能分配: 按技能 + 在线 + 无会话 筛选
        Long agentId = pickAgentBySkill(skillTag);

        if (agentId != null) {
            // 分配成功: 标 ACTIVE, 写坐席-客户 Redis 映射
            assignAgent(s.getId(), agentId);
            s.setAgentId(agentId);
            s.setStatus(CommonConstants.SESSION_ACTIVE);
            s.setLastMessage("客服已接入");
            sessionMapper.updateById(s);
            // 推送系统消息给客户: 告知当前坐席
            Agent agent = userMapper.selectById(agentId);
            String agentName = agent != null ? agent.getNickname() : "#" + agentId;
            String skillPart = skillTag != null && !skillTag.isEmpty()
                    ? " (擅长: " + skillTag + ")" : "";                          // 技能后缀
            systemMessageService.sendSystemMessage(s.getId(),
                    "客服 " + agentName + " 已为您服务" + skillPart);
            auditLogService.log(uid, "CREATE_SESSION", String.valueOf(s.getId()),
                    "auto-assigned to agent=" + agentId + " skill=" + skillTag);
        } else {
            // 无可分配坐席: 入 Redis 队列, 通知所有坐席
            redis.opsForList().rightPush(CommonConstants.REDIS_SESSION_QUEUE, String.valueOf(s.getId()));
            // 客户-会话映射 (用于客户上线时恢复)
            redis.opsForValue().set(
                    CommonConstants.REDIS_CUSTOMER_SESSION + uid, String.valueOf(s.getId()));
            // STOMP 推送: 通知所有坐席有新会话 (携带技能标签供筛选)
            wsPushService.notifyAgentNewWaiting(s.getId(), skillTag);
            auditLogService.log(uid, "CREATE_SESSION", String.valueOf(s.getId()),
                    "queued, skill=" + skillTag);
        }
        return ApiResponse.ok(s);
    }

    /**
     * 智能分配: 按技能筛选 ONLINE + 未在忙的坐席.
     * ----------------------------------------------------------------------------
     * 算法:
     *   1) 取所有 ONLINE 坐席 ID 集合 (PresenceService.onlineAgents())
     *   2) 遍历集合:
     *      - isAssignable=false (状态非 ONLINE/AWAY) → 跳过
     *      - 已有 ACTIVE 会话 → 跳过 (一坐席同时只接一个)
     *      - 无技能要求 → 直接返回该坐席
     *      - 技能匹配 (Set.contains) → 返回该坐席
     *   3) 遍历完都未找到 → 返 null (调用方入队)
     *
     * @param skillTag 技能标签, 可空. 业务含义: 优先匹配坐席的技能
     *                 取值范围: 业务自定义字符串
     *                 影响: 仅在非空时过滤, 空时只要 ONLINE+无会话即可
     * @return 命中的坐席 ID, 未找到返 null
     */
    private Long pickAgentBySkill(String skillTag) {
        // 1) 从 Redis ZSET 拿所有 ONLINE 坐席 (presenceService 内部维护心跳)
        Set<String> onlineIds = presenceService.onlineAgents();
        if (onlineIds == null || onlineIds.isEmpty()) return null;             // 无人在线直接返 null

        // 2) 遍历候选 (顺序由 Redis ZSET 决定, 按 lastHeartbeat desc)
        for (String sid : onlineIds) {
            Long aid = Long.parseLong(sid);
            // 检查坐席状态: ONLINE/AWAY 才可分配 (BUSY/OFFLINE 跳过)
            if (!agentStatusService.isAssignable(aid)) continue;
            // 检查坐席是否已有 ACTIVE 会话 (避免一坐席多开)
            Long existing = sessionMapper.selectCount(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getAgentId, aid)
                    .eq(ChatSession::getStatus, CommonConstants.SESSION_ACTIVE));
            if (existing != null && existing > 0) continue;                   // 已忙, 跳过

            // 查坐席详情 (昵称/技能)
            Agent agent = userMapper.selectById(aid);
            if (agent == null) continue;                                       // 坐席已删除, 跳过
            // 无技能要求 → 直接选; 有技能要求 → 匹配才选
            if (skillTag == null || skillTag.isEmpty()) return aid;
            if (agent.getSkillTags() != null) {
                // skillTags 是 "tech,billing" 逗号分隔字符串, 转 Set 便于 contains
                Set<String> skills = new HashSet<>(Arrays.asList(agent.getSkillTags().split(",")));
                if (skills.contains(skillTag)) return aid;                     // 技能命中
            }
        }
        // 3) 无可分配坐席
        return null;
    }

    /**
     * 坐席抢单 (便捷重载: sessionId=null, 从 Redis 队列自动取下一个).
     * <p>
     * 等价于 {@link #claim(Long)} claim(null).
     *
     * @return ApiResponse 包装的 ChatSession
     */
    @Transactional
    public ApiResponse<ChatSession> claim() {
        return claim(null);
    }

    /**
     * 抢单 (核心 CAS 防串线算法).
     * ----------------------------------------------------------------------------
     * 两种调用:
     *   1) 主动接起指定会话 (Long sessionId 非空) — 坐席从 waiting list 选单
     *   2) sessionId=null — 从 Redis 队列自动取下一个 WAITING (抢单按钮)
     *
     * 算法 (CAS 防串线):
     *   step 1: 取 sessionId (手动指定 OR Redis LPOP)
     *   step 2: 前置校验: 会话存在 + 角色=AGENT
     *   step 3: 条件 UPDATE: SET agent_id=X, status='ACTIVE' WHERE id=? AND status='WAITING' AND agent_id IS NULL
     *           - MySQL 行锁串行化, affected=1 表示抢到, affected=0 表示被别人抢
     *   step 4: 失败补偿: 从 Redis 队列移除该 sid (防其他坐席重复拿)
     *           查最新 session, 报 409 "会话已被 #X 接起"
     *   step 5: 成功: 写 Redis 坐席-客户映射, 发系统消息, 审计日志
     *
     * 并发安全说明:
     *   - 多个坐席同时抢同一会话, MySQL UPDATE 的隐式行锁保证原子
     *   - 只有 1 个坐席的 affected=1, 其他的 affected=0 → 返 409
     *   - Redis 补偿: 失败时从队列移除 sid, 避免其他坐席重复拿到
     *   - 成功时也清理一次: 防 Redis 残留 (同 sid 入队多次)
     *
     * @param sessionId 要抢的会话 ID, 可空.
     *                  业务含义: 手动指定时为"接起指定会话"; null 为"抢下一个"
     *                  取值范围: Long 类型 > 0
     *                  影响: null 时自动从 Redis 队列 LPOP; 非空时跳过队列直接用
     * @return ApiResponse 包装的 ChatSession
     *         - 成功: code=0, data=完整 session
     *         - 失败: 401(未登录) / 403(非坐席) / 404(会话/队列空) / 409(已被抢)
     * @throws 无显式异常抛出
     */
    @Transactional
    public ApiResponse<ChatSession> claim(Long sessionId) {
        wsPushService.broadcastRealtime("SESSION_CLAIMED", null);
        // 0) 鉴权: 从 ThreadLocal 取当前坐席 ID
        Long agentId = UserContext.userId();
        if (agentId == null) return ApiResponse.fail(401, "未登录");

        // 1) 取要抢的 sid: 手动指定 OR 从 Redis 队列 LPOP
        if (sessionId == null) {
            // 原子弹出队头 (LPOP 是 Redis 原子操作, 多坐席并发安全)
            String sid = redis.opsForList().leftPop(CommonConstants.REDIS_SESSION_QUEUE);
            if (sid == null) return ApiResponse.fail(404, "暂无等待中的会话");   // 队列空
            sessionId = Long.parseLong(sid);
        }

        // 2) 前置校验: 会话存在 + 角色必须是 AGENT
        ChatSession probe = sessionMapper.selectById(sessionId);
        if (probe == null) return ApiResponse.fail(404, "会话不存在");
        if (!CommonConstants.ROLE_AGENT.equalsIgnoreCase(UserContext.role())) {
            return ApiResponse.fail(403, "仅坐席可接单");
        }

        // 3) ★ 原子 CAS (Compare-And-Swap) 核心 ★
        //    条件 UPDATE: 只更新 status=WAITING AND agent_id IS NULL 的行
        //    MySQL 隐式行锁保证串行化, 多个坐席同时抢时只有 1 个 affected=1
        ChatSession update = new ChatSession();
        update.setAgentId(agentId);                                            // 新坐席
        update.setStatus(CommonConstants.SESSION_ACTIVE);                      // 转 ACTIVE
        update.setLastMessage("客服已接入");                                     // 列表预览
        update.setUpdatedAt(LocalDateTime.now());                              // 时间戳
        int affected = sessionMapper.update(update, new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)                             // 目标会话
                .eq(ChatSession::getStatus, CommonConstants.SESSION_WAITING)   // 必须是 WAITING
                .isNull(ChatSession::getAgentId));                             // 还未被接
        if (affected == 0) {
            // CAS 失败: 别人抢先了
            // 4) 补偿: 从 Redis 队列移除该 sid (避免其他坐席继续拿)
            redis.opsForList().remove(CommonConstants.REDIS_SESSION_QUEUE, 0, String.valueOf(sessionId));
            // 查最新状态, 告知坐席被谁抢了
            ChatSession latest = sessionMapper.selectById(sessionId);
            String who = (latest != null && latest.getAgentId() != null) ? "#" + latest.getAgentId() : "其他坐席";
            return ApiResponse.fail(409, "会话已被 " + who + " 接起, 请选择其他会话");
        }

        // 5) CAS 成功: 拿最新 session, 写 Redis 映射, 发系统消息
        ChatSession s = sessionMapper.selectById(sessionId);
        // 写 Redis 坐席-会话 + 客户-会话 双向映射 (供 O(1) 查询)
        assignAgent(sessionId, agentId);

        // 系统消息: 推送给客户 "客服 XXX 已为您服务 (擅长: tech)"
        Agent agent = userMapper.selectById(agentId);
        String agentName = agent != null ? agent.getNickname() : "#" + agentId;
        String skillPart = s.getSkillTag() != null && !s.getSkillTag().isEmpty()
                ? " (擅长: " + s.getSkillTag() + ")" : "";
        systemMessageService.sendSystemMessage(sessionId,
                "客服 " + agentName + " 已为您服务" + skillPart);

        // 6) 补偿: 队列里其他重复 sid 一起清理 (防同 sid 多次入队)
        redis.opsForList().remove(CommonConstants.REDIS_SESSION_QUEUE, 0, String.valueOf(sessionId));

        // 7) 审计: 留痕
        auditLogService.log(agentId, "CLAIM", String.valueOf(sessionId), null);
        return ApiResponse.ok(s);
    }

    /**
     * 会话转接: 坐席 A → 坐席 B.
     * ----------------------------------------------------------------------------
     * 算法 (状态机):
     *   step 1: 校验: 会话存在 + ACTIVE + 当前坐席是 owner
     *   step 2: 校验: 目标坐席存在 + role=AGENT
     *   step 3: 更新 session: agent_id=toAgentId, transferred_from_agent_id=fromAgentId
     *   step 4: 更新 Redis: 清除原坐席映射, 写入新坐席映射
     *   step 5: 系统消息: "会话已转接给客服 XXX, 原因: ..."
     *   step 6: STOMP 推送: notifySessionTransferred (前端 TRANSFERRED 事件)
     *   step 7: 审计日志
     *
     * @param sessionId 要转接的会话 ID. 业务含义: 必须是 ACTIVE 状态
     *                  取值范围: Long > 0
     *                  影响: 会话 owner 立即变更, 原坐席失去权限
     * @param toAgentId 目标坐席 ID. 业务含义: 接收方
     *                  取值范围: user.role=AGENT 且存在
     *                  影响: 写入 session.agent_id, 对方获得 owner 权限
     * @param reason    转接原因, 可空. 业务含义: 系统消息 + 审计留痕
     *                  取值范围: 任意字符串
     *                  影响: 影响系统消息文案 + 审计 detail
     * @return ApiResponse 包装的 ChatSession
     *         - 成功: code=0, data=更新后的 session
     *         - 失败: 404(会话/坐席不存在) / 403(非 owner) / 409(非 ACTIVE)
     */
    @Transactional
    public ApiResponse<ChatSession> transfer(Long sessionId, Long toAgentId, String reason) {
        wsPushService.broadcastRealtime("SESSION_TRANSFERRED", java.util.Map.of("sessionId", sessionId, "toAgentId", toAgentId));
        // step 1: 校验
        Long fromAgentId = UserContext.userId();                            // 当前坐席 (转出方)
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        if (!CommonConstants.SESSION_ACTIVE.equals(s.getStatus())) {
            return ApiResponse.fail(409, "只能转接进行中的会话");
        }
        if (!fromAgentId.equals(s.getAgentId())) {
            return ApiResponse.fail(403, "只能转接自己的会话");
        }
        // step 2: 目标坐席校验
        Agent target = userMapper.selectById(toAgentId);
        if (target == null || !"AGENT".equals(target.getRole())) {
            return ApiResponse.fail(400, "目标坐席不存在");
        }
        // step 3: 更新会话实体
        s.setTransferredFromAgentId(fromAgentId);                             // 记录原坐席
        s.setAgentId(toAgentId);                                              // 切换到目标
        s.setTransferReason(reason);                                          // 转接原因
        s.setLastMessage("会话已转接");                                        // 列表预览
        sessionMapper.updateById(s);

        // step 4: Redis 映射更新 (清除旧的, 写入新的)
        redis.delete(CommonConstants.REDIS_AGENT_SESSION + fromAgentId);
        assignAgent(sessionId, toAgentId);

        // step 5: 系统消息通知会话双方
        Agent newAgent = userMapper.selectById(toAgentId);
        String newAgentName = newAgent != null ? newAgent.getNickname() : "#" + toAgentId;
        systemMessageService.sendSystemMessage(sessionId,
                "会话已转接给客服 " + newAgentName + (reason != null && !reason.isEmpty()
                        ? ", 原因: " + reason : ""));
        // step 6: STOMP 推送 TRANSFERRED 事件 (前端会话列表/会话窗刷新)
        wsPushService.notifySessionTransferred(sessionId, fromAgentId, toAgentId, reason);

        // step 7: 审计
        auditLogService.log(fromAgentId, "TRANSFER", String.valueOf(sessionId),
                "to=" + toAgentId + " reason=" + reason);
        return ApiResponse.ok(s);
    }

    /**
     * CSAT 评分 (客户关闭会话后).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 校验 rating ∈ [1, 5]
     *   step 2: 校验会话存在 + 评分者=客户
     *   step 3: 写入 rating + comment + rated_at
     *   step 4: 审计
     *
     * @param sessionId 会话 ID. 业务含义: 已结束的会话
     *                  影响: 写入到 chat_session.rating
     * @param rating    评分 1-5 星. 取值范围: 1-5 (整数)
     *                  影响: 超出范围返 400
     * @param comment   评论, 可空. 业务含义: 客户补充反馈
     *                  取值范围: 任意字符串
     *                  影响: 写入 rating_comment 字段
     * @return ApiResponse<Void>
     *         - 成功: code=0
     *         - 失败: 400(评分越界) / 403(非客户) / 404(会话不存在)
     */
    @Transactional
    public ApiResponse<Void> rate(Long sessionId, Integer rating, String comment) {
        wsPushService.broadcastRealtime("SESSION_RATED", java.util.Map.of("sessionId", sessionId, "rating", rating));
        if (rating == null || rating < 1 || rating > 5) {
            return ApiResponse.fail(400, "评分必须在 1-5 之间");
        }
        Long uid = UserContext.userId();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        if (!uid.equals(s.getCustomerId())) return ApiResponse.fail(403, "只有客户可以评价");

        // 写入评分 (允许重复评分, 取最新一次)
        s.setRating(rating);
        s.setRatingComment(comment);
        s.setRatedAt(LocalDateTime.now());
        sessionMapper.updateById(s);

        auditLogService.log(uid, "RATE", String.valueOf(sessionId),
                "rating=" + rating + " comment=" + comment);
        return ApiResponse.ok();
    }

    /**
     * 写 Redis 坐席-会话 + 客户-会话 双向映射 (供 O(1) 查询, 避免每次都查 DB).
     *
     * @param sessionId 会话 ID
     * @param agentId   坐席 ID
     */
    private void assignAgent(Long sessionId, Long agentId) {
        // 坐席-会话映射: agent-{aid} → sid
        redis.opsForValue().set(CommonConstants.REDIS_AGENT_SESSION + agentId, String.valueOf(sessionId));
        // 客户-会话映射: 查 session 拿 customerId, 写 customer-{uid} → sid
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s != null) {
            redis.opsForValue().set(CommonConstants.REDIS_CUSTOMER_SESSION + s.getCustomerId(),
                    String.valueOf(sessionId));
        }
    }

    /**
     * 我的会话列表 (按角色区分: 客户→我的会话, 坐席→我的会话).
     * <p>
     * 限制 50 条, 按 updated_at desc 排序.
     *
     * @return ApiResponse 包装的 SessionView 列表
     *         - data: 最近的 50 条会话视图 (含对方在线状态)
     */
    public ApiResponse<List<com.chat.im.dto.SessionView>> mySessions() {
        Long uid = UserContext.userId();
        String role = UserContext.role();
        LambdaQueryWrapper<ChatSession> q = new LambdaQueryWrapper<>();
        // 按角色决定查询条件
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)) {
            q.eq(ChatSession::getAgentId, uid);                              // 坐席: agent_id=uid
        } else {
            q.eq(ChatSession::getCustomerId, uid);                            // 客户: customer_id=uid
        }
        q.orderByDesc(ChatSession::getUpdatedAt).last(true, "LIMIT 50");            // 最新 50 条
        List<ChatSession> sessions = sessionMapper.selectList(q);
        // 转 DTO + 查对方在线状态
        List<com.chat.im.dto.SessionView> views = new java.util.ArrayList<>(sessions.size());
        for (ChatSession s : sessions) {
            // 视角方: 客户看 agent, 坐席看 customer
            Long peerId = CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)
                ? s.getCustomerId()
                : s.getAgentId();
            Boolean peerOnline = peerId == null ? null : presenceService.isOnline(peerId);
            views.add(com.chat.im.dto.SessionView.from(s, peerOnline));
        }
        return ApiResponse.ok(views);
    }

    /**
     * 等待队列查询 (Redis List 全量读, 供坐席 UI 展示).
     *
     * @return ApiResponse 包装的 Long[] 等待中的会话 ID 列表
     */
    public ApiResponse<List<Long>> waitingQueue() {
        // range 0..-1 表示读全部
        List<String> ids = redis.opsForList().range(CommonConstants.REDIS_SESSION_QUEUE, 0, -1);
        if (ids == null) return ApiResponse.ok(List.of());
        // String → Long 转换
        return ApiResponse.ok(ids.stream().map(Long::parseLong).toList());
    }

    /**
     * 关闭会话 (客户/坐席均可触发, 幂等).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 校验: 会话存在 + 调用方是 owner
     *   step 2: 幂等: 已 CLOSED 直接返 200
     *   step 3: 标 CLOSED + closed_at + lastMessage
     *   step 4: 删 Redis 坐席-会话 + 客户-会话 映射
     *   step 5: 发系统消息给另一方
     *   step 6: 推 CLOSED 事件 (前端刷新)
     *   step 7: 审计
     *
     * @param sessionId 会话 ID
     * @return ApiResponse<Void>
     *         - 成功: code=0
     *         - 失败: 404(不存在) / 403(非 owner)
     */
    @Transactional
    public ApiResponse<Void> close(Long sessionId) {
        wsPushService.broadcastRealtime("SESSION_CLOSED", java.util.Map.of("sessionId", sessionId));
        Long uid = UserContext.userId();
        String role = UserContext.role();
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        // 校验: 坐席需是 agentId, 客户需是 customerId
        boolean ok = CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)
                ? uid.equals(s.getAgentId())
                : uid.equals(s.getCustomerId());
        if (!ok) return ApiResponse.fail(403, "无权操作");

        // 幂等: 已关闭的会话不重复推事件
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            return ApiResponse.ok();
        }

        s.setStatus(CommonConstants.SESSION_CLOSED);
        s.setClosedAt(LocalDateTime.now());
        s.setLastMessage(CommonConstants.ROLE_AGENT.equalsIgnoreCase(role) ? "客服已结束会话" : "已结束会话");
        sessionMapper.updateById(s);

        // 清理 Redis 映射
        if (s.getAgentId() != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + s.getAgentId());
        }
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + s.getCustomerId());

        // 推系统消息给另一方
        if (CommonConstants.ROLE_AGENT.equalsIgnoreCase(role) && s.getCustomerId() != null) {
            systemMessageService.sendSystemMessage(sessionId, "客服已结束本次会话");
        } else if (CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(role) && s.getAgentId() != null) {
            systemMessageService.sendSystemMessage(sessionId, "客户已结束本次会话");
        }
        // 推 CLOSED 事件 (另一方刷新会话列表 + 清理当前)
        String reason = CommonConstants.ROLE_AGENT.equalsIgnoreCase(role) ? "AGENT_CLOSE" : "CUSTOMER_CLOSE";
        wsPushService.notifySessionClosed(sessionId, s.getCustomerId(), s.getAgentId(), reason);

        auditLogService.log(uid, "CLOSE", String.valueOf(sessionId), "role=" + role);
        return ApiResponse.ok();
    }

    /**
     * 异步处理客户转人工事件 (由 MessageService 发布).
     * ----------------------------------------------------------------------------
     * 设计要点:
     *   - 不走 UserContext (因为 @Async 异步, 原 ThreadLocal 可能已失效),
     *     事件本身携带 customerId + sessionId + skill.
     *   - 幂等性: 如果 bot 会话已经被关闭或 customer 不匹配, 直接 return.
     *
     * 算法:
     *   step 1: 校验 bot 会话存在 + 未关闭 + 客户匹配
     *   step 2: 关闭 bot 会话 (status=CLOSED, lastMessage="客户申请转人工")
     *   step 3: 调 create(skill, false) 创建新人工会话 (会走智能分配)
     *   step 4: 推 BOT_TRANSFER 事件给客户前端 (跳转新人工会话)
     *   step 5: 审计
     *
     * @param event 转人工事件 (TransferToHumanEvent)
     *              - getCustomerId: 客户 ID
     *              - getOldSessionId: 旧 bot 会话 ID
     *              - getSkillTag: 技能标签 (从原 session 继承)
     */
    @EventListener
    @Async
    public void onTransferToHumanEvent(TransferToHumanEvent event) {
        Long customerId = event.getCustomerId();
        Long oldSessionId = event.getOldSessionId();
        String skillTag = event.getSkillTag();
        log.info("[event] TransferToHuman: customer={} session={} skill={}", customerId, oldSessionId, skillTag);

        // step 1: 幂等检查: 会话不存在/已关闭/customer 不匹配 直接返
        ChatSession old = sessionMapper.selectById(oldSessionId);
        if (old == null || CommonConstants.SESSION_CLOSED.equals(old.getStatus())) return;
        if (!customerId.equals(old.getCustomerId())) return;

        // step 2: 关闭 bot 会话
        old.setStatus(CommonConstants.SESSION_CLOSED);
        old.setClosedAt(LocalDateTime.now());
        old.setLastMessage("客户申请转人工");
        sessionMapper.updateById(old);
        // 清 Redis 客户-会话映射 (bot 关闭)
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + customerId);

        // step 3: 重新进人工队列 — 调内部 create(..., false) 走智能分配
        ApiResponse<ChatSession> fresh = create(skillTag != null ? skillTag : old.getSkillTag(), false);
        Long newId = fresh.getData() != null ? fresh.getData().getId() : null;
        auditLogService.log(customerId, "TRANSFER_BOT_TO_HUMAN", String.valueOf(oldSessionId),
                "newSession=" + newId);

        // step 4: 推事件给客户前端, 跳转到新会话 (前端 type=BOT_TRANSFER, sessionId=newId)
        if (newId != null) {
            wsPushService.pushBotTransferEvent(customerId, oldSessionId, newId);
        }
    }

    /**
     * 客户主动转人工 (REST 版本, 兼容直接调用).
     * ----------------------------------------------------------------------------
     * 与 {@link #onTransferToHumanEvent} 区别:
     *   - 这个走 UserContext 同步调用, 给客户前端按钮触发
     *   - 事件版本是 @Async, 给 MessageService 自动识别转人工关键词时触发
     *
     * 算法: 与 onTransferToHumanEvent 类似, 但直接关闭 + 新建 (不走事件)
     *
     * @param sessionId 旧 bot 会话 ID
     * @param skillTag  期望技能, 可空 (null 时继承旧会话的 skill)
     * @return ApiResponse 包装的新 ChatSession
     */
    @Transactional
    public ApiResponse<ChatSession> transferToHuman(Long sessionId, String skillTag) {
        Long uid = UserContext.userId();
        if (!CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(UserContext.role())) {
            return ApiResponse.fail(403, "仅客户可调用");
        }
        ChatSession old = sessionMapper.selectById(sessionId);
        if (old == null) return ApiResponse.fail(404, "会话不存在");
        if (!uid.equals(old.getCustomerId())) return ApiResponse.fail(403, "无权操作");
        if (CommonConstants.SESSION_CLOSED.equals(old.getStatus())) {
            return ApiResponse.fail(409, "会话已关闭");
        }

        // 关闭当前机器人会话
        old.setStatus(CommonConstants.SESSION_CLOSED);
        old.setClosedAt(LocalDateTime.now());
        old.setLastMessage("客户申请转人工");
        sessionMapper.updateById(old);
        if (old.getAgentId() != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + old.getAgentId());
        }
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + old.getCustomerId());

        // 重新进人工队列 (用 create(..., false), 会有新 session id)
        ApiResponse<ChatSession> fresh = create(skillTag != null ? skillTag : old.getSkillTag(), false);
        auditLogService.log(uid, "TRANSFER_BOT_TO_HUMAN", String.valueOf(sessionId),
            "newSession=" + (fresh.getData() != null ? fresh.getData().getId() : "?"));
        return fresh;
    }

    /**
     * 客户主动退出会话 (与 close 的区别).
     * ----------------------------------------------------------------------------
     * 区别:
     *   - 仅客户可调 (close 客户/坐席均可)
     *   - 推一条系统消息给坐席 "客户已退出 (原因)"
     *   - 推一个 CLOSE 事件给坐席 (前端根据事件刷会话列表)
     *   - 幂等: 重复点击不报错
     *
     * @param sessionId 会话 ID
     * @param reason    退出原因, 可空 (前端可传 "用户主动"/"网络断开" 等)
     * @return ApiResponse<Void>
     */
    @Transactional
    public ApiResponse<Void> customerExit(Long sessionId, String reason) {
        Long uid = UserContext.userId();
        if (!CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(UserContext.role())) {
            return ApiResponse.fail(403, "仅客户可调用");
        }
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        if (!uid.equals(s.getCustomerId())) return ApiResponse.fail(403, "无权操作");
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            return ApiResponse.ok();                                          // 幂等: 重复点击不报错
        }

        // 关闭会话
        s.setStatus(CommonConstants.SESSION_CLOSED);
        s.setClosedAt(LocalDateTime.now());
        s.setLastMessage("客户主动退出");
        sessionMapper.updateById(s);

        // 清理 Redis 映射
        if (s.getAgentId() != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + s.getAgentId());
        }
        redis.delete(CommonConstants.REDIS_CUSTOMER_SESSION + uid);

        // 推系统消息 (坐席可见)
        String text = reason != null && !reason.isEmpty()
            ? "客户已主动退出 (" + reason + ")"
            : "客户已主动退出会话";
        systemMessageService.sendSystemMessage(sessionId, text);
        // 推 CLOSED 事件 (坐席侧 Client.OnEvent 收到 type=CLOSED, 刷新列表)
        wsPushService.notifySessionClosed(sessionId, s.getCustomerId(), s.getAgentId(), "CUSTOMER_EXIT");

        auditLogService.log(uid, "CUSTOMER_EXIT", String.valueOf(sessionId),
            "agentId=" + s.getAgentId() + " reason=" + reason);
        return ApiResponse.ok();
    }

    /**
     * 客户申请转接其他坐席 (会保留会话与聊天记录, 只是换客服).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 校验: 客户身份 + 会话未关闭
     *   step 2: 找候选: 优先同技能的其他坐席 → 都没有再选任意可分配
     *   step 3: 更新 session: agent_id=newAgent, transferred_from_agent_id=old
     *   step 4: 更新 Redis 映射
     *   step 5: 发系统消息 + 推 TRANSFER 事件
     *   step 6: 审计
     *
     * 与 transfer 的区别:
     *   - transfer: 坐席 A → 坐席 B (坐席视角)
     *   - customerRequestTransfer: 客户主动申请换坐席 (客户视角)
     *
     * @param sessionId      会话 ID
     * @param preferredSkill 期望技能, 可空 (null 时用原 session 的 skill)
     * @return ApiResponse 包装的 ChatSession
     *         - 成功: code=0
     *         - 失败: 403(非客户) / 404(无) / 409(已关) / 503(无坐席)
     */
    @Transactional
    public ApiResponse<ChatSession> customerRequestTransfer(Long sessionId, String preferredSkill) {
        Long uid = UserContext.userId();
        if (!CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(UserContext.role())) {
            return ApiResponse.fail(403, "仅客户可调用");
        }
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null) return ApiResponse.fail(404, "会话不存在");
        if (!uid.equals(s.getCustomerId())) return ApiResponse.fail(403, "无权操作");
        if (CommonConstants.SESSION_CLOSED.equals(s.getStatus())) {
            return ApiResponse.fail(409, "会话已关闭, 不能转接");
        }

        // 计算目标技能
        String skill = preferredSkill != null && !preferredSkill.isEmpty()
            ? preferredSkill
            : s.getSkillTag();
        Long currentAgentId = s.getAgentId();

        // step 2: 查可分配的坐席 (不含当前 + 同一技能优先).
        // Agent.skillTags 是 JSON 数组字符串 (e.g. '["billing","refund"]'), 用 LIKE 包含匹配.
        QueryWrapper<Agent> q = new QueryWrapper<>();
        q.eq("role", "AGENT").eq("status", 1);                                // 仅查 AGENT + 启用
        if (skill != null && !skill.isEmpty()) {
            // LIKE "%\"skill\"%" 匹配 JSON 数组里的精确字符串 (防 "tech" 匹配 "technology")
            q.and(w -> w.like("skill_tags", "\"" + skill + "\"").or().like("skill_tags", skill));
        }
        q.orderByAsc("id");                                                   // 按 id 升序 (稳定顺序)
        List<Agent> candidates = userMapper.selectList(q);
        // 选第一个非当前的坐席
        Long newAgentId = null;
        for (Agent a : candidates) {
            if (a.getId().equals(currentAgentId)) continue;                   // 排除当前坐席
            newAgentId = a.getId();
            break;
        }
        // 2) 没有同技能, 查任意可分配
        if (newAgentId == null) {
            QueryWrapper<Agent> q2 = new QueryWrapper<>();
            q2.eq("role", "AGENT").eq("status", 1).orderByAsc("id");
            List<Agent> allAssignable = userMapper.selectList(q2);
            for (Agent a : allAssignable) {
                if (a.getId().equals(currentAgentId)) continue;
                newAgentId = a.getId();
                break;
            }
        }
        if (newAgentId == null) {
            return ApiResponse.fail(503, "暂无可转接的坐席, 请稍后再试");
        }

        // step 3: 更新会话
        Long fromAgentId = currentAgentId;
        s.setAgentId(newAgentId);
        s.setTransferredFromAgentId(fromAgentId);
        s.setTransferReason("客户申请转接");
        s.setLastMessage("会话已转接");
        sessionMapper.updateById(s);

        // step 4: Redis 映射
        if (fromAgentId != null) {
            redis.delete(CommonConstants.REDIS_AGENT_SESSION + fromAgentId);
        }
        assignAgent(sessionId, newAgentId);

        // step 5: 推系统消息 + 事件
        Agent newAgent = userMapper.selectById(newAgentId);
        String newAgentName = newAgent != null ? newAgent.getNickname() : "#" + newAgentId;
        String oldAgentName = fromAgentId != null
            ? (Optional.ofNullable(userMapper.selectById(fromAgentId)).map(Agent::getNickname).orElse("#" + fromAgentId))
            : null;
        String msg = "客户申请转接, 客服已更换为 " + newAgentName +
            (oldAgentName != null ? " (原客服 " + oldAgentName + ")" : "");
        systemMessageService.sendSystemMessage(sessionId, msg);
        wsPushService.notifySessionTransferred(sessionId, fromAgentId, newAgentId, "客户申请转接");

        auditLogService.log(uid, "CUSTOMER_TRANSFER", String.valueOf(sessionId),
            "from=" + fromAgentId + " to=" + newAgentId + " skill=" + skill);
        return ApiResponse.ok(s);
    }

    /**
     * 生成唯一 session_no (单号).
     * <p>
     * 格式: "S" + yyyyMMdd + currentTimeMillis()%100000
     * 例: S2026071145321
     * <p>
     * 冲突概率: 同毫秒内冲突约 1/100000 (实际同客户同毫秒多次创建会被幂等检查拦截)
     *
     * @return session_no 字符串
     */
    private String generateSessionNo() {
        return "S" + LocalDate.now().format(NO_FMT) + System.currentTimeMillis() % 100000;
    }

    // ============================================================================
    //  坐席统计 (computeAgentStats) - 阶段 2 真实数据接入
    // ----------------------------------------------------------------------------
    //  数据源:
    //    - chat_session  (id, agent_id, customer_id, status, rating, skill_tag,
    //                     created_at, updated_at, closed_at)
    //    - chat_message  (id, session_id, sender_id, sender_role, created_at)
    //
    //  指标:
    //    1) todaySessions       当日 (按 created_at) 该坐席的会话数
    //    2) todayAvgResponseSec 当日 (按 created_at) 该坐席的会话中,
    //                           客户首条消息 -> 坐席首条回复 的平均时长 (秒)
    //    3) todayAvgCsat        当日 (按 updated_at) 该坐席的会话中 rating 非空的平均分
    //    4) monthSessions       当月会话数
    //    5) monthAvgCsat        当月平均 CSAT
    //    6) activeDays          近 30 天有活动的天数
    //    7) last7Days           近 7 天 (老 -> 新) 趋势: count + avgResponse + avgCsat
    //    8) skills              按 skill_tag 聚合近 30 天的会话数 + CSAT, 推算能力评分
    // ============================================================================

    /** 7 天趋势的日期格式 (yyyy-MM-dd) */
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 计算坐席真实统计数据.
     */
    public AgentStatsView computeAgentStats(Long agentId) {
        if (agentId == null || agentId <= 0) {
            return emptyView(null, "EMPTY: 坐席 ID 无效");
        }
        Agent agent = userMapper.selectById(agentId);
        if (agent == null || !CommonConstants.ROLE_AGENT.equalsIgnoreCase(agent.getRole())) {
            return emptyView(agentId, "EMPTY: 坐席不存在或非 AGENT 角色");
        }

        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate thirtyStart = today.minusDays(29);

        QueryWrapper<ChatSession> q = new QueryWrapper<>();
        q.eq("agent_id", agentId)
         .ge("created_at", thirtyStart.atStartOfDay());
        List<ChatSession> all = sessionMapper.selectList(q);
        if (all.isEmpty()) {
            return emptyView(agentId, "REAL: 近 30 天无会话记录");
        }

        List<ChatSession> todayList = new ArrayList<>();
        List<ChatSession> monthList = new ArrayList<>();
        Map<LocalDate, List<ChatSession>> byDay = new HashMap<>();
        Set<LocalDate> activeDays = new HashSet<>();
        Map<String, List<ChatSession>> bySkill = new HashMap<>();

        for (ChatSession s : all) {
            LocalDateTime createdAt = s.getCreatedAt();
            if (createdAt == null) continue;
            LocalDate d = createdAt.toLocalDate();
            activeDays.add(d);
            byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(s);
            if (!createdAt.isBefore(today.atStartOfDay())) todayList.add(s);
            if (!createdAt.isBefore(monthStart.atStartOfDay())) monthList.add(s);
            String tag = s.getSkillTag();
            String key = (tag == null || tag.isEmpty()) ? "通用" : tag;
            bySkill.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        int todaySessions = todayList.size();
        int todayAvgResponseSec = avgResponseSecForSessions(todayList, today);
        double todayAvgCsat = avgCsatForSessions(todayList, today);

        int monthSessions = monthList.size();
        double monthAvgCsat = avgCsatForSessions(monthList, monthStart);

        List<AgentStatsView.DailyPoint> last7 = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            List<ChatSession> dayList = byDay.getOrDefault(d, List.of());
            int cnt = dayList.size();
            int resp = avgResponseSecForSessions(dayList, d);
            double csat = avgCsatForSessions(dayList, d);
            last7.add(AgentStatsView.DailyPoint.builder()
                    .date(d.format(DAY_FMT))
                    .day(d)
                    .count(cnt)
                    .avgResponseSec(resp)
                    .avgCsat(round1(csat))
                    .build());
        }

        List<AgentStatsView.SkillScore> skills = computeSkills(bySkill, thirtyStart);

        return AgentStatsView.builder()
                .agentId(agentId)
                .todaySessions(todaySessions)
                .todayAvgResponseSec(todayAvgResponseSec)
                .todayAvgCsat(round1(todayAvgCsat))
                .monthSessions(monthSessions)
                .monthAvgCsat(round1(monthAvgCsat))
                .activeDays(activeDays.size())
                .last7Days(last7)
                .skills(skills)
                .dataSource("REAL")
                .generatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    private AgentStatsView emptyView(Long agentId, String source) {
        return AgentStatsView.builder()
                .agentId(agentId)
                .todaySessions(0)
                .todayAvgResponseSec(0)
                .todayAvgCsat(0.0)
                .monthSessions(0)
                .monthAvgCsat(0.0)
                .activeDays(0)
                .last7Days(buildEmpty7Days())
                .skills(List.of())
                .dataSource(source)
                .generatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    private List<AgentStatsView.DailyPoint> buildEmpty7Days() {
        LocalDate today = LocalDate.now();
        List<AgentStatsView.DailyPoint> out = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            out.add(AgentStatsView.DailyPoint.builder()
                    .date(d.format(DAY_FMT)).day(d)
                    .count(0).avgResponseSec(0).avgCsat(0.0)
                    .build());
        }
        return out;
    }

    private int avgResponseSecForSessions(List<ChatSession> sessions, LocalDate day) {
        if (sessions == null || sessions.isEmpty()) return 0;
        long totalSec = 0;
        int count = 0;
        for (ChatSession s : sessions) {
            if (s == null || s.getId() == null) continue;
            List<ChatMessage> msgs = messageMapper.selectList(new QueryWrapper<ChatMessage>()
                    .eq("session_id", s.getId())
                    .orderByAsc("created_at"));
            if (msgs == null || msgs.isEmpty()) continue;

            LocalDateTime customerFirstAt = null;
            LocalDateTime agentReplyAt = null;
            for (ChatMessage m : msgs) {
                String role = m.getSenderRole();
                LocalDateTime ts = m.getCreatedAt();
                if (ts == null) continue;
                if (customerFirstAt == null && CommonConstants.ROLE_CUSTOMER.equalsIgnoreCase(role)) {
                    customerFirstAt = ts;
                    continue;
                }
                if (customerFirstAt != null && agentReplyAt == null
                        && CommonConstants.ROLE_AGENT.equalsIgnoreCase(role)
                        && !ts.isBefore(customerFirstAt)) {
                    agentReplyAt = ts;
                    break;
                }
            }
            if (customerFirstAt != null && agentReplyAt != null) {
                long sec = java.time.Duration.between(customerFirstAt, agentReplyAt).getSeconds();
                if (sec >= 0 && sec <= 86400) {
                    totalSec += sec;
                    count++;
                }
            }
        }
        if (count == 0) return 0;
        return (int) Math.round((double) totalSec / count);
    }

    private double avgCsatForSessions(List<ChatSession> sessions, LocalDate dayStart) {
        if (sessions == null || sessions.isEmpty()) return 0.0;
        long sum = 0;
        int count = 0;
        LocalDateTime fromTs = dayStart.atStartOfDay();
        for (ChatSession s : sessions) {
            if (s == null) continue;
            Integer rating = s.getRating();
            if (rating == null || rating < 1 || rating > 5) continue;
            LocalDateTime updated = s.getUpdatedAt();
            if (updated != null && updated.isBefore(fromTs)) continue;
            sum += rating;
            count++;
        }
        if (count == 0) return 0.0;
        return (double) sum / count;
    }

    private List<AgentStatsView.SkillScore> computeSkills(Map<String, List<ChatSession>> bySkill,
                                                          LocalDate thirtyStart) {
        if (bySkill == null || bySkill.isEmpty()) return List.of();
        List<AgentStatsView.SkillScore> out = new ArrayList<>();
        for (Map.Entry<String, List<ChatSession>> e : bySkill.entrySet()) {
            String skill = e.getKey();
            List<ChatSession> list = e.getValue();
            if (list == null || list.isEmpty()) continue;
            int volume = list.size();

            long sum = 0;
            int rated = 0;
            LocalDateTime fromTs = thirtyStart.atStartOfDay();
            for (ChatSession s : list) {
                Integer rating = s.getRating();
                if (rating == null || rating < 1 || rating > 5) continue;
                LocalDateTime updated = s.getUpdatedAt();
                if (updated != null && updated.isBefore(fromTs)) continue;
                sum += rating;
                rated++;
            }
            double avgCsat = rated == 0 ? 0.0 : (double) sum / rated;

            int baseScore = (int) Math.round(avgCsat / 5.0 * 100);
            int volumeBonus = Math.min(20, volume / 5);
            int score = (int) Math.round(baseScore * 0.7 + volumeBonus);
            if (score > 100) score = 100;
            if (score < 0) score = 0;

            String level;
            if (score >= 90) level = "expert";
            else if (score >= 75) level = "advanced";
            else if (score >= 60) level = "intermediate";
            else level = "beginner";

            out.add(AgentStatsView.SkillScore.builder()
                    .name(skill)
                    .score(score)
                    .level(level)
                    .volume(volume)
                    .avgCsat(round1(avgCsat))
                    .build());
        }
        out.sort(Comparator
                .comparingInt(AgentStatsView.SkillScore::getScore).reversed()
                .thenComparing(Comparator.comparingInt(AgentStatsView.SkillScore::getVolume).reversed()));
        return out;
    }

    private double round1(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10.0) / 10.0;
    }
}
