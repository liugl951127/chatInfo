<script setup>
/**
 * GlobalLoading.vue - 全局 loading 遮罩.
 * 接 stores/loading, 自动显示/隐藏.
 */
import { useLoadingStore } from '@/stores/loading'
const loading = useLoadingStore()
</script>

<template>
  <Transition name="loading-fade">
    <div v-if="loading.visible" class="global-loading">
      <div class="loading-box">
        <el-icon class="is-loading" :size="36"><Loading /></el-icon>
        <div class="loading-text">{{ loading.text }}</div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.global-loading {
  position: fixed; inset: 0; z-index: 9999;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(8px);
  display: flex; align-items: center; justify-content: center;
}
.loading-box {
  display: flex; flex-direction: column; align-items: center; gap: 16px;
  padding: 32px 48px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12);
}
.loading-text { font-size: 14px; color: #606266; }
.loading-fade-enter-active, .loading-fade-leave-active { transition: opacity 0.2s; }
.loading-fade-enter-from, .loading-fade-leave-to { opacity: 0; }
</style>