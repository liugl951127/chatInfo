/**
 * aiApi - Java 自研 AI (cs-ai 模块) API 客户端.
 * ----------------------------------------------------------------------------
 * 后端 5 个端点 (cs-ai 服务, 默认 8087):
 *   - POST /api/ai/chat       自由对话 (基于关键词 + 模板)
 *   - POST /api/ai/sentiment  情感分析 (Java 自研, 0 依赖)
 *   - GET  /api/ai/faq        FAQ 检索 (TF-IDF 相似度)
 *   - POST /api/ai/intent     意图识别 (规则匹配)
 *   - POST /api/ai/embed      TF-IDF 向量化 (256 维)
 *
 * 阶段 1: 直接 HTTP 调用, 阶段 2: 走 Agent 网关.
 */
import http from './axios'

export const aiApi = {
  /** 自由对话 (prompt → AI 回复) */
  async chat(prompt, context = {}) {
    const r = await http.post('/api/ai/chat', { prompt, context })
    return r.data
  },

  /** 情感分析 (text → { sentiment, score, confidence }) */
  async sentiment(text) {
    const r = await http.post('/api/ai/sentiment', { text })
    return r.data
  },

  /** FAQ 检索 (query → [hits]) */
  async faq(q, topK = 5) {
    const r = await http.get('/api/ai/faq', { params: { q, topK } })
    return r.data
  },

  /** 意图识别 (text → { intent, confidence }) */
  async intent(text) {
    const r = await http.post('/api/ai/intent', { text })
    return r.data
  },

  /** TF-IDF 向量化 (text → float[256]) */
  async embed(text) {
    const r = await http.post('/api/ai/embed', { text })
    return r.data
  },
}