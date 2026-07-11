/**
 * useDraft.js - 草稿自动保存 composable.
 * ----------------------------------------------------------------------------
 * 业务场景: 用户输入到一半刷新 / 切换会话 / 断网, 不丢失内容.
 *
 * 策略:
 *   - 每次内容变化 → debounce 500ms → localStorage 写入
 *   - 切换 key (如 sessionId) → 重新加载对应草稿
 *   - 发送成功后 → 清空草稿
 *   - 手动清空: clearDraft()
 *
 * 存储: localStorage 'cs_drafts' (Map<key, value>).
 * 兼容: 单 key (旧版本) 自动迁移.
 */
import { ref, watch, onUnmounted } from 'vue'

const STORAGE_KEY = 'cs_drafts'
const DEBOUNCE_MS = 500
const MAX_DRAFTS = 50  // 最多保留 50 个 key 的草稿

function loadAll() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') return parsed
  } catch (e) { /* ignore */ }
  return {}
}

function saveAll(map) {
  try {
    // 限制大小
    const keys = Object.keys(map)
    if (keys.length > MAX_DRAFTS) {
      // LRU: 只保留最近 MAX_DRAFTS 个
      const sorted = keys.slice(-MAX_DRAFTS)
      const newMap = {}
      sorted.forEach(k => { newMap[k] = map[k] })
      localStorage.setItem(STORAGE_KEY, JSON.stringify(newMap))
    } else {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(map))
    }
  } catch (e) { /* quota exceeded 等 */ }
}

export function useDraft(key, getValue) {
  /** 当前草稿 */
  const draft = ref(loadAll()[key.value] || '')

  let timer = null
  watch([getValue, key], () => {
    // 防抖写入
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => {
      const v = getValue()
      if (!v) return
      const all = loadAll()
      all[key.value] = v
      saveAll(all)
    }, DEBOUNCE_MS)
  }, { immediate: true })

  // 切换 key 时重新加载
  watch(key, (newKey) => {
    draft.value = loadAll()[newKey] || ''
  })

  /** 主动清空 (发送成功后) */
  function clearDraft() {
    draft.value = ''
    const all = loadAll()
    delete all[key.value]
    saveAll(all)
  }

  onUnmounted(() => {
    if (timer) clearTimeout(timer)
  })

  return { draft, clearDraft }
}