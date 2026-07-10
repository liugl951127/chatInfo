package com.chat.im.service;

import lombok.extern.slf4j.Slf4j;                                          // SLF4J 日志门面

import java.util.HashMap;                                                  // 关键字表底层
import java.util.List;                                                     // 候选回复列表
import java.util.Map;                                                      // 关键字 -> 回复 映射
import java.util.Random;                                                   // 随机选回复
import java.util.regex.Pattern;                                            // 正则匹配

/**
 * 智能客服 (机器人) - 关键字匹配 + 规则回退.
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 客户在机器人会话 (isBot=1) 发文本消息时, 自动生成回复
 *   - 完全本地规则, 不依赖外部 LLM (生产可换 OpenAI / Ollama, 改 reply() 即可)
 *
 * 设计:
 *   - 纯静态工具类 (public final + private 构造), 不作为 Spring Bean
 *     原因: 避免 MessageService<->BotService 的 Spring 循环依赖 (Spring 6 默认禁)
 *     替代方案: 事件总线 / @Lazy / allow-circular-references 都不彻底
 *   - 匹配顺序: 精确关键字 (substring) -> 正则 -> 兜底 (永不返回 null)
 *   - 同关键词多条候选, 随机返回一条 (增加对话多样性)
 *
 * 调用入口:
 *   - MessageService.handleIncoming() 检测到 (isBot=1, MSG_TEXT, ROLE_CUSTOMER)
 *     时调用 BotService.reply(content), 然后自己负责持久化 + 推送
 */
@Slf4j                                                                      // 自动生成 log 字段
public final class BotService {

    /** 私有构造: 禁止实例化 (工具类) */
    private BotService() {}

    /** 随机数生成器 (候选回复随机选择) */
    private static final Random random = new Random();

    /** 关键字 (小写) -> 候选回复列表. 注意: 匹配是 contains 而非 equals, 所以 "人工" 会匹配 "我要转人工" */
    private static final Map<String, List<String>> KEYWORD_RULES = new HashMap<>();
    /** 正则 -> 回复列表 (兜底前的精细匹配, 比如问号结尾) */
    private static final Map<Pattern, List<String>> PATTERN_RULES = new HashMap<>();
    /** 兜底回复: 任何 query 都能命中, 保证客户永远收到机器人响应 */
    private static final List<String> FALLBACK = List.of(
        "已收到您的问题, 正在为您查询...",
        "让我帮您查找一下, 请稍等片刻。",
        "我理解您的问题, 我们马上为您处理。"
    );

    /** 类初始化块: 注册所有关键字和正则规则 (类加载时执行一次) */
    static {
        // ============ 问候 ============
        KEYWORD_RULES.put("你好", List.of("您好! 很高兴为您服务, 请问需要什么帮助?", "你好呀~ 我是智能客服小助手, 请告诉我您的问题?"));
        KEYWORD_RULES.put("hi", List.of("Hi! 有什么可以帮您?", "Hello, 请问需要什么帮助?"));
        KEYWORD_RULES.put("hello", List.of("Hello! 请问有什么可以帮您?", "您好! 我能为您做什么?"));
        // ============ 价格 / 费用 ============
        KEYWORD_RULES.put("价格", List.of("我们的价格表请参考 [价格页面]。您具体想了解哪项服务的价格?"));
        KEYWORD_RULES.put("多少钱", List.of("具体价格取决于您选择的服务类型, 请问您想了解哪类服务?"));
        KEYWORD_RULES.put("收费", List.of("基础咨询免费, 具体业务费用请参考 [收费标准]。"));
        // ============ 退款 ============
        KEYWORD_RULES.put("退款", List.of("退款流程: 提交申请 → 审核 (1-2 个工作日) → 原路退回。\n需要我帮您提交退款申请吗?"));
        KEYWORD_RULES.put("退钱", List.of("退款到账时间一般是 3-5 个工作日, 请问您方便提供订单号吗?"));
        // ============ 订单 / 物流 ============
        KEYWORD_RULES.put("订单", List.of("请问您的订单号是多少? 我帮您查询。"));
        KEYWORD_RULES.put("物流", List.of("物流信息: 您的包裹正在派送中, 预计今天 18:00 前送达。"));
        KEYWORD_RULES.put("快递", List.of("快递单号已发到您手机, 请注意查收。"));
        // ============ 技术 ============
        KEYWORD_RULES.put("无法登录", List.of("请尝试: 1) 清除浏览器缓存 2) 重置密码 3) 仍不行请联系人工客服"));
        KEYWORD_RULES.put("密码", List.of("忘记密码可在登录页点击 '忘记密码', 通过手机号验证码重置。"));
        // ============ 转人工 ============
        KEYWORD_RULES.put("人工", List.of("好的, 我帮您转接到人工客服, 请稍等片刻。"));
        KEYWORD_RULES.put("真人", List.of("好的, 正在为您转接真人客服。"));
        // ============ 投诉 ============
        KEYWORD_RULES.put("投诉", List.of("非常抱歉给您带来不便, 我马上为您升级到主管处理, 请稍等。"));
        // ============ 致谢 / 告别 ============
        KEYWORD_RULES.put("谢谢", List.of("不客气! 还有其他问题吗?", "很高兴能帮到您 :)"));
        KEYWORD_RULES.put("再见", List.of("再见! 祝您生活愉快 :)", "感谢您的咨询, 后会有期。"));

        // ============ 正则规则: 问号结尾 (兜底前的精细匹配) ============
        PATTERN_RULES.put(Pattern.compile(".*[?？]$"), List.of(
            "这是个很好的问题, 让我帮您查询...",
            "我理解您的疑问, 正在为您查找答案。"
        ));
    }

    /**
     * 根据用户消息生成机器人回复.
     * 始终返回非 null 字符串 (未匹配时返回 FALLBACK 之一), 保证客户永远能看到响应.
     *
     * 匹配流程:
     *   1) null/空 → 直接返 FALLBACK
     *   2) 遍历 KEYWORD_RULES, 用 contains 匹配 (substring 而非 equals)
     *      注意: HashMap 迭代顺序不固定, 多个关键词都命中时取先遍历到的
     *   3) 遍历 PATTERN_RULES, 用 regex matches() 匹配整个字符串
     *   4) 兜底: 返 FALLBACK 随机一条
     *
     * @param userMessage 客户消息文本
     * @return 回复文本 (非 null)
     */
    public static String reply(String userMessage) {
        // 1) 空消息 → 兜底
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return pickOne(FALLBACK);
        }
        // 转为小写 + trim, 用于不区分大小写的关键字匹配
        String lower = userMessage.toLowerCase().trim();

        // 2) 关键字匹配 (substring 包含, 不是整词匹配)
        for (Map.Entry<String, List<String>> e : KEYWORD_RULES.entrySet()) {
            if (lower.contains(e.getKey())) {
                log.debug("[bot] matched keyword '{}' for message '{}'", e.getKey(), userMessage);
                return pickOne(e.getValue());
            }
        }

        // 3) 正则匹配 (整字符串 matches, 不是 find)
        for (Map.Entry<Pattern, List<String>> e : PATTERN_RULES.entrySet()) {
            if (e.getKey().matcher(userMessage.trim()).matches()) {
                return pickOne(e.getValue());
            }
        }

        // 4) 兜底: 永不返 null
        return pickOne(FALLBACK);
    }

    /**
     * 从候选列表中随机选一条返回.
     * @param candidates 候选列表 (不可为空, 调用方保证)
     * @return 选中的字符串
     */
    private static String pickOne(List<String> candidates) {
        return candidates.get(random.nextInt(candidates.size()));
    }
}