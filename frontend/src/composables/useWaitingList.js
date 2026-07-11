/**
 * useWaitingList.js - 等候队列 composable.
 * ----------------------------------------------------------------------------
 * 业务: 坐席侧实时拉取等候会话, 支持抢单 + 防串线.
 *
 * 轮询: 5s 拉一次 (轻量)
 * CAS 防串线: 后端 409 → 提示已被他人接起
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { imApi } from '@/api/im'
import { ElMessage } from 'element-plus'

export function useWaitingList() {
  const list = ref([])
  const loading = ref(false)
  const claiming = ref(null)  // 当前正在抢的 sessionId
  const error = ref(null)
  let timer = null

  async function fetchList() {
    loading.value = true
    error.value = null
    try {
      const r = await imApi.waitingList()
      list.value = r?.data || r || []
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  /**
   * 抢单 (CAS):
   *   1) 设置 claiming = sessionId
   *   2) 调 imApi.claimSession(id)
   *   3) 成功 → emit claimed
   *   4) 409 → 提示 "已被 #X 接起"
   *   5) 清理 claiming
   */
  async function claim(sessionId = null) {
    claiming.value = sessionId || true
    try {
      const r = await imApi.claimSession(sessionId)
      // 成功: 刷新列表
      await fetchList()
      return r
    } catch (e) {
      // 409: 防串线
      if (e.code === 409 || e.response?.status === 409) {
        ElMessage.warning(e.message || '已被他人接起')
        await fetchList()
      } else {
        ElMessage.error(e.message || '抢单失败')
      }
      throw e
    } finally {
      claiming.value = null
    }
  }

  function start(intervalMs = 5000) {
    if (timer) return
    fetchList()
    timer = setInterval(fetchList, intervalMs)
  }

  function stop() {
    if (timer) { clearInterval(timer); timer = null }
  }

  onMounted(() => start())
  onUnmounted(() => stop())

  return {
    list, loading, claiming, error,
    fetchList, claim, start, stop,
  }
}