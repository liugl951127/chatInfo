/**
 * successApi - 客户成功 API.
 */
import http from './axios'

export const successApi = {
  async myHealth() {
    const r = await http.get('/api/success/health/me')
    return r.data
  },
  async compute(activeDays = 7, avgCsat = 4.0) {
    const r = await http.post('/api/success/health/compute', { activeDays, avgCsat })
    return r.data
  },
  async history() {
    const r = await http.get('/api/success/health/me/history')
    return r.data
  },
  async agentStats(agentId) {
    const r = await http.get('/api/success/agent-stats', { params: agentId ? { agentId } : {} })
    return r.data
  },
}