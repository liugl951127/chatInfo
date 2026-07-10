/**
 * voiceApi - 智能电话 API.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - init(callee, aiEnabled): 发起通话
 *   - answer(id): 接听
 *   - asr(id, audioBlob): ASR + AI 决策 + TTS
 *   - end(id): 挂断
 *   - active(): 我的活跃通话
 *   - get(id): 详情
 */
import http from './axios'

export const voiceApi = {
  async init(callee, aiEnabled = true) {
    const r = await http.post('/api/voice/init', { callee, aiEnabled })
    return r.data
  },
  async answer(id) {
    const r = await http.post(`/api/voice/${id}/answer`)
    return r.data
  },
  async asr(id, audioBlob) {
    const fd = new FormData()
    fd.append('audio', audioBlob, 'voice.webm')
    const r = await http.post(`/api/voice/${id}/asr`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    return r.data
  },
  async end(id) {
    const r = await http.post(`/api/voice/${id}/end`)
    return r.data
  },
  async active() {
    const r = await http.get('/api/voice/active')
    return r.data
  },
  async get(id) {
    const r = await http.get(`/api/voice/${id}`)
    return r.data
  },
}