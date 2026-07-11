package com.chat.prediction.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;             // MP QueryWrapper
import com.chat.common.constant.CommonConstants;                              // 公共常量 (Redis channel)
import com.chat.prediction.entity.PredictionEvent;                            // 事件实体 (触发的实例)
import com.chat.prediction.entity.PredictionRule;                             // 规则实体
import com.chat.prediction.mapper.PredictionEventMapper;                      // 事件 DAO
import com.chat.prediction.mapper.PredictionRuleMapper;                       // 规则 DAO
import com.fasterxml.jackson.databind.ObjectMapper;                            // JSON 序列化
import lombok.RequiredArgsConstructor;                                        // final 字段注入
import lombok.extern.slf4j.Slf4j;                                              // 日志
import org.springframework.data.redis.core.StringRedisTemplate;                // Redis Pub/Sub
import org.springframework.stereotype.Service;                                 // Spring Bean
import org.springframework.transaction.annotation.Transactional;                // 事务

import java.time.LocalDate;                                                     // 日期 (天)
import java.time.LocalDateTime;                                                 // 时间戳
import java.util.HashMap;                                                       // payload 容器
import java.util.List;                                                          // 规则列表
import java.util.Map;                                                           // 上下文 (规则入参)

/**
 * PredictionService - 预见式服务核心 (主动推送).
 * ----------------------------------------------------------------------------
 * 职责:
 *   - evaluate(userId, eventType, context): 评估某事件是否触发规则
 *   - 命中规则 -> 写 prediction_event + 推 STOMP /user/{uid}/queue/events
 *   - 防刷: 同一用户同一规则一天内只触发 1 次
 *   - 模板替换: ${var} 从 context 替换
 *
 * 阶段 1: 同步评估 (后续可改异步, 当前用同步保证事务性).
 *
 * 关键决策:
 *   - 推送通道: 用 Redis Pub/Sub 而非 STOMP (cs-prediction 无 @MessageMapping handler, 避免 'No handlers' 启动错误)
 *   - 跨实例: Redis pub/sub 支持, cs-im 订阅后用 SimpMessagingTemplate 推 STOMP
 *   - 防刷: 数据库 count (user, rule, today) > 0 → 跳过
 *
 * @author V3 Team
 * @since 2025-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    /** 规则 DAO */
    private final PredictionRuleMapper ruleMapper;
    /** 事件 DAO (触发历史) */
    private final PredictionEventMapper eventMapper;
    /** 规则引擎 (evaluate 规则表达式 + context) */
    private final RuleEngine ruleEngine;
    /** Redis 客户端 (用于 Pub/Sub 推送) */
    // SimpMessagingTemplate 弃用: 改用 Redis pub/sub 让 cs-im 转发
    private final StringRedisTemplate redis;
    /** JSON 序列化 (context + payload 转 JSON 存 DB) */
    private final ObjectMapper mapper;

    /**
     * 评估事件是否触发规则 (核心方法, 规则引擎入口).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 查所有 enabled 规则 (trigger_event=eventType, 按 priority asc)
     *   step 2: 遍历规则:
     *     a) 防刷检查: 同一规则今天是否已触发 (count > 0 → skip)
     *     b) 规则评估: ruleEngine.evaluate(rule, context) (失败 → skip)
     *     c) 命中: 创建 PredictionEvent, 写 DB
     *     d) 模板替换: renderTemplate(actionTemplate, context)
     *     e) 推 Redis Pub/Sub (cs-im 订阅后转 STOMP)
     *   step 3: 返回所有触发的 PredictionEvent 列表
     *
     * @param userId    触发事件的用户 ID. 业务含义: 推送目标
     *                  取值范围: Long > 0
     *                  影响: 决定推送给谁 + 防刷 user
     * @param eventType 事件类型. 业务含义: 触发条件 (e.g. "ORDER_PAID"/"LOGIN"/"HIGH_CSAT")
     *                  取值范围: 业务自定义字符串, 需与 prediction_rule.trigger_event 匹配
     *                  影响: 决定评估哪些规则
     * @param context   事件上下文. 业务含义: 规则评估的入参 + 模板替换的变量
     *                  取值范围: Map<String, Object>
     *                  影响: 决定规则是否命中 + 替换模板中的 ${var}
     * @return 触发的 PredictionEvent 列表 (一般 0-1 个, 多个规则时多个)
     *         - 空列表: 没规则命中或全部防刷
     *         - 非空: 命中的事件, 含 actionPayload
     */
    @Transactional
    public List<PredictionEvent> evaluate(Long userId, String eventType, Map<String, Object> context) {
        // step 1: 查所有 enabled 且匹配 eventType 的规则, 按 priority 升序
        List<PredictionRule> rules = ruleMapper.selectList(new QueryWrapper<PredictionRule>()
                .eq("trigger_event", eventType)                                // 事件类型匹配
                .eq("enabled", 1)                                              // 仅查启用的
                .orderByAsc("priority"));                                      // 优先级低的先评估

        if (rules.isEmpty()) {
            return List.of();                                                 // 无规则直接返
        }

        // 收集触发的结果
        List<PredictionEvent> triggered = new java.util.ArrayList<>();
        // 防刷时间窗: 今天 0 点 (LocalDate.now() atStartOfDay)
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();

        // step 2: 逐条评估
        for (PredictionRule rule : rules) {
            try {
                // 2a) 防刷: 同一 user + rule 今天的触发次数
                long alreadyCount = eventMapper.countTodayByUserAndRule(userId, rule.getRuleCode(), dayStart);
                if (alreadyCount > 0) {
                    // 今天已触发过, 跳过 (防骚扰)
                    log.debug("[prediction] skip (already fired today): user={} rule={}", userId, rule.getRuleCode());
                    continue;
                }

                // 2b) 规则评估: 用 ruleEngine 判断 context 是否满足规则的 when 条件
                if (!ruleEngine.evaluate(rule, context)) {
                    // 条件不满足, 跳过
                    log.debug("[prediction] condition not met: user={} rule={}", userId, rule.getRuleCode());
                    continue;
                }

                // 2c) 命中! 创建事件实体
                PredictionEvent ev = new PredictionEvent();
                ev.setUserId(userId);
                ev.setRuleCode(rule.getRuleCode());
                ev.setStatus("SENT");                                          // 已发送
                ev.setTriggerContext(mapper.writeValueAsString(context));      // 上下文存 JSON
                ev.setCreatedAt(LocalDateTime.now());
                ev.setSentAt(LocalDateTime.now());

                // 2d) 模板替换: actionTemplate 含 ${var}, 用 context 替换
                String text = renderTemplate(rule.getActionTemplate(), context);
                // 2e) 构造 STOMP payload (前端按 type 字段分发)
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "PREDICTION");                            // 前端识别类型
                payload.put("ruleCode", rule.getRuleCode());
                payload.put("ruleName", rule.getRuleName());
                payload.put("actionType", rule.getActionType());
                payload.put("text", text);                                    // 模板替换后的文案
                payload.put("ts", System.currentTimeMillis());                // 时间戳
                ev.setActionPayload(mapper.writeValueAsString(payload));     // payload 存 JSON

                // 写 DB (事件历史, 供审计 + 防刷查询)
                eventMapper.insert(ev);
                triggered.add(ev);

                // 2f) 推 Redis Pub/Sub (cs-im 订阅后用 SimpMessagingTemplate 推 STOMP)
                //     跨实例 + 避免 STOMP 'No handlers' 错误 (cs-prediction 没有 @MessageMapping handler)
                try {
                    // channel 格式: ws:push:{userId} (cs-im 订阅这个 pattern)
                    String channel = CommonConstants.REDIS_WS_PUSH_CHANNEL + userId;
                    String json = mapper.writeValueAsString(payload);
                    redis.convertAndSend(channel, json);
                    log.info("[prediction] fired: user={} rule={} text={}",
                            userId, rule.getRuleCode(), text);
                } catch (Exception e) {
                    // 推送失败不影响事件记录, 仅 log
                    log.error("[prediction] redis push failed: user={} rule={}",
                            userId, rule.getRuleCode(), e);
                }
            } catch (Exception e) {
                // 单条规则异常不影响其他规则
                log.error("[prediction] eval failed: rule={}", rule.getRuleCode(), e);
            }
        }
        return triggered;
    }

    /**
     * 模板渲染: ${var} 替换 (内部用).
     * <p>
     * 算法: 遍历 context 的 entry, 对每个 key 找 ${key} 占位符替换为 value.
     * <p>
     * 限制: 简单字符串替换, 不支持嵌套/表达式. 后续可换 Thymeleaf/Freemarker.
     *
     * @param template 模板字符串. 业务含义: 含 ${var} 占位符
     *                 取值范围: 任意字符串, 可不含占位符
     *                 影响: 占位符被 context 替换
     * @param ctx      替换变量. 业务含义: var → value
     *                 取值范围: Map<String, Object> (value 调 String.valueOf)
     *                 影响: 决定替换后的内容
     * @return 替换后的字符串
     *         - template 为空 → 返 ""
     *         - ctx 为空 → 返原 template
     *         - 正常 → 替换后字符串
     */
    private String renderTemplate(String template, Map<String, Object> ctx) {
        // 边界: 空模板
        if (template == null || template.isEmpty()) return "";
        String result = template;
        // 遍历 context 替换占位符
        if (ctx != null) {
            for (Map.Entry<String, Object> e : ctx.entrySet()) {
                // 占位符格式: ${key}
                String placeholder = "${" + e.getKey() + "}";
                if (result.contains(placeholder)) {
                    // 替换 (value 转 String, null → "null")
                    result = result.replace(placeholder, String.valueOf(e.getValue()));
                }
            }
        }
        return result;
    }
}
