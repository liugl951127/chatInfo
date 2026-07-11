/**
 * realtimeApi - 实时监控 API.
 */
import http from './axios'

export const realtimeApi = {
  /** 大屏实时统计 */
  async getStats() {
    const r = await http.get('/api/success/realtime')
    return r.data
  },
}