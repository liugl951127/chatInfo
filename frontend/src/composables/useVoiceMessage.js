/**
 * useVoiceMessage.js - 语音消息 (VOICE) 解析工具.
 * ----------------------------------------------------------------------------
 * 用途: VOICE 消息的 content 字段是 JSON {url, seconds, mimeType},
 *       MessageBubble 需抽取 url/seconds 用于 <audio> 渲染.
 *
 * API:
 *   - parseVoiceUrl(content)        -> string (audio src URL, 失败返空串)
 *   - parseVoiceSeconds(content)    -> number (秒数, 失败返 0)
 *
 * 容错:
 *   - JSON.parse 失败 → 返空串/0
 *   - content 为 null/undefined → 返空串/0
 *   - 不抛异常, 避免一条坏消息发响整个列表
 */
export function parseVoiceUrl(content) {
  try {
    return JSON.parse(content || '{}').url || ''
  } catch {
    return ''
  }
}

export function parseVoiceSeconds(content) {
  try {
    return JSON.parse(content || '{}').seconds || 0
  } catch {
    return 0
  }
}