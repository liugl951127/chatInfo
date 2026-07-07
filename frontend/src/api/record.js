import axios from 'axios'
import http from './axios'
import { useUserStore } from '@/stores/user'

// 专用于下载二进制的 axios 实例 (不走统一 response 拦截器)
const blobHttp = axios.create({ baseURL: '/', timeout: 30000 })
blobHttp.interceptors.request.use((config) => {
  const user = useUserStore()
  if (user.token) config.headers.Authorization = `Bearer ${user.token}`
  return config
})

export const recordApi = {
  init: (sessionId, consent) =>
    http.post('/record/init', null, { params: { sessionId, consent } }),

  uploadChunk: (recordId, sequenceNo, durationMs, file, onProgress) => {
    const fd = new FormData()
    fd.append('file', file, sequenceNo + '.webm')
    return http.post('/record/chunk', fd, {
      params: { recordId, sequenceNo, durationMs },
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: onProgress,
    })
  },

  end: (recordId, endReason) =>
    http.post('/record/end', null, { params: { recordId, endReason } }),

  listBySession: (sessionId) =>
    http.get('/record/session/' + sessionId, { params: { _t: Date.now() } }),

  sessionRecords: (sessionId) =>
    http.get('/record/session/' + sessionId + '/with-chunks', { params: { _t: Date.now() } }),

  recordChunks: (recordId) =>
    http.get('/record/' + recordId + '/chunks', { params: { _t: Date.now() } }),

  downloadChunkBlob: (chunkId) =>
    blobHttp.get('/record/chunk/' + chunkId + '/raw', { responseType: 'blob' })
      .then(resp => resp.data),
}