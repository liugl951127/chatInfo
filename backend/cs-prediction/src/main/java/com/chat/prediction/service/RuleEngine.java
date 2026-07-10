package com.chat.prediction.service;

import com.chat.prediction.entity.PredictionRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * RuleEngine - 简易规则引擎 (JSONLogic 简化版).
 * ----------------------------------------------------------------------------
 * 阶段 1 支持的运算符:
 *   $gte: >=
 *   $gt:  >
 *   $lte: <=
 *   $lt:  <
 *   $eq:  ==
 *   $ne:  !=
 *
 * 例: {"hours_since_update": {"$gte": 24}}  -> ctx.hours_since_update >= 24
 *
 * 阶段 2 升级: 接 MiniMax-M3 做语义级判断.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleEngine {

    private final ObjectMapper mapper;

    @SuppressWarnings("unchecked")
    public boolean evaluate(PredictionRule rule, Map<String, Object> context) {
        if (rule.getConditionExpr() == null || rule.getConditionExpr().isEmpty()) {
            return true;  // 无条件
        }
        try {
            Map<String, Object> expr = mapper.readValue(rule.getConditionExpr(), Map.class);
            return evalExpr(expr, context);
        } catch (Exception e) {
            log.error("[rule] condition parse failed: rule={} expr={}", rule.getRuleCode(), rule.getConditionExpr(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean evalExpr(Object expr, Map<String, Object> ctx) {
        if (expr instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) expr;
            // 单 key 形式: {field: {op: value}}
            if (map.size() == 1) {
                Map.Entry<String, Object> e = map.entrySet().iterator().next();
                String field = e.getKey();
                Object condition = e.getValue();
                if (condition instanceof Map) {
                    Map<String, Object> ops = (Map<String, Object>) condition;
                    Object actual = ctx.get(field);
                    return evalOps(ops, actual);
                } else {
                    // 简单相等: {field: value}
                    return Objects.equals(ctx.get(field), condition);
                }
            }
            // 多 key AND: {a: {op: v}, b: {op: v}}
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!evalExpr(Map.of(e.getKey(), e.getValue()), ctx)) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean evalOps(Map<String, Object> ops, Object actual) {
        for (Map.Entry<String, Object> e : ops.entrySet()) {
            String op = e.getKey();
            Object expected = e.getValue();
            switch (op) {
                case "$gte": if (!compareNumber(actual, expected, (a, b) -> a >= b)) return false; break;
                case "$gt":  if (!compareNumber(actual, expected, (a, b) -> a > b)) return false; break;
                case "$lte": if (!compareNumber(actual, expected, (a, b) -> a <= b)) return false; break;
                case "$lt":  if (!compareNumber(actual, expected, (a, b) -> a < b)) return false; break;
                case "$eq":  if (!Objects.equals(actual, expected)) return false; break;
                case "$ne":  if (Objects.equals(actual, expected)) return false; break;
                default: log.warn("[rule] unknown op: {}", op);
            }
        }
        return true;
    }

    private boolean compareNumber(Object actual, Object expected,
                                   java.util.function.BiFunction<Double, Double, Boolean> op) {
        if (actual == null) return false;
        try {
            double a = Double.parseDouble(actual.toString());
            double b = Double.parseDouble(expected.toString());
            return op.apply(a, b);
        } catch (Exception e) {
            return false;
        }
    }
}