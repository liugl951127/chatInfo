# M3 Adapter (MiniMax-M3 Python Service)

**职责**: 把 M3 的工具能力包装成 HTTP API, 给 Java 后端调用.

**部署**:
```bash
cd backend/m3-adapter
pip install -r requirements.txt
uvicorn m3_server:app --host 0.0.0.0 --port 8084
```

**接口** (Java 端 HttpM3Adapter 调这些):

| Method | Path | Request | Response |
|--------|------|---------|----------|
| GET    | /health | - | "UP" |
| POST   | /chat | `{messages, tools, model, temperature, max_tokens, user}` | `{content, finish_reason, prompt_tokens, completion_tokens}` |
| POST   | /embed | `{text}` | `{vector: [..1024 floats]}` |
| POST   | /embed-batch | `{texts: [..]}` | `{vectors: [[..]]}` |
| POST   | /tts | `{text, voice_id, speed, volume, pitch, emotion, format}` | mp3 bytes |
| POST   | /asr | `{audio_b64}` | `{text}` |
| POST   | /understand-image | `{image_url, prompt}` | `{text}` |
| POST   | /sentiment | `{text}` | `{score, label, confidence}` |

**阶段 1 实现策略** (用 M3 工具):
- chat: 用主对话 + Function Calling
- embed: 用 M3 的文本向量化能力 (基于 sentences)
- tts: 用 `synthesize_speech` / `batch_synthesize_speech`
- asr: 用 `listen_audio` / `audios_understand`
- understandImage: 用 `image_synthesize` + 反向搜索 + M3 描述
- sentiment: 用主对话 + 提示词做情绪分类

**fallback**: 当 M3 不可达时, 用启发式规则 (关键词匹配) 兜底.
