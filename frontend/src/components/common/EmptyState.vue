<script setup>
/**
 * EmptyState.vue - 通用空状态组件.
 * 替代散落的空状态 div, 统一视觉.
 */
defineProps({
  icon: { type: String, default: '📭' },       // emoji 或 icon
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' },
  actionText: { type: String, default: '' },    // 按钮文字
  variant: { type: String, default: 'default' }, // default | primary | success
})
const emit = defineEmits(['action'])
</script>

<template>
  <div class="empty-state" :class="`v-${variant}`">
    <div class="empty-icon">{{ icon }}</div>
    <div class="empty-title">{{ title }}</div>
    <div v-if="description" class="empty-desc">{{ description }}</div>
    <el-button v-if="actionText" type="primary" round size="default" @click="emit('action')">
      {{ actionText }}
    </el-button>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 60px 20px; min-height: 240px;
  text-align: center;
}
.empty-icon { font-size: 72px; margin-bottom: 16px; opacity: 0.85; }
.empty-title { font-size: 16px; color: #606266; font-weight: 500; margin-bottom: 6px; }
.empty-desc { font-size: 13px; color: #909399; max-width: 320px; line-height: 1.6; margin-bottom: 16px; }
.v-primary .empty-title { color: #409EFF; }
.v-success .empty-title { color: #67C23A; }
</style>