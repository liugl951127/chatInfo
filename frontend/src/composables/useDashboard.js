/**
 * useDashboard.js - 坐席数据看板 composable.
 * ----------------------------------------------------------------------------
 * 职责: 封装 AgentDashboard 的数据加载 + 错误处理 + 缓存.
 *
 * 数据源:
 *   - successApi.getAgentStats(agentId) → 坐席统计 (今日/总)
 *   - 多次调用 debounce + 缓存
 *
 * 状态:
 *   - loading: 是否加载中
 *   - data: { today: {...}, total: {...}, trend: [...] }
 *   - error: 错误信息
 */
import { ref } from 'vue'
import { successApi } from '@/api/success'
import { handleError } from '@/utils/error-handler'

export function useDashboard() {
  const loading = ref(false)
  const data = ref(null)
  const error = ref(null)
  let lastFetch = 0
  const CACHE_MS = 30 * 1000  // 30s 缓存

  async function fetchData(agentId, force = false) {
    if (!agentId) return
    if (!force && Date.now() - lastFetch < CACHE_MS && data.value) {
      return data.value
    }
    loading.value = true
    error.value = null
    try {
      const r = await successApi.getAgentStats(agentId)
      data.value = r?.data || r
      lastFetch = Date.now()
      return data.value
    } catch (e) {
      error.value = e.message || '加载失败'
      handleError(e, { customMessage: '看板数据加载失败' })
    } finally {
      loading.value = false
    }
  }

  function reset() {
    data.value = null
    error.value = null
    lastFetch = 0
  }

  return { loading, data, error, fetchData, reset }
}