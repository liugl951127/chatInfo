/**
 * 响应式布局 hook (从 Customer/Agent 拆出).
 *  - isMobile: < 768px (移动端布局)
 *  - drawerVisible: 移动端侧栏抽屉
 *  - previewImageUrl: 全屏图片预览
 *  - onResize 监听 window 变化
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'

export function useResponsive() {
  const isMobile = ref(false)
  const drawerVisible = ref(false)
  const previewImageUrl = ref(null)

  let resizeHandler = null
  const checkIsMobile = () => {
    isMobile.value = window.innerWidth < 768
  }

  onMounted(() => {
    checkIsMobile()
    resizeHandler = checkIsMobile
    window.addEventListener('resize', resizeHandler)
  })

  onBeforeUnmount(() => {
    if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  })

  return { isMobile, drawerVisible, previewImageUrl }
}