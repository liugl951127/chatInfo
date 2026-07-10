/**
 * videoApi - 视频会话 API.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - init(chatSessionId, peerId): 创建会话
 *   - active(): 我的活跃会话
 *   - end(id): 结束
 *   - get(id): 详情
 *   - linkRecord(id, recordId): 关联录像
 */
import http from './axios'

export const videoApi = {
  async init(chatSessionId, peerId) {
    const r = await http.post('/api/video/init', { chatSessionId, peerId })
    return r.data
  },
  async active() {
    const r = await http.get('/api/video/active')
    return r.data
  },
  async end(id) {
    const r = await http.post(`/api/video/${id}/end`)
    return r.data
  },
  async get(id) {
    const r = await http.get(`/api/video/${id}`)
    return r.data
  },
  async linkRecord(id, recordId) {
    const r = await http.post(`/api/video/${id}/record`, { recordId })
    return r.data
  },
}