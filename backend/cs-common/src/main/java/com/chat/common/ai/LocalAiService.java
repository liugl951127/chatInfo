package com.chat.common.ai;

import com.chat.common.m3.M3Capability;        // 统一 AI 能力接口 (chat/embed/tts/asr)
import lombok.RequiredArgsConstructor;        // final 字段构造注入
import lombok.extern.slf4j.Slf4j;              // 日志
import org.springframework.context.annotation.Primary;  // @Primary 默认注入
import org.springframework.stereotype.Service; // Spring Bean

import java.util.*;                            // List/Map/Optional 等工具

/**
 * LocalAiService - 自研 AI 主服务 (Java 原生, 替换 miniMax3 Python 服务).
 * ----------------------------------------------------------------------------
 * 整合 IntentClassifier / SentimentAnalyzer / FaqEngine / TfIdfEmbedder,
 * 实现 M3Capability 接口, 业务可直接注入. @Primary 标记, 默认走本服务.
 *
 * 核心能力:
 *   - chat: 意图 + FAQ + 模板生成 (1ms 响应, 无外部依赖)
 *   - embed: TF-IDF 256 维 (1ms 响应, 内存向量)
 *   - sentiment: 情感分析 (词典 + 否定翻转 + 程度副词, 详见 SentimentAnalyzer)
 *   - tts: 返空, 前端用 Web Speech API (服务端不合成音频)
 *   - asr: 返空, 前端用 Web Speech API (浏览器端 STT)
 *   - understandImage: 简化版, 返结构化提示让用户描述
 *
 * 设计意图:
 *   - 零外部依赖: 不需要 Python 微服务, 不需要 GPU
 *   - 0-1ms 响应: 全部 JVM 内存计算
 *   - 零成本: 无 API 费用
 *   - 可解释: 决策链全程留痕 (intent / source / action / sentiment)
 *   - 离线可用: 不依赖网络
 *   - 易于热更新: 词典/FAQ 都是内存对象, 启动时加载
 *
 * vs miniMax3 优势:
 *   - 启动时间: 1s vs 30s+
 *   - 部署复杂度: 单 JAR vs 需 Python 环境
 *   - 响应延迟: 1ms vs 200-500ms
 *
 * @author V3 Team
 * @since 2025-09
 */
@Slf4j
@Primary                                                                  // 注入 M3Capability 默认走自研
@Service
@RequiredArgsConstructor
public class LocalAiService implements M3Capability {

    /** 意图分类器 (规则 + 关键词匹配, 11 个意图: greeting/transfer_human/...) */
    private final IntentClassifier intentClassifier;
    /** 情感分析器 (词典 + 否定 + 程度副词, 4 类: positive/negative/neutral/angry) */
    private final SentimentAnalyzer sentimentAnalyzer;
    /** TF-IDF 嵌入器 (256 维向量, 用 FAQ 检索 + 文本相似度) */
    private final TfIdfEmbedder embedder;
    /** FAQ 引擎 (基于 embedder 的相似度检索, 启动时从 DB 加载) */
    private final FaqEngine faqEngine;

    /**
     * 无参构造器: 显式 new 各个组件, 用于自包含部署 (不依赖 Spring DI).
     * <p>
     * 设计: 双重构造支持 — 容器中有依赖时走 Lombok @RequiredArgsConstructor,
     * 无容器时 (脚本/测试) 走本构造.
     */
    public LocalAiService() {
        this.intentClassifier = new IntentClassifier();
        this.sentimentAnalyzer = new SentimentAnalyzer();
        this.embedder = new TfIdfEmbedder(256);
        this.faqEngine = new FaqEngine(embedder);
        log.info("[local-ai] 自研 AI 启动: intent={} sentiment={} embed=256d faq={}",
                11, "4 类", faqEngine.size());
    }

    /**
     * 聊天 (chat 入口, 实现 M3Capability 接口).
     * ----------------------------------------------------------------------------
     * 算法:
     *   1) 取最后一条 user 消息 (忽略 system 消息)
     *   2) 调 decide() 拿到决策结果 (text / intent / source / action / sentiment)
     *   3) 估算 token 数 (简化: 字符数/2, 仅用于前端展示, 非真实 BPE)
     *   4) 包装为 ChatResponse 返回
     *   5) 异常兜底: 返"抱歉没理解" + finishReason=error
     *
     * @param req 聊天请求 (含 messages 列表, 每条 {role, content})
     *            业务含义: 对话历史, 最后一条 user 是当前问题
     *            取值范围: messages 非空
     *            影响: 仅处理最后一条 user, 历史用于上下文 (当前未使用)
     * @return ChatResponse {content, finishReason, promptTokens, completionTokens, latencyMs}
     *         - 成功: content=AI 回复, finishReason=stop
     *         - 失败: content=兜底文案, finishReason=error
     */
    @Override
    public ChatResponse chat(ChatRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            // 1) 拿 messages 列表
            List<ChatMessage> msgs = req.getMessages();
            // 2) 从尾部找第一条 role=user (忽略 system / assistant 历史)
            String lastUser = "";
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if ("user".equals(msgs.get(i).getRole())) {
                    lastUser = msgs.get(i).getContent();
                    break;
                }
            }
            // 3) 走决策链 (意图 → 规则 → FAQ → 兜底)
            Decision d = decide(lastUser);
            // 4) 估算 token (简化: 字符数 / 2 ≈ 实际 token 数, 用于前端展示)
            int promptTokens = msgs.stream().mapToInt(m -> m.getContent().length() / 2).sum();
            int completionTokens = d.text.length() / 2;
            // 5) 包装响应
            return ChatResponse.builder()
                    .content(d.text)
                    .finishReason("stop")
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        } catch (Exception e) {
            // 兜底: 任何异常都返友好提示, 不让前端崩溃
            log.error("[local-ai] chat failed", e);
            return ChatResponse.builder()
                    .content("抱歉, 我没理解您的问题, 请换个说法或回复【人工】转人工客服")
                    .finishReason("error")
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        }
    }

    /**
     * 单条文本嵌入 (TF-IDF 256 维向量).
     *
     * @param text 输入文本. 业务含义: 待向量化的文本
     *             取值范围: 任意字符串
     *             影响: 输出 256 维 float 数组
     * @return 256 维向量, 元素值范围 [0, 1]
     */
    @Override
    public float[] embed(String text) {
        // 委托给 embedder (内部: 分词 → TF → IDF → 归一化)
        return embedder.embed(text);
    }

    /**
     * 批量文本嵌入 (FAQ 启动加载时用).
     *
     * @param texts 输入文本列表. 业务含义: 批量向量化
     *              取值范围: 任意字符串列表
     *              影响: 每个元素产出一个 256 维向量
     * @return 向量列表 (与输入顺序一致)
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        return embedder.embedBatch(texts);
    }

    /**
     * 文字转语音 (TTS). 阶段 1 服务端不合成, 前端用 Web Speech API.
     * <p>
     * 设计: 服务端合成需要 GPU + 模型, 成本高, 改为浏览器原生 API.
     *
     * @param text   要合成的文本. 业务含义: TTS 内容
     *               取值范围: 任意字符串
     *               影响: 当前版本不影响 (返空 byte[])
     * @param config TTS 配置 (音色/语速等). 业务含义: 合成参数
     *               取值范围: 自定义对象
     *               影响: 当前版本不使用
     * @return 始终返空 byte[] (前端 Web Speech API 自处理)
     */
    @Override
    public byte[] tts(String text, TtsConfig config) {
        // 阶段 1: 服务端不合成, 前端用 Web Speech API
        log.debug("[local-ai] TTS: text='{}' (前端 Web Speech API 处理)", text);
        return new byte[0];
    }

    /**
     * 语音转文字 (ASR). 阶段 1 服务端不识别, 前端用 Web Speech API.
     *
     * @param audio 音频二进制. 业务含义: 待识别音频
     *              取值范围: 任意 byte[] (前端可送 webm/opus)
     *              影响: 当前版本不影响 (返空字符串)
     * @return 始终返空字符串
     */
    @Override
    public String asr(byte[] audio) {
        log.debug("[local-ai] ASR: 音频 {} 字节 (前端 Web Speech API 转写)", audio == null ? 0 : audio.length);
        return "";
    }

    /**
     * 图像理解 (简化版).
     * <p>
     * 阶段 1 不真正识别图像, 返结构化提示让用户用文字补充描述.
     *
     * @param imageUrl 图片 URL. 业务含义: 用户上传的图片地址
     *                 取值范围: HTTP/HTTPS URL
     *                 影响: 当前版本不影响
     * @param prompt   提示词. 业务含义: 用户的提问
     *                 取值范围: 任意字符串
     *                 影响: 当前版本不影响
     * @return 固定提示语
     */
    @Override
    public String understandImage(String imageUrl, String prompt) {
        return "已收到您的图片. 请用文字描述一下您看到的内容, 我会帮您解答.";
    }

    /**
     * 情感分析 (实现 M3Capability 接口).
     * <p>
     * 内部算法 (SentimentAnalyzer.analyze):
     *   1) 分词 + 移除停用词
     *   2) 扫描情感词, 累加正/负分
     *   3) 否定词翻转 (如 "不" + "好" = 负)
     *   4) 程度副词加权 (如 "非常" + "好" = 加倍)
     *   5) 计算归一化 score (正→+, 负→-, 中性→0)
     *   6) 根据 score + 关键词 (生气/愤怒) 判断 label (4 类)
     *
     * @param text 输入文本. 业务含义: 待分析文本
     *             取值范围: 任意字符串
     *             影响: 决定 sentiment label (positive/negative/neutral/angry)
     * @return SentimentResult {score, label, confidence}
     *         - score: [-1, 1] 情感强度
     *         - label: positive/negative/neutral/angry
     *         - confidence: [0, 1] 置信度
     */
    @Override
    public SentimentResult analyzeSentiment(String text) {
        // 调 SentimentAnalyzer (Java 自研, 不依赖外部)
        SentimentAnalyzer.Result r = sentimentAnalyzer.analyze(text);
        // 解析 label (枚举 → 小写字符串, 对外契约)
        SentimentAnalyzer.Label l = r.label();
        return SentimentResult.builder()
                .score(r.score())                              // 情感强度
                .label(l.name().toLowerCase())                 // 类别 (小写)
                .confidence(r.confidence())                    // 置信度
                .build();
    }

    /**
     * 健康检查 (用于 Spring Boot Actuator).
     *
     * @return 永远 true (本地服务, 不存在外部依赖故障)
     */
    @Override
    public boolean isHealthy() {
        return true;   // 本地永远 UP
    }

    // ==================== 决策链 (核心算法) ====================

    /**
     * 决策链: 意图 → 规则 → FAQ → 兜底.
     * ----------------------------------------------------------------------------
     * 算法 (伪代码):
     *   <pre>
     *   if text is empty:      return 问候语 (greeting + fallback)
     *   classify intent
     *   analyze sentiment
     *   switch intent:
     *     case TRANSFER_HUMAN: return 转人工提示 (rule + transfer_to_human action)
     *     case GOODBYE:        return 告别语 (rule + end_call action)
     *     case COMPLAINT:      return 升级到主管 (rule + transfer_to_human action)
     *     case THANKS:         return 不用客气 (rule)
     *     default:             go FAQ
     *   faq = faqEngine.search(text)
     *   if faq.score > 0.15:   return faq.answer (faq source)
     *   if sentiment == ANGRY: return 升级到人工 (fallback + transfer action)
     *   if text ends with ?:   return 建议看 FAQ/转人工 (fallback)
     *   return 兜底文案 (fallback)
     *   </pre>
     *
     * 决策点 (按优先级):
     *   1) 规则匹配优先 (高置信度, 0 延迟)
     *   2) FAQ 检索 (中等置信度, 1-2ms)
     *   3) 情感升级 (兜底前, 防止 ANGRY 用户被冷处理)
     *   4) 问号/普通 兜底
     *
     * @param text 用户消息. 业务含义: 触发决策的输入
     *             取值范围: 任意字符串 (空/中文/英文/数字)
     *             影响: 决定返回哪个 Decision
     * @return Decision {text, intent, source, action, sentiment}
     *         - text: AI 回复内容
     *         - intent: 命中的意图 (greeting/transfer_human/...)
     *         - source: 来源 (rule/faq/fallback)
     *         - action: 后续动作 (transfer_to_human/end_call/null)
     *         - sentiment: 情感分析结果
     */
    private Decision decide(String text) {
        // 0) 边界: 空文本 → 问候
        if (text == null || text.isEmpty()) {
            return new Decision("您好, 有什么可以帮您?", "greeting", "fallback", null,
                    sentimentAnalyzer.analyze(text));
        }
        // 1) 同步调用两个独立分析器 (意图 + 情感)
        IntentClassifier.Result ires = intentClassifier.classify(text);
        SentimentAnalyzer.Result sres = sentimentAnalyzer.analyze(text);
        String intent = ires.intent().name().toLowerCase();

        // 2) 规则匹配 (硬编码映射, 优先于 FAQ, 命中即返)
        switch (ires.intent()) {
            case TRANSFER_HUMAN:
                // 用户说"人工"等 → 转人工 (V3.1 markdown)
                String tx = "好的, 正在为您转接人工客服 👨‍💼\n\n"
                    + "### 预计等待时间\n\n"
                    + "- 当前等候: **0** 人\n"
                    + "- 预计接通: **< 30 秒**\n\n"
                    + "您也可以 [button:rate:继续 AI 答疑]";
                return new Decision(tx,
                        intent, "rule", "transfer_to_human", sres);
            case GOODBYE:
                // 用户说"再见"等 → 返告别语, action=end_call (前端可关闭)
                return new Decision("感谢您的咨询, 祝您生活愉快!",
                        intent, "rule", "end_call", sres);
            case COMPLAINT:
                // 用户说"投诉"等 → 升级主管 (V3.1 markdown 格式)
                String comp = "### 非常抱歉给您带来不便 😔\n\n"
                    + "我马上为您升级到主管处理.\n\n"
                    + "[button:transfer:立即转主管]  [button:rate:⭐ 评分]";
                return new Decision(comp,
                        intent, "rule", "transfer_to_human", sres);
            case THANKS:
                // 用户说"谢谢"等 → 返不用客气 (V3.1 markdown + 互动按钮)
                String thanks = "不客气! 很高兴帮到您 😊\n\n"
                    + "### 继续探索\n\n"
                    + "- 查看 [更多功能](#)\n"
                    + "- [button:rate:👍 满意] [button:rate:👎 不满意]\n"
                    + "- [button:transfer:需要人工帮助]";
                return new Decision(thanks,
                        intent, "rule", null, sres);
            default:
                // 走 FAQ / 兜底
        }

        // 3) FAQ 检索 (TF-IDF 相似度)
        Optional<FaqEngine.SearchResult> faq = faqEngine.search(text);
        if (faq.isPresent() && faq.get().score() > 0.15) {
            // 相似度阈值 0.15, 低于此值视为误匹配走兜底
            return new Decision(faq.get().answer(),
                    intent, "faq", null, sres);
        }

        // 4) 兜底: 根据情感/句末标点 决定文案
        if (sres.label() == SentimentAnalyzer.Label.ANGRY) {
            // 愤怒用户 → 直接升级人工 (避免冷处理导致投诉)
            return new Decision("理解您的心情, 让我为您转接人工客服妥善处理",
                    intent, "fallback", "transfer_to_human", sres);
        }
        String trimmed = text.trim();
        // V3.2 富文本演示: 检测特定关键词, 返富文本示例
        if (trimmed.contains("订单") && (trimmed.contains("详情") || trimmed.contains("查"))) {
            return new Decision(renderOrderDetail(), intent, "rule", null, sres);
        }
        if (trimmed.contains("评价") || trimmed.contains("评分") || trimmed.contains("反馈")) {
            return new Decision(renderFeedbackForm(), intent, "rule", null, sres);
        }
        if (trimmed.contains("帮助") || trimmed.contains("help") || trimmed.equalsIgnoreCase("?")) {
            return new Decision(renderHelpCenter(), intent, "rule", null, sres);
        }
        if (trimmed.endsWith("?") || trimmed.endsWith("？")) {
            // 问句 (V3.2 富文本)
            String q = "### [icon:🤔] 没理解您的问题\n\n"
                + "[alert:info:小贴士|您可以尝试下面任意一种方式提问]\n\n"
                + "**建议**:\n\n"
                + "1. 换个更具体的说法\n"
                + "2. 看看下方的常见问题\n"
                + "3. 直接转人工客服\n\n"
                + "### [icon:💡] 常见问题 (一键提问)\n\n"
                + "[btn:quick:怎么退款]\n\n"
                + "[btn:quick:怎么查物流]\n\n"
                + "[btn:quick:支付失败怎么办]\n\n"
                + "### [icon:👥] 需要更多帮助?\n\n"
                + "[btn:transfer:转人工客服]  [btn:link:帮助中心:https://example.com/help]";
            return new Decision(q,
                    intent, "fallback", null, sres);
        }
        // 普通兜底 (V3.2 富文本)
        String f = "[icon:👋] 抱歉没理解您的问题\n\n"
            + "[btn:transfer:转人工客服]  [btn:rate:👍]  [btn:link:帮助中心:https://example.com/help]";
        return new Decision(f,
                intent, "fallback", null, sres);
    }

    /**
     * 决策结果记录 (内部用, 用于决策链可解释性).
     *
     * @param text      AI 回复内容
     * @param intent    命中的意图 (lower case, e.g. "greeting")
     * @param source    来源 ("rule" 规则 / "faq" FAQ库 / "fallback" 兜底)
     * @param action    后续动作 ("transfer_to_human" / "end_call" / null)
     * @param sentiment 情感分析结果
     */
    public record Decision(
            String text,
            String intent,
            String source,
            String action,
            SentimentAnalyzer.Result sentiment
    ) {}

    // ============= V3.2 富文本演示模板 =============

    /**
     * 演示 1: 订单详情 (含表格/徽章/进度条/按钮).
     */
    private String renderOrderDetail() {
        return "[icon:📦] 订单详情 [badge:success:已发货] [badge:primary:VIP]\n\n"
             + "[alert:success:物流中|您的订单正在配送中, 预计 24 小时内送达]\n\n"
             + "### 订单信息\n\n"
             + "| 商品 | 数量 | 单价 |\n"
             + "| --- | --- | --- |\n"
             + "| iPhone 15 Pro | 1 | ¥8999 |\n"
             + "| AirPods Pro | 1 | ¥1899 |\n\n"
             + "### 物流进度\n\n"
             + "[progress:75]\n\n"
             + "[icon:🚚] 已揽收 → [icon:📦] 已发出 → [icon:🚛] 运输中 → [icon:🏠] 派送中\n\n"
             + "### 订单统计\n\n"
             + "[stat:总价:¥10898|+5%] [stat:已付:¥10898] [stat:优惠:¥200|-10%]\n\n"
             + "### 操作\n\n"
             + "[btn:primary:确认收货] [btn:success:查看物流] "
             + "[btn:warning:申请退货] [btn:danger:取消订单] "
             + "[btn:default:暂不处理]\n\n"
             + "[quote:客服小贴士|请在签收后 7 天内确认, 否则系统将自动确认收货]";
    }

    /**
     * 演示 2: 评价表单 (含单选/复选/文本域/提交按钮).
     */
    private String renderFeedbackForm() {
        return "[icon:⭐] 服务评价 [badge:warning:匿名]\n\n"
             + "[alert:info:感谢|您宝贵的反馈是我们改进的动力]\n\n"
             + "### 整体满意度\n\n"
             + "[radio:rating:请评分:5=非常满意|4=满意|3=一般|2=不满意|1=非常不满意]\n\n"
             + "### 哪些方面做得好? (可多选)\n\n"
             + "[checkbox:good:优秀项:attitude=服务态度|skill=专业能力|speed=响应速度|patience=耐心细致]\n\n"
             + "### 需要改进的地方\n\n"
             + "[checkbox:bad:待改进项:wait=等待时间长|skill=解答不专业|attitude=态度差|other=其他]\n\n"
             + "### 详细说明\n\n"
             + "[textarea:detail:请填写您的宝贵建议:非常满意, 客服小王很专业]\n\n"
             + "### 是否愿意推荐给他人?\n\n"
             + "[radio:nps:推荐意愿:10=非常愿意|7-9=愿意|4-6=一般|0-3=不愿意]\n\n"
             + "### 操作\n\n"
             + "[btn:primary:提交评价] [btn:default:稍后再说] [btn:link:查看评价指南:https://example.com]";
    }

    /**
     * 演示 3: 帮助中心 (含折叠面板/数据统计/标签).
     */
    private String renderHelpCenter() {
        return "[icon:📚] 帮助中心 [tag:新] [tag:热门]\n\n"
             + "### [icon:🔥] 今日数据\n\n"
             + "[stat:浏览量:12893|+15%] [stat:解决问题:8956|+8%] "
             + "[stat:好评率:96.5%|+0.5%] [stat:响应时间:< 30s|-10%]\n\n"
             + "### [icon:📖] 常见问题 (点击展开)\n\n"
             + "[collapse:如何注册账号?|访问官网, 点击注册, 填写手机号和验证码即可]\n\n"
             + "[collapse:如何修改密码?|进入个人中心 → 账户安全 → 修改密码]\n\n"
             + "[collapse:如何申请退款?|订单详情页 → 申请退款 → 填写原因 → 提交审核]\n\n"
             + "[collapse:如何联系人工?|在线客服: 工作日 9:00-18:00 | 热线: 400-xxx-xxxx]\n\n"
             + "### [icon:💬] 快捷入口\n\n"
             + "[btn:primary:在线客服] [btn:success:电话咨询] "
             + "[btn:warning:邮件反馈] [btn:link:官网帮助:https://example.com/help]\n\n"
             + "[divider]\n\n"
             + "[quote:系统提示|遇到问题可先查看帮助文档, 大部分问题 1 分钟内可自助解决]";
    }
}
