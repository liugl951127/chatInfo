package com.chat.prediction.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chat.prediction.entity.PredictionEvent;
import com.chat.prediction.entity.PredictionRule;
import com.chat.prediction.mapper.PredictionEventMapper;
import com.chat.prediction.mapper.PredictionRuleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PredictionService - 预见式服务核心.
 * ----------------------------------------------------------------------------
 * 职责:
 *   - evaluate(userId, eventType, context): 评估某事件是否触发规则
 *   - 命中规则 -> 写 prediction_event + 推 STOMP /user/{uid}/queue/events
 *   - 防刷: 同一用户同一规则一天内只触发 1 次
 *   - 模板替换: ${var} 从 context 替换
 *
 * 阶段 1: 同步评估 (后续可改异步).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    private final PredictionRuleMapper ruleMapper;
    private final PredictionEventMapper eventMapper;
    private final RuleEngine ruleEngine;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /**
     * 评估事件是否触发规则.
     * @return 触发的 PredictionEvent 列表 (一般 0-1 个)
     */
    @Transactional
    public List<PredictionEvent> evaluate(Long userId, String eventType, Map<String, Object> context) {
        // 1) 查 enabled 规则 (按 trigger_event 匹配, 按 priority 升序)
        List<PredictionRule> rules = ruleMapper.selectList(new QueryWrapper<PredictionRule>()
                .eq("trigger_event", eventType)
                .eq("enabled", 1)
                .orderByAsc("priority"));

        if (rules.isEmpty()) {
            return List.of();
        }

        List<PredictionEvent> triggered = new java.util.ArrayList<>();
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();

        for (PredictionRule rule : rules) {
            try {
                // 2) 防刷: 同一规则今天是否已触发
                long alreadyCount = eventMapper.countTodayByUserAndRule(userId, rule.getRuleCode(), dayStart);
                if (alreadyCount > 0) {
                    log.debug("[prediction] skip (already fired today): user={} rule={}", userId, rule.getRuleCode());
                    continue;
                }

                // 3) 规则评估
                if (!ruleEngine.evaluate(rule, context)) {
                    log.debug("[prediction] condition not met: user={} rule={}", userId, rule.getRuleCode());
                    continue;
                }

                // 4) 命中: 写 prediction_event
                PredictionEvent ev = new PredictionEvent();
                ev.setUserId(userId);
                ev.setRuleCode(rule.getRuleCode());
                ev.setStatus("SENT");
                ev.setTriggerContext(mapper.writeValueAsString(context));
                ev.setCreatedAt(LocalDateTime.now());
                ev.setSentAt(LocalDateTime.now());

                // 5) 模板替换 + 推 STOMP
                String text = renderTemplate(rule.getActionTemplate(), context);
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "PREDICTION");
                payload.put("ruleCode", rule.getRuleCode());
                payload.put("ruleName", rule.getRuleName());
                payload.put("actionType", rule.getActionType());
                payload.put("text", text);
                payload.put("ts", System.currentTimeMillis());
                ev.setActionPayload(mapper.writeValueAsString(payload));

                eventMapper.insert(ev);
                triggered.add(ev);

                // 6) 推送到用户 STOMP 队列
                try {
                    messagingTemplate.convertAndSendToUser(
                            String.valueOf(userId), "/queue/events", payload);
                    log.info("[prediction] fired: user={} rule={} text={}",
                            userId, rule.getRuleCode(), text);
                } catch (Exception e) {
                    log.error("[prediction] STOMP push failed: user={} rule={}",
                            userId, rule.getRuleCode(), e);
                }
            } catch (Exception e) {
                log.error("[prediction] eval failed: rule={}", rule.getRuleCode(), e);
            }
        }
        return triggered;
    }

    /**
     * 模板渲染: ${var} 替换.
     */
    private String renderTemplate(String template, Map<String, Object> ctx) {
        if (template == null || template.isEmpty()) return "";
        String result = template;
        if (ctx != null) {
            for (Map.Entry<String, Object> e : ctx.entrySet()) {
                String placeholder = "${" + e.getKey() + "}";
                if (result.contains(placeholder)) {
                    result = result.replace(placeholder, String.valueOf(e.getValue()));
                }
            }
        }
        return result;
    }
}