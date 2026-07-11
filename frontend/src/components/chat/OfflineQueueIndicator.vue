<script setup>
/**
 * OfflineQueueIndicator.vue - 离线队列指示器 (V3.1 增强).
 * 显示离线录像分片数 + 手动同步按钮.
 */
import { useOfflineQueue } from '@/composables/useOfflineQueue'
const { pending, syncing, flush } = useOfflineQueue()
</script>

<template>
  <Transition name="fade">
    <div v-if="pending > 0" class="offline-indicator">
      <span class="icon">📦</span>
      <span class="text">离线队列: {{ pending }} 个分片</span>
      <el-button size="small" type="primary" :loading="syncing" @click="flush">
        立即同步
      </el-button>
    </div>
  </Transition>
</template>

<style scoped>
.offline-indicator {
  position: fixed;
  bottom: 20px;
  left: 20px;
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px;
  background: rgba(230, 162, 60, 0.95);
  backdrop-filter: blur(10px);
  color: #fff;
  border-radius: 24px;
  font-size: 13px;
  font-weight: 500;
  box-shadow: 0 6px 20px rgba(230, 162, 60, 0.3);
  z-index: 9999;
}
.icon { font-size: 18px; }
.fade-enter-active, .fade-leave-active { transition: all 0.3s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; transform: translateY(20px); }
</style>
