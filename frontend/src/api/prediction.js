/**
 * predictionApi - 预见式服务 API 客户端.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - evaluate(userId, eventType, context): 评估事件 (其他模块调)
 *   - listRules(): 列出所有规则 (管理员)
 *   - getHistory(uid): 某用户触发历史
 */
import http from './axios'

export const predictionApi = {
  async evaluate(userId, eventType, context = {}) {
    const r = await http.post('/api/prediction/evaluate', { userId, eventType, context })
    return r.data
  },
  async listRules() {
    const r = await http.get('/api/prediction/rules')
    return r.data
  },
  async getHistory(uid) {
    const r = await http.get(`/api/prediction/history/${uid}`)
    return r.data
  },
}