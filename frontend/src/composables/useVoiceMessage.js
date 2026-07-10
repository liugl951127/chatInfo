/**
 * 语音消息 (VOICE) 解析.
 *  - VOICE 消息内容是 JSON {url, seconds, mimeType}
 *  - parseVoiceUrl(content) -> audio src URL
 *  - parseVoiceSeconds(content) -> 秒数
 *  - 失败/空内容时返空串/0, 不抛异常 (容错)
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