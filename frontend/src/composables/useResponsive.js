/**
 * useResponsive.js - 响应式布局 composable.
 * ----------------------------------------------------------------------------
 * 用途: Customer/Agent 共享的响应式状态 (H5 移动端优先设计).
 *
 * Returns:
 *   - isMobile: Ref<boolean>          < 768px 走移动端布局
 *   - drawerVisible: Ref<boolean>     移动端侧栏抽屉
 *   - previewImageUrl: Ref<string>    全屏图片预览 (el-image-viewer)
 *
 * 生命周期:
 *   - onMounted: 检查窗口宽度 + 监听 resize
 *   - onBeforeUnmount: 清理 resize 监听
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