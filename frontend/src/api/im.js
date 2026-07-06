import http from './axios'

export const imApi = {
  // 会话
  createSession: () => http.post('/api/im/session/create'),
  claimSession:  () => http.post('/api/im/session/claim'),
  mySessions:    () => http.get('/api/im/session/mine'),
  waitingList:   () => http.get('/api/im/session/waiting'),
  closeSession:  (id) => http.post(`/api/im/session/${id}/close`),

  // 启动
  bootstrap:     () => http.get('/api/im/session/bootstrap'),
  drainOffline:  () => http.get('/api/im/session/offline/drain'),

  // 历史
  history: (sessionId, limit = 50) =>
    http.get(`/api/im/session/${sessionId}/messages`, { params: { limit } })
}