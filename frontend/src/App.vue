<template>
  <router-view />
  <GlobalLoading />
  <!-- PWA 离线 / 更新提示 -->
  <transition name="pwa-fade">
    <div v-if="pwaOfflineReady" class="pwa-toast">
      <el-icon><CircleCheck /></el-icon>
      <span>应用已就绪 · 支持离线访问</span>
    </div>
  </transition>
  <transition name="pwa-fade">
    <div v-if="pwaUpdateAvailable" class="pwa-toast pwa-update">
      <el-icon><Refresh /></el-icon>
      <span>新版本可用</span>
      <el-button size="small" type="primary" link @click="applyUpdate">立即更新</el-button>
      <el-button size="small" link @click="pwaUpdateAvailable = false">×</el-button>
    </div>
  </transition>
</template>

<script setup>
import { ref } from 'vue'
import { CircleCheck, Refresh } from '@element-plus/icons-vue'
import { usePwa } from '@/composables/usePwa'
import GlobalLoading from '@/components/common/GlobalLoading.vue'
import OfflineQueueIndicator from '@/components/chat/OfflineQueueIndicator.vue'

const pwaOfflineReady = ref(false)
const pwaUpdateAvailable = ref(false)

const { swRegistered, offlineReady, updateAvailable, applyUpdate } = usePwa()

// 监测状态变化
import { watch } from 'vue'
watch(offlineReady, v => { if (v) pwaOfflineReady.value = true; setTimeout(() => pwaOfflineReady.value = false, 3000) })
watch(updateAvailable, v => { if (v) pwaUpdateAvailable.value = true })
</script>

<style>
:root {
  --safe-top: env(safe-area-inset-top, 0px);
  --safe-bottom: env(safe-area-inset-bottom, 0px);
  --safe-top-legacy: constant(safe-area-inset-top, 0px);
  --safe-bottom-legacy: constant(safe-area-inset-bottom, 0px);
}

html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  background: #f5f7fa;
  -webkit-tap-highlight-color: transparent;
  -webkit-touch-callout: none;
  overscroll-behavior-y: none;
}

@media (max-width: 768px) {
  html { font-size: 16px; }
  body { font-size: 14px; }
}
@media (min-width: 769px) {
  html { font-size: 14px; }
}

.scroll-smooth { -webkit-overflow-scrolling: touch; }

@media (max-width: 480px) {
  .el-message { min-width: 280px !important; max-width: 90vw !important; }
  .el-dialog { width: 92vw !important; }
  .el-rate { font-size: 28px !important; }
}

/* PWA 提示 */
.pwa-toast {
  position: fixed;
  bottom: 20px;
  right: 20px;
  z-index: 9999;
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px;
  background: rgba(67, 160, 71, 0.95);
  backdrop-filter: blur(10px);
  color: #fff;
  border-radius: 24px;
  font-size: 13px;
  font-weight: 500;
  box-shadow: 0 6px 20px rgba(67, 160, 71, 0.3);
}
.pwa-toast.pwa-update {
  background: rgba(64, 158, 255, 0.95);
  box-shadow: 0 6px 20px rgba(64, 158, 255, 0.3);
}
.pwa-toast .el-icon { font-size: 18px; }
.pwa-toast .el-button { color: #fff; }

.pwa-fade-enter-active, .pwa-fade-leave-active { transition: all 0.3s ease; }
.pwa-fade-enter-from, .pwa-fade-leave-to { opacity: 0; transform: translateY(20px); }
</style>