<script setup>
/**
 * ConnectionBanner.vue - 连接状态横幅.
 * 显示在页面顶部, 提示网络/服务异常.
 */
import { computed } from 'vue'

const props = defineProps({
  online: { type: Boolean, default: true },
  reconnecting: { type: Boolean, default: false },
  stompConnected: { type: Boolean, default: true },
})

const visible = computed(() => !props.online || props.reconnecting || !props.stompConnected)
const text = computed(() => {
  if (!props.online) return '网络已断开, 部分功能不可用'
  if (props.reconnecting) return '正在重连服务器...'
  if (!props.stompConnected) return '消息服务连接中...'
  return ''
})
const type = computed(() => !props.online ? 'error' : 'warning')
</script>

<template>
  <Transition name="banner-fade">
    <div v-if="visible" class="connection-banner" :class="`b-${type}`">
      <el-icon v-if="reconnecting" class="is-loading"><Loading /></el-icon>
      <span>{{ text }}</span>
    </div>
  </Transition>
</template>

<style scoped>
.connection-banner {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  padding: 8px 16px; font-size: 13px; font-weight: 500;
  position: sticky; top: 0; z-index: 100;
}
.b-error { background: #fef0f0; color: #f56c6c; border-bottom: 1px solid #fde2e2; }
.b-warning { background: #fdf6ec; color: #e6a23c; border-bottom: 1px solid #faecd8; }
.banner-fade-enter-active, .banner-fade-leave-active { transition: all 0.2s; }
.banner-fade-enter-from, .banner-fade-leave-to { opacity: 0; transform: translateY(-100%); }
</style>