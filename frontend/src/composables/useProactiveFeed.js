/**
 * useProactiveFeed.js - 主动推送流 composable.
 * ----------------------------------------------------------------------------
 * 用途: 客户页面监听 /queue/events 的 PREDICTION 事件, 维护"主动推送流".
 *
 * Returns:
 *   - feed: Ref<ProactiveItem[]>     推送流 (新事件 unshift 到头部)
 *   - dismiss(id): 关闭某条
 *   - clear(): 清空全部
 *   - unreadCount: Ref<number>       未读推送数 (右上角红点)
 */
import { ref, computed } from 'vue'

export function useProactiveFeed() {
  /** 推送流 (最新在前) */
  const feed = ref([])

  /** 各条已读状态 (dismiss/clicked/expired) */
  const dismissed = ref(new Set())

  /** 未读数 */
  const unreadCount = computed(() => feed.value.filter(f => !dismissed.value.has(f.id)).length)

  /**
   * 推入一条 PREDICTION 事件.
   * @param payload 后端 STOMP 推送的 payload
   *   { type: 'PREDICTION', ruleCode, ruleName, actionType, text, ts }
   */
  function push(payload) {
    if (!payload || payload.type !== 'PREDICTION') return
    const item = {
      id: `${payload.ruleCode}-${payload.ts || Date.now()}`,
      ruleCode: payload.ruleCode,
      ruleName: payload.ruleName,
      actionType: payload.actionType,   // PUSH / SESSION_INVITE / EMAIL
      text: payload.text,
      ts: payload.ts || Date.now(),
    }
    // 同 ruleCode 已有未读 -> 合并 (避免重复)
    const existing = feed.value.find(f => f.ruleCode === item.ruleCode && !dismissed.value.has(f.id))
    if (existing) {
      existing.ts = item.ts
      existing.text = item.text
    } else {
      feed.value.unshift(item)
    }
  }

  /** 关闭一条 */
  function dismiss(id) {
    dismissed.value.add(id)
    dismissed.value = new Set(dismissed.value)  // 触发响应式
  }

  /** 清空 */
  function clear() {
    feed.value = []
    dismissed.value = new Set()
  }

  return { feed, unreadCount, push, dismiss, clear }
}