/**
 * communityApi - 客户社区 API.
 */
import http from './axios'

export const communityApi = {
  async createPost(title, content, category) {
    const r = await http.post('/api/community/posts', { title, content, category })
    return r.data
  },
  async listRecent(limit = 20) {
    const r = await http.get('/api/community/posts', { params: { limit } })
    return r.data
  },
  async listByCategory(category, limit = 20) {
    const r = await http.get(`/api/community/posts/category/${category}`, { params: { limit } })
    return r.data
  },
  async get(id) {
    const r = await http.get(`/api/community/posts/${id}`)
    return r.data
  },
  async reply(postId, content, parentId = null) {
    const r = await http.post(`/api/community/posts/${postId}/reply`, { content, parentId })
    return r.data
  },
  async listReplies(postId) {
    const r = await http.get(`/api/community/posts/${postId}/replies`)
    return r.data
  },
  async like(id) {
    const r = await http.post(`/api/community/posts/${id}/like`)
    return r.data
  },
  /** 接受某条回复为最佳答案 (仅发贴人) */
  async acceptReply(replyId) {
    const r = await http.post(`/api/community/replies/${replyId}/accept`)
    return r.data
  },
}