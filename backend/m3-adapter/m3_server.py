"""
m3_server.py - MiniMax-M3 Python Adapter (FastAPI)
把 M3 的工具能力包装成 HTTP API.
"""
import os
import io
import base64
import time
from typing import List, Optional, Dict, Any
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="M3 Adapter", version="0.1.0")


# ==================== 健康检查 ====================

@app.get("/health")
def health():
    return "UP"


# ==================== DTO ====================

class ChatMessage(BaseModel):
    role: str
    content: str
    name: Optional[str] = None


class Tool(BaseModel):
    name: str
    description: str
    parameters: Dict[str, Any]


class ChatRequest(BaseModel):
    messages: List[ChatMessage]
    tools: Optional[List[Tool]] = None
    model: str = "minimax3"
    temperature: float = 0.7
    max_tokens: int = 1024
    user: Optional[str] = None


class ChatResponse(BaseModel):
    content: str
    finish_reason: str = "stop"
    prompt_tokens: int = 0
    completion_tokens: int = 0


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    vector: List[float]


class EmbedBatchRequest(BaseModel):
    texts: List[str]


class EmbedBatchResponse(BaseModel):
    vectors: List[List[float]]


class TtsRequest(BaseModel):
    text: str
    voice_id: str = "male-qn-qingse"
    speed: float = 1.0
    volume: float = 1.0
    pitch: int = 0
    emotion: str = "neutral"
    format: str = "mp3"


class AsrRequest(BaseModel):
    audio_b64: str


class AsrResponse(BaseModel):
    text: str


class UnderstandImageRequest(BaseModel):
    image_url: str
    prompt: str = "描述这张图片"


class SentimentRequest(BaseModel):
    text: str


class SentimentResponse(BaseModel):
    score: float
    label: str
    confidence: float


# ==================== 实际能力 (调 M3 工具) ====================

def call_m3_chat(req: ChatRequest) -> ChatResponse:
    """调 M3 主对话工具生成回复.

    阶段 1: 用启发式 fallback (M3 工具直调接口待补).
    阶段 2: 替换为实际 M3 API.
    """
    # 提取最后一条 user 消息
    user_msg = ""
    for m in reversed(req.messages):
        if m.role == "user":
            user_msg = m.content
            break

    # 简单兜底: 关键词回显
    return ChatResponse(
        content=f"[m3-fallback] 收到: {user_msg[:100]}",
        finish_reason="stop",
        prompt_tokens=len(user_msg),
        completion_tokens=50,
    )


def call_m3_embed(text: str) -> EmbedResponse:
    """调 M3 embedding.

    阶段 1: 用 hash 生成 1024 维伪向量 (后续替换).
    """
    import hashlib
    h = hashlib.sha512(text.encode("utf-8")).digest()
    # 扩展到 1024 维 (重复 + 偏移)
    vec = []
    for i in range(1024):
        b = h[i % len(h)]
        vec.append((b - 128) / 128.0)
    return EmbedResponse(vector=vec)


def call_m3_tts(req: TtsRequest) -> bytes:
    """调 M3 synthesize_speech.

    阶段 1: 占位返回静音 (后续接 batch_text_to_audio).
    """
    # 10ms 静音 mp3 frame (简化)
    return b"\xff\xfb\x90\x00" + b"\x00" * 100


def call_m3_asr(audio_b64: str) -> AsrResponse:
    """调 M3 listen_audio.

    阶段 1: 占位返回 (后续接 audios_understand).
    """
    return AsrResponse(text="[m3-asr-fallback] (音频转写待接)")


def call_m3_understand_image(req: UnderstandImageRequest) -> Dict:
    """调 M3 多模态图片理解.

    阶段 1: 占位.
    """
    return {"text": f"[m3-image-fallback] image_url={req.image_url[:50]}"}


def call_m3_sentiment(req: SentimentRequest) -> SentimentResponse:
    """调 M3 情感分析.

    阶段 1: 关键词启发式.
    """
    text = req.text or ""
    neg_kw = ["投诉", "差评", "退款", "退钱", "垃圾", "骗子", "生气", "愤怒", "失望", "烦"]
    pos_kw = ["谢谢", "感谢", "满意", "好", "棒", "优秀", "nice", "good", "love"]

    score = 0.0
    for kw in neg_kw:
        if kw in text: score -= 0.3
    for kw in pos_kw:
        if kw in text: score += 0.3
    score = max(-1.0, min(1.0, score))

    if score < -0.3:
        label = "angry"
    elif score < 0:
        label = "sad"
    elif score > 0.3:
        label = "happy"
    else:
        label = "neutral"

    return SentimentResponse(score=score, label=label, confidence=0.6)


# ==================== 路由 ====================

@app.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest):
    try:
        return call_m3_chat(req)
    except Exception as e:
        raise HTTPException(500, f"m3 chat failed: {e}")


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    try:
        return call_m3_embed(req.text)
    except Exception as e:
        raise HTTPException(500, f"m3 embed failed: {e}")


@app.post("/embed-batch", response_model=EmbedBatchResponse)
def embed_batch(req: EmbedBatchRequest):
    try:
        vectors = [call_m3_embed(t).vector for t in req.texts]
        return EmbedBatchResponse(vectors=vectors)
    except Exception as e:
        raise HTTPException(500, f"m3 embed-batch failed: {e}")


@app.post("/tts", response_class=bytes)
def tts(req: TtsRequest):
    try:
        return call_m3_tts(req)
    except Exception as e:
        raise HTTPException(500, f"m3 tts failed: {e}")


@app.post("/asr", response_model=AsrResponse)
def asr(req: AsrRequest):
    try:
        return call_m3_asr(req.audio_b64)
    except Exception as e:
        raise HTTPException(500, f"m3 asr failed: {e}")


@app.post("/understand-image")
def understand_image(req: UnderstandImageRequest):
    try:
        return call_m3_understand_image(req)
    except Exception as e:
        raise HTTPException(500, f"m3 understand-image failed: {e}")


@app.post("/sentiment", response_model=SentimentResponse)
def sentiment(req: SentimentRequest):
    try:
        return call_m3_sentiment(req)
    except Exception as e:
        raise HTTPException(500, f"m3 sentiment failed: {e}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8084)
