package com.chat.im.service;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * 智能客服 (机器人) - 关键字匹配 + 规则回退.
 *
 * 设计:
 *  - 纯工具类, 不作为 Spring bean (避免循环依赖)
 *  - 关键字匹配顺序: 精确 → 模糊 (子串) → 默认
 *  - 生产可换成 LLM (OpenAI / Ollama) - 只需替换 reply() 实现.
 */
@Slf4j
public final class BotService {

    private BotService() {}  // utility class

    private static final Random random = new Random();

    /** 关键字 (小写) -> 候选回复列表 (随机选一条) */
    private static final Map<String, List<String>> KEYWORD_RULES = new HashMap<>();
    /** 正则 -> 回复列表 */
    private static final Map<Pattern, List<String>> PATTERN_RULES = new HashMap<>();
    /** 兜底回复 */
    private static final List<String> FALLBACK = List.of(
        "已收到您的问题, 正在为您查询...",
        "让我帮您查找一下, 请稍等片刻。",
        "我理解您的问题, 我们马上为您处理。"
    );

    static {
        // 问候
        KEYWORD_RULES.put("你好", List.of("您好! 很高兴为您服务, 请问需要什么帮助?", "你好呀~ 我是智能客服小助手, 请告诉我您的问题?"));
        KEYWORD_RULES.put("hi", List.of("Hi! 有什么可以帮您?", "Hello, 请问需要什么帮助?"));
        KEYWORD_RULES.put("hello", List.of("Hello! 请问有什么可以帮您?", "您好! 我能为您做什么?"));
        // 价格 / 费用
        KEYWORD_RULES.put("价格", List.of("我们的价格表请参考 [价格页面]。您具体想了解哪项服务的价格?"));
        KEYWORD_RULES.put("多少钱", List.of("具体价格取决于您选择的服务类型, 请问您想了解哪类服务?"));
        KEYWORD_RULES.put("收费", List.of("基础咨询免费, 具体业务费用请参考 [收费标准]。"));
        // 退款
        KEYWORD_RULES.put("退款", List.of("退款流程: 提交申请 → 审核 (1-2 个工作日) → 原路退回。\n需要我帮您提交退款申请吗?"));
        KEYWORD_RULES.put("退钱", List.of("退款到账时间一般是 3-5 个工作日, 请问您方便提供订单号吗?"));
        // 订单
        KEYWORD_RULES.put("订单", List.of("请问您的订单号是多少? 我帮您查询。"));
        KEYWORD_RULES.put("物流", List.of("物流信息: 您的包裹正在派送中, 预计今天 18:00 前送达。"));
        KEYWORD_RULES.put("快递", List.of("快递单号已发到您手机, 请注意查收。"));
        // 技术
        KEYWORD_RULES.put("无法登录", List.of("请尝试: 1) 清除浏览器缓存 2) 重置密码 3) 仍不行请联系人工客服"));
        KEYWORD_RULES.put("密码", List.of("忘记密码可在登录页点击 '忘记密码', 通过手机号验证码重置。"));
        // 转人工
        KEYWORD_RULES.put("人工", List.of("好的, 我帮您转接到人工客服, 请稍等片刻。"));
        KEYWORD_RULES.put("真人", List.of("好的, 正在为您转接真人客服。"));
        // 投诉
        KEYWORD_RULES.put("投诉", List.of("非常抱歉给您带来不便, 我马上为您升级到主管处理, 请稍等。"));
        // 谢谢
        KEYWORD_RULES.put("谢谢", List.of("不客气! 还有其他问题吗?", "很高兴能帮到您 :)"));
        KEYWORD_RULES.put("再见", List.of("再见! 祝您生活愉快 :)", "感谢您的咨询, 后会有期。"));

        // 正则: 问号结尾
        PATTERN_RULES.put(Pattern.compile(".*[?？]$"), List.of(
            "这是个很好的问题, 让我帮您查询...",
            "我理解您的疑问, 正在为您查找答案。"
        ));
    }

    /**
     * 根据用户消息生成回复.
     * 始终返回非 null 字符串 (未匹配时返回 FALLBACK).
     */
    public static String reply(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return pickOne(FALLBACK);
        }
        String lower = userMessage.toLowerCase().trim();

        // 1) 精确关键字匹配
        for (Map.Entry<String, List<String>> e : KEYWORD_RULES.entrySet()) {
            if (lower.contains(e.getKey())) {
                log.debug("[bot] matched keyword '{}' for message '{}'", e.getKey(), userMessage);
                return pickOne(e.getValue());
            }
        }

        // 2) 正则匹配
        for (Map.Entry<Pattern, List<String>> e : PATTERN_RULES.entrySet()) {
            if (e.getKey().matcher(userMessage.trim()).matches()) {
                return pickOne(e.getValue());
            }
        }

        // 3) 兜底回复 (永不返回 null, 保证客户总能看到机器人响应)
        return pickOne(FALLBACK);
    }

    private static String pickOne(List<String> candidates) {
        return candidates.get(random.nextInt(candidates.size()));
    }
}