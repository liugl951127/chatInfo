# Own AI Adapter (自研 AI 替换 miniMax3)

**目标**: 完全脱离 miniMax3 (miniMax3 = Mavis), 用自研 AI 能力支撑 6 个新模块.

**端口**: 8085 (与原 m3-adapter 8084 并存, 方便灰度)

**核心能力**:

| 接口 | 自研实现 | 关键技术 |
|------|---------|---------|
| /chat | 意图分类 + 知识库检索 + 模板生成 | 关键词权重 + TF-IDF + Jinja2 |
| /embed | TF-IDF 向量化 | sklearn TfidfVectorizer (256 维) |
| /tts | 浏览器原生 (后端返参数) | Web Speech API (前端) |
| /asr | 浏览器原生 (前端转写后送文字) | Web Speech API (前端) |
| /understand-image | OCR + 模板 | pytesseract (阶段 2) |
| /sentiment | 情感词典 + 否定词处理 | HowNet 简化版 |

**优势**:
- 零外部依赖 (不调任何 LLM API)
- CPU 可跑 (无需 GPU)
- 可解释 (每条回复都能溯源到知识库或模板)
- 离线可用

**局限**:
- 阶段 1 不支持长上下文 (历史消息只取最近 5 条)
- 阶段 1 不支持复杂多轮对话 (只支持单意图)
- 阶段 2 升级: 加小型 BERT (int8 量化) 做意图分类
