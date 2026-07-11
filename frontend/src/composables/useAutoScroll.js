/**
 * useAutoScroll.js - 自动滚动到底部 composable.
 * ----------------------------------------------------------------------------
 * 业务场景: 新消息到达时自动滚动; 用户上滑阅读历史时不要打断.
 *
 * 策略:
 *   - 用户在底部 80px 内 → 滚动
 *   - 用户上滑 (距离底部 > 80px) → 不滚, 显示 "回到最新" 按钮
 *   - 点击按钮 → 强制滚到底
 *
 * 性能: raf 节流, 避免高频抖动
 */
import { ref, watch, nextTick } from 'vue'

const BOTTOM_THRESHOLD = 80  // 距离底部 80px 内认为在底部

export function useAutoScroll(containerRef, listRef, onNeedScroll) {
  const isAtBottom = ref(true)
  const showJumpButton = ref(false)

  function checkAtBottom() {
    const el = containerRef.value
    if (!el) return
    const dist = el.scrollHeight - el.scrollTop - el.clientHeight
    isAtBottom.value = dist <= BOTTOM_THRESHOLD
    showJumpButton.value = !isAtBottom.value
  }

  function scrollToBottom(smooth = true) {
    nextTick(() => {
      const el = containerRef.value
      if (!el) return
      el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
      isAtBottom.value = true
      showJumpButton.value = false
      onNeedScroll?.()
    })
  }

  // 监听消息数变化: 新消息触发
  watch(() => listRef.value?.length, (n, o) => {
    if (n > (o || 0)) {
      // 新消息: 如果在底部, 自动滚; 不在底部, 显示按钮
      if (isAtBottom.value) {
        scrollToBottom()
      }
    }
  })

  return {
    isAtBottom,
    showJumpButton,
    checkAtBottom,
    scrollToBottom,
  }
}