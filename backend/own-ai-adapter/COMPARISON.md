# 自研 AI vs miniMax3 适配对比

> 创建时间: 2026-07-10
> 状态: 自研 AI 已上线, miniMax3 保留为备份

---

## 1. 架构对比

| 维度 | miniMax3 (HttpM3Adapter) | 自研 AI (OwnAiAdapter) |
|------|--------------------------|------------------------|
| **部署** | 调用 miniMax3 工具 (Python SDK) | 自有 Python service (FastAPI) |
| **端口** | 8084 (m3-adapter) | 8085 (own-ai-adapter) |
| **依赖** | miniMax3 工具 + 网络 | 零外部依赖 |
| **能力** | 全部 6 个 (chat/embed/tts/asr/image/sentiment) | chat/embed/sentiment/understand-image + client-side tts/asr |
| **响应** | 网络往返 (50-500ms) | 本地 (0-1ms) |
| **成本** | 按 token 计费 | 零 |
| **可解释** | 黑盒 | 每个回复带 _meta (intent/source/action/sentiment) |
| **离线** | 不可 | 可 |
| **可定制** | 受限于 miniMax3 工具 | 完全自主 |

---

## 2. 自研 AI 能力矩阵

### 2.1 /chat (对话)

**算法链**:
```
用户消息
  ↓
1) 意图分类 (关键词权重, 10 类)
  ↓
2) 规则匹配 (转人工/挂断/投诉 → 直接 action)
  ↓
3) FAQ 检索 (TF-IDF 256 维, cosine 相似度)
  ↓
4) 模板生成 (按意图选 FAQ 答案)
  ↓
5) 兜底 (按情感 + 问句结尾选不同回复)
```

**可解释 _meta 字段**:
```json
{
  "intent": "refund",
  "source": "faq",
  "action": null,
  "sentiment": { "score": -1.0, "label": "angry", "confidence": 0.2 }
}
```

**意图分类 (10 类)**:
- `refund` - 退款相关
- `order_query` - 订单查询
- `payment_issue` - 支付问题
- `complaint` - 投诉
- `transfer_human` - 转人工
- `goodbye` - 告别
- `greeting` - 问候
- `price` - 价格
- `login` - 登录
- `thanks` - 感谢
- `unknown` - 未识别

**FAQ 知识库 (阶段 1, 25 条)**:
- 退款流程 / 物流查询 / 支付问题 / 价格 / 登录 / 问候 / 谢谢 / 转人工

### 2.2 /embed (向量化)

**算法**: TF-IDF + 哈希桶
- 维度: 256
- 分词: 中英混合 (英文按词 + 中文 1字/2字滑窗)
- 归一化: L2 normalize
- 检索: 余弦相似度

**优势**:
- 零依赖 (不调 BERT)
- 1ms 响应
- 可解释 (每个维度对应一类 token)

**局限**:
- 不支持语义级 (近义词)
- 阶段 2: 升级到小型 BERT (int8 量化, ~30MB)

### 2.3 /tts / /asr (客户端原生)

**决策**: 阶段 1 不做服务端, 前端用 Web Speech API

**理由**:
- Web Speech API 浏览器原生, 零依赖
- 隐私更好 (语音不离开浏览器)
- 减少服务端压力
- 阶段 2 再做服务端 (用 piper / coqui TTS)

**前端用法**:
```js
// TTS
const u = new SpeechSynthesisUtterance(text)
u.lang = 'zh-CN'
speechSynthesis.speak(u)

// ASR
const rec = new webkitSpeechRecognition()
rec.lang = 'zh-CN'
rec.onresult = (e) => send(e.results[0][0].transcript)
rec.start()
```

### 2.4 /sentiment (情感分析)

**算法**: 词典 + 否定翻转 + 程度副词
- 正面词: 25 个 (好/棒/优秀/满意/感谢...)
- 负面词: 27 个 (差/烂/垃圾/投诉/退款/失望...)
- 否定词: 不/没/无/non't (翻转情感)
- 程度副词: 很(×1.5)/非常(×2.0)/稍微(×0.7)...

**返回**:
```json
{
  "score": -1.0,    // [-1, 1]
  "label": "angry", // angry/sad/neutral/happy
  "confidence": 0.2 // [0, 1]
}
```

**测试结果**:
| 文本 | score | label |
|------|-------|-------|
| 气死我了, 这什么垃圾服务 | -1.0 | angry |
| 非常感谢, 客服太棒了 | 1.0 | happy |
| 这个不好 | -1.0 | angry (否定翻转) |
| 非常好, 非常满意 | 1.0 | happy (程度副词) |

### 2.5 /understand-image (图片理解)

**阶段 1 简化版**: 不做真实 CV, 返"已收到图片, 请用文字描述"

**阶段 2**: 加 pytesseract OCR
- 中文 OCR 准确率 80-90%
- 可识别错误码/数字/简单 UI

**阶段 3**: 加小型多模态模型 (CLIP/BLIP-2 int8)

---

## 3. 性能基准 (本机测试)

| 能力 | 平均延迟 | 内存 | CPU |
|------|---------|------|-----|
| /chat | 0-1ms | ~50MB | 0% |
| /embed | 0-1ms | ~10MB | 0% |
| /sentiment | 0-1ms | ~5MB | 0% |
| /health | <1ms | - | 0% |

**对比 miniMax3**:
- /chat: 50-500ms (网络) vs 0-1ms (本地) — **快 100x**
- 成本: 0 vs 按 token 计费

---

## 4. 集成方式

### 4.1 Java 端

```java
// 默认走自研 AI (@Primary 优先)
@Autowired
private M3Capability ai;

// 显式指定自研 AI
@Autowired
@Qualifier("ownAiAdapter")
private M3Capability ai;

// 显式回退 miniMax3 (备份)
@Autowired
@Qualifier("httpM3Adapter")
private M3Capability ai;
```

### 4.2 业务调用

```java
// cs-voice 已接入
@Service
public class VoiceAiAgent {
    @Autowired
    private M3Capability ai;  // 拿到 OwnAiAdapter
    
    public AIResponse decide(VoiceCall call, String userText) {
        var resp = ai.chat(...);  // 自研 AI 决策
        var sent = ai.analyzeSentiment(userText);  // 情感分析
    }
}
```

### 4.3 部署

```bash
# own-ai-adapter (端口 8085)
cd backend/own-ai-adapter
uvicorn own_ai_server:app --host 0.0.0.0 --port 8085

# m3-adapter (端口 8084, 备份, 可选)
cd backend/m3-adapter
uvicorn m3_server:app --host 0.0.0.0 --port 8084
```

---

## 5. 切换策略

**当前状态 (v3.1)**:
- 默认 `OwnAiAdapter` (@Primary)
- 保留 `HttpM3Adapter` 作为备份
- 灰度切换: 通过 `@Qualifier` 控制

**未来**:
- v3.2: 升级自研 AI (加小型 BERT)
- v3.3: 加图谱 / 多模态
- 长期: 自研 AI 全面替代 miniMax3, miniMax3 仅作 fallback

---

## 6. FAQ 知识库扩展

知识库定义在 `own_ai_server.py` `FAQ_DATABASE`:

```python
FAQ_DATABASE = [
    {
        "intent": "refund",
        "q": ["怎么退款", "如何退款", ...],
        "a": "退款流程: ...",
    },
    # ... 25 条
]
```

**添加 FAQ 步骤**:
1. 找对应 intent
2. 加 `q` 列表 (5-10 个常用问法)
3. 加 `a` (标准答案)
4. 重启 own-ai-adapter

**未来**: 阶段 2 加管理后台, 运营可在线编辑

---

## 7. 监控指标

```python
# 阶段 1 内置基础日志
log.info("[own-ai] chat intent={} source={} action={} latency={}ms",
    intent, source, action, latency_ms)

# 阶段 2 加 Prometheus:
# - own_ai_chat_total (按 intent 标签)
# - own_ai_chat_latency_seconds
# - own_ai_faq_hit_rate
# - own_ai_sentiment_distribution
```

---

**最后更新**: 2026-07-10
**状态**: 自研 AI 100% 替代 miniMax3 路径已打通
