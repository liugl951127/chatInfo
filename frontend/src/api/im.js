import http from './axios'

export const imApi = {
  // 会话
  createSession: (skill) => http.post('/api/im/session/create', null, { params: { skill } }),
  claimSession:  () => http.post('/api/im/session/claim'),
  transferSession: (sessionId, toAgentId, reason) =>
    http.post(`/api/im/session/${sessionId}/transfer`, null, { params: { toAgentId, reason } }),
  rateSession:   (sessionId, rating, comment) =>
    http.post(`/api/im/session/${sessionId}/rate`, null, { params: { rating, comment } }),
  mySessions:    () => http.get('/api/im/session/mine'),
  waitingList:   () => http.get('/api/im/session/waiting'),
  closeSession:  (id) => http.post(`/api/im/session/${id}/close`),
  unread:        (sessionId) => http.get(`/api/im/session/${sessionId}/unread`),

  // 启动
  bootstrap:     () => http.get('/api/im/session/bootstrap'),
  drainOffline:  () => http.get('/api/im/session/offline/drain'),

  // 历史
  history: (sessionId, limit = 50) =>
    http.get(`/api/im/session/${sessionId}/messages`, { params: { limit } }),

  // 消息操作
  recallMessage:  (messageId) => http.post(`/api/im/session/message/${messageId}/recall`),
  readMessage:    (messageId) => http.post(`/api/im/session/message/${messageId}/read`),
  readAll:        (sessionId) => http.post(`/api/im/session/${sessionId}/read-all`),

  // 坐席状态
  setAgentStatus: (status) => http.post('/api/im/session/agent/status', null, { params: { status } }),
  getAgentStatus: () => http.get('/api/im/session/agent/status'),

  // 快捷回复
  listCanned:    (skill) => http.get('/api/im/canned/list', { params: { skill } }),
  createCanned:  (skill, title, content) =>
    http.post('/api/im/canned/create', null, { params: { skill, title, content } }),
  deleteCanned:  (id) => http.delete(`/api/im/canned/${id}`),

  // 坐席列表 (转接用)
  listAgents:    () => http.get('/api/im/agent/list')
}