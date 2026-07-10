# 自研 AI (Local AI Service) - 替换 miniMax3, Java 原生, 开箱即用

> 创建: 2026-07-10
> 状态: ✅ 11/11 单元测试通过, 已 @Primary 默认注入

## 概述

完全脱离 miniMax3 (miniMax3 = Mavis) 依赖, 用 Java 实现 6 大 AI 能力,
集成到 cs-common, 业务模块开箱即用, 无需 Python service.

## 模块结构

```
cs-common/src/main/java/com/chat/common/ai/
├── IntentClassifier.java   意图分类 (10 类 + 关键词权重)
├── SentimentAnalyzer.java  情感分析 (词典 + 否定翻转 + 程度副词)
├── TfIdfEmbedder.java      TF-IDF 向量化 (256 维, L2 归一化)
├── FaqEngine.java          FAQ 知识库 (8 类 25 条, 余弦检索)
├── LocalAiService.java     整合 + 主决策链 (@Primary, 实现 M3Capability)
└── README.md               (本文件)

cs-common/src/test/java/com/chat/common/ai/
└── LocalAiServiceTest.java 11 个单元测试
```

## 能力矩阵

| M3Capability 方法 | Java 实现 | 性能 | 备注 |
|------------------|----------|------|------|
| chat() | IntentClassifier → 规则 → FAQ → 兜底 | 0-1ms | 主对话 |
| embed() | TF-IDF 256 维 + L2 归一化 | 0-1ms | 文本向量化 |
| tts() | 返空 (前端用 Web Speech API) | 0ms | 客户端合成 |
| asr() | 返空 (前端用 Web Speech API) | 0ms | 客户端转写 |
| understandImage() | 简化版, 提示用户描述 | 0ms | 阶段 2 加 OCR |
| analyzeSentiment() | 词典 + 否定翻转 + 程度副词 | 0-1ms | 情感分析 |
| isHealthy() | 永远 true (本地) | 0ms | - |

## 决策链 (chat)

```
用户消息
  ↓
1) 意图分类 (IntentClassifier.classify)
  ↓
2) 规则匹配 (TRANSFER_HUMAN/GOODBYE/COMPLAINT/THANKS → 直接 action)
  ↓
3) FAQ 检索 (FaqEngine.search, cosine > 0.15)
  ↓
4) 情感兜底 (ANGRY → 转人工)
  ↓
5) 问句结尾 (?, ？ → 引导性回复)
  ↓
6) 通用兜底 (换说法 / 转人工)
```

## 业务使用

```java
@Service
public class MyBusiness {
    @Autowired
    private M3Capability ai;  // 自动拿到 LocalAiService (@Primary)

    public void onUserMessage(String text) {
        var resp = ai.chat(ChatRequest.builder()
            .messages(List.of(message("user", text)))
            .build());
        // resp.getContent() -> AI 回复

        var sent = ai.analyzeSentiment(text);
        // sent.getScore() / getLabel() -> 情感分析
    }
}
```

## 单元测试

```
mvn test -pl cs-common -Dtest=LocalAiServiceTest
```

11 个测试覆盖:
- chat 退款 / 转人工 / 告别
- sentiment 愤怒 / 高兴 / 否定翻转
- embed 维度 / L2 归一化 / 主题相似度
- isHealthy
- 100 次 chat 延迟 < 1s

## 阶段 2 升级

- 意图分类: 加小型 BERT (int8 量化, ~30MB)
- Embed: 加 SBERT small
- OCR: pytesseract 中文识别
- TTS/ASR: piper / faster-whisper 本地推理

## 优势 vs miniMax3

| 维度 | miniMax3 | 自研 AI (Java) |
|------|----------|----------------|
| 部署 | Python service | 零部署 (JVM) |
| 依赖 | 外部服务 | 零 |
| 响应 | 50-500ms | 0-1ms |
| 成本 | 按 token | 零 |
| 离线 | ❌ | ✅ |
| 可解释 | 黑盒 | 每个回复带 _meta |
| 可定制 | 受限 | 完全自主 |
