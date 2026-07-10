package com.chat.common.ai;

import java.util.*;

/**
 * IntentClassifier - 意图分类器 (自研 AI 核心).
 * ----------------------------------------------------------------------------
 * 算法: 关键词权重 + 多意图打分, 取 top 1.
 * 阶段 1: 10 类意图, 规则匹配.
 * 阶段 2: 升级到小型 BERT (int8 量化).
 *
 * 与 miniMax3 区别: 零外部调用, 0-1ms 响应, 完全可解释.
 */
public class IntentClassifier {

    public enum Intent {
        REFUND, ORDER_QUERY, PAYMENT_ISSUE, COMPLAINT,
        TRANSFER_HUMAN, GOODBYE, GREETING, PRICE, LOGIN, THANKS, UNKNOWN
    }

    private final List<IntentRule> rules;

    public IntentClassifier() {
        this.rules = List.of(
            new IntentRule(Intent.REFUND,         List.of("退款", "退钱", "退货", "退单", "refund"), 1),
            new IntentRule(Intent.ORDER_QUERY,    List.of("订单", "物流", "快递", "发货", "到哪", "查单", "order", "shipping"), 1),
            new IntentRule(Intent.PAYMENT_ISSUE,  List.of("支付", "付款", "扣款", "失败", "没法付", "付不了", "payment"), 1),
            new IntentRule(Intent.COMPLAINT,      List.of("投诉", "举报", "差评", "消协", "12315", "complaint"), 1),
            new IntentRule(Intent.TRANSFER_HUMAN, List.of("人工", "真人", "坐席", "转接", "转人工", "human", "agent", "staff"), 1),
            new IntentRule(Intent.GOODBYE,        List.of("再见", "拜拜", "bye", "goodbye", "结束", "挂断"), 1),
            new IntentRule(Intent.GREETING,       List.of("你好", "hi", "hello", "在吗", "在么"), 1),
            new IntentRule(Intent.PRICE,          List.of("价格", "多少钱", "怎么卖", "贵不贵", "优惠", "折扣", "price"), 1),
            new IntentRule(Intent.LOGIN,          List.of("登录", "登不上", "密码", "账号", "注册", "login", "password"), 1),
            new IntentRule(Intent.THANKS,         List.of("谢谢", "感谢", "thanks", "thank you", "thx"), 1),
            // 弱信号: 问句结尾
            new IntentRule(Intent.UNKNOWN,        List.of("?", "？"), 0.1)
        );
    }

    public Result classify(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(Intent.UNKNOWN, 0.0);
        }
        String lower = text.toLowerCase();
        Map<Intent, Double> scores = new EnumMap<>(Intent.class);

        for (IntentRule rule : rules) {
            for (String kw : rule.keywords) {
                if (lower.contains(kw.toLowerCase())) {
                    scores.merge(rule.intent, rule.weight, Double::sum);
                }
            }
        }
        if (scores.isEmpty()) {
            return new Result(Intent.UNKNOWN, 0.0);
        }
        Map.Entry<Intent, Double> top = Collections.max(scores.entrySet(), Map.Entry.comparingByValue());
        double total = scores.values().stream().mapToDouble(Double::doubleValue).sum();
        return new Result(top.getKey(), top.getValue() / Math.max(total, 1.0));
    }

    public record Result(Intent intent, double confidence) {}

    private record IntentRule(Intent intent, List<String> keywords, double weight) {}
}