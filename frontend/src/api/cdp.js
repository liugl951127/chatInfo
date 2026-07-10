/**
 * cdpApi - 数字孪生 360 API 客户端.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - getMyProfile(): 我的 360 画像
 *   - getProfile(uid): 任意用户画像 (坐席/管理员)
 *   - recordEvent(type, payload, sessionId): 上报事件
 *   - heartbeat(): 心跳 (更新活跃时间)
 *   - recompute(): 触发画像重算
 */
import http from './axios'

export const cdpApi = {
  async getMyProfile() {
    const r = await http.get('/api/cdp/profile/me')
    return r.data
  },
  async getProfile(uid) {
    const r = await http.get(`/api/cdp/profile/${uid}`)
    return r.data
  },
  async recordEvent(eventType, payload = {}, sessionId = null) {
    return http.post('/api/cdp/event', { eventType, payload, sessionId })
  },
  async heartbeat() {
    return http.post('/api/cdp/active')
  },
  async recompute() {
    return http.post('/api/cdp/recompute')
  },
}