package com.chat.common.ai;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * FaqEngine - FAQ 知识库 + 检索.
 * ----------------------------------------------------------------------------
 * 阶段 1: 25 条内置 FAQ, TF-IDF 余弦检索.
 * 阶段 2: 加管理后台, 支持在线编辑 FAQ.
 * 阶段 3: 加小型 LLM 二次精排.
 */
@Slf4j
public class FaqEngine {

    private final List<FaqItem> items = new ArrayList<>();
    private final TfIdfEmbedder embedder;
    private final Map<String, float[]> itemVectors = new HashMap<>();

    public FaqEngine(TfIdfEmbedder embedder) {
        this.embedder = embedder;
        loadDefaultFaqs();
        rebuildIndex();
    }

    public record FaqItem(String intent, List<String> questions, String answer) {}

    public record SearchResult(String intent, String answer, double score) {}

    /** 默认 FAQ 库 */
    private void loadDefaultFaqs() {
        items.add(new FaqItem("refund", List.of(
                "怎么退款", "如何退款", "退款流程", "退款多久到账", "申请退款"),
                "退款流程: 1) 进入【我的订单】; 2) 找到对应订单点【申请退款】; 3) 选择退款原因并提交; 4) 审核 1-3 个工作日; 5) 退款原路返回. 大额订单 (¥500+) 转人工审核, 约 3-5 个工作日."));
        items.add(new FaqItem("order_query", List.of(
                "怎么查物流", "快递到哪了", "订单状态", "多久发货", "什么时候到"),
                "查物流: 1) 我的订单 → 找到订单 → 查看物流; 2) 物流停滞 24h+ 可联系客服催促; 3) 一般发货后 1-3 天到达. 注: 大件商品可能 5-7 天."));
        items.add(new FaqItem("payment_issue", List.of(
                "支付失败", "没法付款", "扣款没订单", "支付报错", "银行卡限额"),
                "支付常见问题: 1) 银行卡限额: 换支付宝/微信; 2) 网络问题: 切换网络重试; 3) 重复扣款: 会在 24h 内自动退回; 4) 持续失败: 联系发卡行. 紧急情况可转人工."));
        items.add(new FaqItem("price", List.of(
                "多少钱", "价格", "怎么收费", "贵不贵", "会员价", "打折吗"),
                "价格根据商品/服务不同. 普通会员 9.5 折, 银卡 9 折, 金卡 8.5 折, 钻石 8 折. 新用户首单立减 ¥30. 节日有额外优惠 (618/双11/年货节)."));
        items.add(new FaqItem("login", List.of(
                "登不上", "忘记密码", "密码错误", "账号异常", "注册"),
                "登录问题: 1) 密码错误: 点【忘记密码】重置; 2) 账号被锁: 30 分钟后自动解锁; 3) 收不到验证码: 检查垃圾箱; 4) 持续无法登录: 转人工客服协助."));
        items.add(new FaqItem("greeting", List.of(
                "你好", "hi", "hello", "在吗"),
                "您好! 我是 AI 客服小助手, 有什么可以帮您的? 常见问题: 退款 / 订单 / 支付 / 价格 / 登录. 需要人工请说【人工】."));
        items.add(new FaqItem("thanks", List.of(
                "谢谢", "感谢"),
                "不客气! 祝您生活愉快 😊"));
        items.add(new FaqItem("transfer_human", List.of(
                "人工", "真人", "坐席", "转人工"),
                "好的, 正在为您转接人工客服, 请稍等..."));
    }

    /** 重建索引 (添加新 FAQ 后调用) */
    public void rebuildIndex() {
        itemVectors.clear();
        for (FaqItem item : items) {
            String corpus = String.join(" ", item.questions());
            itemVectors.put(item.intent(), embedder.embed(corpus));
        }
        log.info("[faq] index rebuilt: {} items", items.size());
    }

    /** 添加 FAQ */
    public void addFaq(FaqItem item) {
        items.add(item);
        rebuildIndex();
    }

    /** 检索 top 1 (cosine > 0.05) */
    public Optional<SearchResult> search(String text) {
        return search(text, 1).stream().findFirst();
    }

    /** 检索 top K */
    public List<SearchResult> search(String text, int topK) {
        if (text == null || text.isEmpty()) return List.of();
        float[] qVec = embedder.embed(text);
        List<SearchResult> scored = new ArrayList<>();
        for (FaqItem item : items) {
            float[] dVec = itemVectors.get(item.intent());
            double sim = embedder.cosine(qVec, dVec);
            if (sim > 0.05) scored.add(new SearchResult(item.intent(), item.answer(), sim));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    public int size() { return items.size(); }
}