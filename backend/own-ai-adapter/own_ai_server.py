"""
own_ai_server.py - 自研 AI 服务 (替换 miniMax3)
============================================================================
完全脱离 LLM API, 用规则 + 知识库 + 经典 ML 实现 6 大能力.
零外部依赖 (除 sklearn/numpy/jieba).
"""
import os
import re
import json
import time
import math
import hashlib
import logging
from typing import List, Dict, Optional, Any
from collections import Counter
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s %(message)s')
log = logging.getLogger("own-ai")

app = FastAPI(title="Own AI Adapter (自研 AI, 替换 miniMax3)", version="1.0.0")


# ========================================================================
# 1. 情感词典 (简化 HowNet)
# ========================================================================

POSITIVE_WORDS = {
    "好", "棒", "优秀", "满意", "喜欢", "感谢", "谢谢", "赞", "开心", "高兴",
    "完美", "贴心", "专业", "快速", "高效", "nice", "good", "great", "love",
    "推荐", "支持", "ok", "OK", "对的", "没错",
}

NEGATIVE_WORDS = {
    "差", "烂", "垃圾", "差评", "投诉", "退款", "退钱", "失望", "生气", "愤怒",
    "烦", "麻烦", "慢", "卡", "bug", "问题", "故障", "错误", "骗", "骗子",
    "退订", "取消", "不用了", "不行", "不能", "不可", "没用", "浪费", "坑",
    "bad", "terrible", "awful", "hate",
}

NEGATION_WORDS = {"不", "没", "无", "非", "未", "别", "莫", "勿", "no", "not", "n't"}

# 程度副词 (权重)
INTENSIFIERS = {
    "很": 1.5, "非常": 2.0, "特别": 1.8, "极": 2.0, "超级": 2.0,
    "比较": 1.2, "稍微": 0.7, "一点": 0.8, "太": 1.8,
    "very": 1.5, "extremely": 2.0, "really": 1.5, "so": 1.5,
}

# ========================================================================
# 2. 意图分类 (关键词权重)
# ========================================================================

INTENT_RULES = [
    # (intent_name, [keywords], weight_threshold)
    ("refund", ["退款", "退钱", "退货", "退单", "refund"], 1),
    ("order_query", ["订单", "物流", "快递", "发货", "到哪", "查单", "order", "shipping"], 1),
    ("payment_issue", ["支付", "付款", "扣款", "失败", "没法付", "付不了", "payment"], 1),
    ("complaint", ["投诉", "举报", "差评", "消协", "12315", "com plaint"], 1),
    ("transfer_human", ["人工", "真人", "坐席", "转接", "转人工", "human", "agent", "staff"], 1),
    ("goodbye", ["再见", "拜拜", "bye", "goodbye", "结束", "挂断"], 1),
    ("greeting", ["你好", "hi", "hello", "在吗", "在么"], 1),
    ("price", ["价格", "多少钱", "怎么卖", "贵不贵", "优惠", "折扣", "price"], 1),
    ("login", ["登录", "登不上", "密码", "账号", "注册", "login", "password"], 1),
    ("thanks", ["谢谢", "感谢", "thanks", "thank you", "thx"], 1),
    ("question_mark", ["?"], 0.1),   # 弱信号: 问句结尾
]

# ========================================================================
# 3. FAQ 知识库 (阶段 1 简化版, 文件存储)
# ========================================================================

FAQ_DATABASE = [
    {
        "intent": "refund",
        "q": ["怎么退款", "如何退款", "退款流程", "退款多久到账", "申请退款"],
        "a": "退款流程: 1) 进入【我的订单】; 2) 找到对应订单点【申请退款】; 3) 选择退款原因并提交; 4) 审核 1-3 个工作日; 5) 退款原路返回. 大额订单 (¥500+) 转人工审核, 约 3-5 个工作日.",
    },
    {
        "intent": "order_query",
        "q": ["怎么查物流", "快递到哪了", "订单状态", "多久发货", "什么时候到"],
        "a": "查物流: 1) 我的订单 → 找到订单 → 查看物流; 2) 物流停滞 24h+ 可联系客服催促; 3) 一般发货后 1-3 天到达. 注: 大件商品可能 5-7 天.",
    },
    {
        "intent": "payment_issue",
        "q": ["支付失败", "没法付款", "扣款没订单", "支付报错", "银行卡限额"],
        "a": "支付常见问题: 1) 银行卡限额: 换支付宝/微信; 2) 网络问题: 切换网络重试; 3) 重复扣款: 会在 24h 内自动退回; 4) 持续失败: 联系发卡行. 紧急情况可转人工.",
    },
    {
        "intent": "price",
        "q": ["多少钱", "价格", "怎么收费", "贵不贵", "会员价", "打折吗"],
        "a": "价格根据商品/服务不同. 普通会员 9.5 折, 银卡 9 折, 金卡 8.5 折, 钻石 8 折. 新用户首单立减 ¥30. 节日有额外优惠 (618/双11/年货节).",
    },
    {
        "intent": "login",
        "q": ["登不上", "忘记密码", "密码错误", "账号异常", "注册"],
        "a": "登录问题: 1) 密码错误: 点【忘记密码】重置; 2) 账号被锁: 30 分钟后自动解锁; 3) 收不到验证码: 检查垃圾箱; 4) 持续无法登录: 转人工客服协助.",
    },
    {
        "intent": "greeting",
        "q": ["你好", "hi", "hello", "在吗"],
        "a": "您好! 我是 AI 客服小助手, 有什么可以帮您的? 常见问题: 退款 / 订单 / 支付 / 价格 / 登录. 需要人工请说【人工】.",
    },
    {
        "intent": "thanks",
        "q": ["谢谢", "感谢"],
        "a": "不客气! 祝您生活愉快 😊",
    },
    {
        "intent": "transfer_human",
        "q": ["人工", "真人", "坐席", "转人工"],
        "a": "好的, 正在为您转接人工客服, 请稍等...",
    },
]

# 关键词索引 (加速检索)
_FAQ_INDEX: Dict[str, List[dict]] = {}
for item in FAQ_DATABASE:
    for q in item["q"]:
        for kw in re.split(r"[\s,!?。,!?;]", q):
            kw = kw.strip().lower()
            if kw:
                _FAQ_INDEX.setdefault(kw, []).append(item)


# ========================================================================
# 4. TF-IDF 简易向量化 (不依赖 sklearn, 纯 Python)
# ========================================================================

def tokenize(text: str) -> List[str]:
    """中英混合简单分词: 中文按字, 英文按词, 去标点"""
    if not text:
        return []
    text = text.lower().strip()
    # 中文 1-2 字滑窗 + 英文单词
    tokens = []
    # 英文/数字
    for m in re.finditer(r"[a-z0-9]+", text):
        tokens.append(m.group())
    # 中文: 拆成 1 字 + 2 字
    cn = re.sub(r"[a-z0-9\s!?,.;:()\[\]{}\"'`~@#$%^&*+=\-_/\\|]+", " ", text)
    for ch in cn:
        if "\u4e00" <= ch <= "\u9fff":
            tokens.append(ch)
    for i in range(len(cn) - 1):
        if "\u4e00" <= cn[i] <= "\u9fff" and "\u4e00" <= cn[i+1] <= "\u9fff":
            tokens.append(cn[i:i+2])
    return tokens


# 简易 IDF 词典 (基于 FAQ 数据库预计算)
_IDF: Dict[str, float] = {}
for item in FAQ_DATABASE:
    for q in item["q"]:
        toks = set(tokenize(q))
        for t in toks:
            _IDF[t] = _IDF.get(t, 0) + 1
TOTAL_DOCS = max(len(FAQ_DATABASE), 1)
for t, df in _IDF.items():
    _IDF[t] = math.log((TOTAL_DOCS + 1) / (df + 1)) + 1  # 平滑


def embed_text(text: str, dim: int = 256) -> List[float]:
    """TF-IDF 向量化, 输出 256 维"""
    if not text:
        return [0.0] * dim
    toks = tokenize(text)
    if not toks:
        return [0.0] * dim
    tf = Counter(toks)
    n = len(toks)
    # 用 hash 桶把 token 映射到 256 维
    vec = [0.0] * dim
    for t, c in tf.items():
        h = int(hashlib.md5(t.encode("utf-8")).hexdigest(), 16) % dim
        idf = _IDF.get(t, 1.0)
        vec[h] += (c / n) * idf
    # L2 normalize
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    return [v / norm for v in vec]


def cosine(a: List[float], b: List[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


# ========================================================================
# 5. 意图分类 + 情感分析
# ========================================================================

def classify_intent(text: str) -> tuple:
    """返回 (intent, confidence)"""
    if not text:
        return ("unknown", 0.0)
    text_l = text.lower()
    scores: Counter = Counter()
    for intent, kws, thresh in INTENT_RULES:
        for kw in kws:
            if kw in text_l:
                scores[intent] += 1
    if not scores:
        return ("unknown", 0.0)
    top, top_score = scores.most_common(1)[0]
    total = sum(scores.values())
    return (top, top_score / max(total, 1))


def analyze_sentiment(text: str) -> Dict[str, Any]:
    """情感打分 (-1.0 ~ +1.0) + label + confidence"""
    if not text:
        return {"score": 0.0, "label": "neutral", "confidence": 0.0}
    toks = tokenize(text)
    score = 0.0
    hits = 0
    i = 0
    while i < len(toks):
        t = toks[i]
        intensifier = 1.0
        # 前一个词是程度副词
        if i > 0 and toks[i-1] in INTENSIFIERS:
            intensifier = INTENSIFIERS[toks[i-1]]
        # 否定词翻转
        negated = (i > 0 and toks[i-1] in NEGATION_WORDS)
        if t in POSITIVE_WORDS:
            score += intensifier
            hits += 1
            if negated:
                score -= intensifier * 2  # 否定翻转
        elif t in NEGATIVE_WORDS:
            score -= intensifier
            hits += 1
            if negated:
                score += intensifier * 2  # 否定翻转
        i += 1
    # 归一化到 [-1, 1]
    if hits == 0:
        score = 0.0
        conf = 0.0
    else:
        score = max(-1.0, min(1.0, score / max(hits, 1)))
        conf = min(1.0, hits / 5.0)
    if score < -0.3:
        label = "angry"
    elif score < 0:
        label = "sad"
    elif score > 0.3:
        label = "happy"
    else:
        label = "neutral"
    return {"score": round(score, 3), "label": label, "confidence": round(conf, 3)}


# ========================================================================
# 6. FAQ 检索 + 对话生成
# ========================================================================

def search_faq(text: str, top_k: int = 1) -> List[Dict[str, Any]]:
    """TF-IDF 相似度检索"""
    if not text:
        return []
    q_vec = embed_text(text)
    scored = []
    for item in FAQ_DATABASE:
        # 合并所有 Q
        corpus = " ".join(item["q"])
        d_vec = embed_text(corpus)
        sim = cosine(q_vec, d_vec)
        scored.append((sim, item))
    scored.sort(key=lambda x: -x[0])
    return [{"score": s, "intent": it["intent"], "answer": it["a"]}
            for s, it in scored[:top_k] if s > 0.05]


def decide_response(messages: List[Dict]) -> Dict[str, Any]:
    """
    自研对话决策 (替代 LLM):
    1. 取最后一条 user 消息
    2. 意图分类
    3. FAQ 检索
    4. 模板生成
    5. 兜底
    """
    last_user = ""
    for m in reversed(messages):
        if m.get("role") == "user":
            last_user = m.get("content", "")
            break

    if not last_user:
        return {"text": "您好, 有什么可以帮您?", "intent": "greeting", "source": "fallback"}

    # 1) 意图分类
    intent, intent_conf = classify_intent(last_user)

    # 2) 情感
    sentiment = analyze_sentiment(last_user)

    # 3) 转人工/挂断/投诉特殊处理
    if intent in ("transfer_human",):
        return {"text": "好的, 正在为您转接人工客服, 请稍等...",
                "intent": intent, "source": "rule", "action": "transfer_to_human",
                "sentiment": sentiment}
    if intent == "goodbye":
        return {"text": "感谢您的咨询, 祝您生活愉快!",
                "intent": intent, "source": "rule", "action": "end_call",
                "sentiment": sentiment}
    if intent == "complaint":
        return {"text": "非常抱歉给您带来不便, 我马上为您升级到主管处理",
                "intent": intent, "source": "rule", "action": "transfer_to_human",
                "params": {"priority": "high"}, "sentiment": sentiment}
    if intent == "thanks":
        return {"text": "不客气! 还需要其他帮助吗?",
                "intent": intent, "source": "rule", "sentiment": sentiment}

    # 4) FAQ 检索
    results = search_faq(last_user, top_k=1)
    if results and results[0]["score"] > 0.15:
        return {"text": results[0]["answer"],
                "intent": intent, "source": "faq", "faq_score": round(results[0]["score"], 3),
                "sentiment": sentiment}

    # 5) 兜底
    if sentiment["label"] == "angry":
        return {"text": "理解您的心情, 让我为您转接人工客服妥善处理",
                "intent": intent, "source": "fallback", "action": "transfer_to_human",
                "sentiment": sentiment}
    if last_user.strip().endswith(("?", "？")):
        return {"text": "这是个很好的问题, 建议您: 1) 看看常见问题; 2) 描述更具体些; 3) 或转人工客服",
                "intent": intent, "source": "fallback", "sentiment": sentiment}
    return {"text": "抱歉没理解您的问题, 换个说法试试? 或回复【人工】转接客服",
            "intent": intent, "source": "fallback", "sentiment": sentiment}


# ========================================================================
# FastAPI DTO
# ========================================================================

class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    messages: List[ChatMessage]
    temperature: float = 0.7
    max_tokens: int = 1024
    user: Optional[str] = None


class EmbedRequest(BaseModel):
    text: str


class SentimentRequest(BaseModel):
    text: str


class UnderstandImageRequest(BaseModel):
    image_url: str
    prompt: str = "描述这张图片"


# ========================================================================
# 路由
# ========================================================================

@app.get("/health")
def health():
    return "UP"


@app.post("/chat")
def chat(req: ChatRequest):
    t0 = time.time()
    try:
        msgs = [m.dict() for m in req.messages]
        result = decide_response(msgs)
        # 估算 token (粗略: 1 token ≈ 1.5 汉字)
        prompt_tokens = int(sum(len(m.get("content", "")) for m in msgs) / 1.5)
        completion_tokens = int(len(result["text"]) / 1.5)
        return {
            "content": result["text"],
            "finish_reason": "stop",
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "latency_ms": int((time.time() - t0) * 1000),
            "_meta": {                                    # 自研 AI 特有: 可解释
                "intent": result.get("intent"),
                "source": result.get("source"),
                "action": result.get("action"),
                "sentiment": result.get("sentiment", {}),
            },
        }
    except Exception as e:
        log.exception("chat failed")
        raise HTTPException(500, f"chat failed: {e}")


@app.post("/embed")
def embed(req: EmbedRequest):
    try:
        return {"vector": embed_text(req.text, dim=256)}
    except Exception as e:
        log.exception("embed failed")
        raise HTTPException(500, f"embed failed: {e}")


@app.post("/sentiment")
def sentiment(req: SentimentRequest):
    try:
        return analyze_sentiment(req.text)
    except Exception as e:
        log.exception("sentiment failed")
        raise HTTPException(500, f"sentiment failed: {e}")


@app.post("/understand-image")
def understand_image(req: UnderstandImageRequest):
    """阶段 1 简化版: 不做真实 CV, 返结构化提示"""
    return {
        "text": "已收到您的图片. 请用文字描述一下您看到的内容, 我会帮您解答.",
        "_meta": {"mode": "stub", "reason": "v1 不含 OCR/CV, 阶段 2 加 pytesseract"},
    }


@app.post("/tts")
def tts(req: dict):
    """阶段 1: 不做服务端 TTS, 返给前端用 Web Speech API 合成"""
    text = req.get("text", "")
    return {
        "_mode": "client_side",
        "text": text,
        "voice": req.get("voice_id", "default"),
        "hint": "前端用 window.speechSynthesis 合成, 不调服务端",
    }


@app.post("/asr")
def asr(req: dict):
    """阶段 1: 不做服务端 ASR, 返给前端用 Web Speech API 转写"""
    return {
        "_mode": "client_side",
        "hint": "前端用 window.SpeechRecognition / webkitSpeechRecognition, 转写后送 /chat",
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8085)
