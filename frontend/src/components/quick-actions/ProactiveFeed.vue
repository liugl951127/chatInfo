<script setup>
/**
 * ProactiveFeed.vue - AI 主动推送流 (顶部下滑).
 * ----------------------------------------------------------------------------
 * v6 客户体验: 顶部下拉显示 AI 主动推送的卡片 (订单/关怀/促销).
 *
 * Props:
 *   - feed: ProactiveItem[]     推送流
 *
 * Emits:
 *   - dismiss(id): 关闭某条
 *   - action(item): 用户点击推送卡片
 */
import { computed } from 'vue'

const props = defineProps({
  feed: { type: Array, default: () => [] },
})

const emit = defineEmits(['dismiss', 'action'])

const visible = computed(() => props.feed.slice(0, 3))  // 最多同时显示 3 条

function iconFor(actionType) {
  switch (actionType) {
    case 'PUSH': return '📬'
    case 'SESSION_INVITE': return '💬'
    case 'EMAIL': return '📧'
    default: return '🔔'
  }
}
</script>

<template>
  <div class="proactive-feed">
    <TransitionGroup name="proactive">
      <div v-for="item in visible" :key="item.id" class="proactive-card"
           :class="{ 'proactive-session': item.actionType === 'SESSION_INVITE' }">
        <div class="pc-icon">{{ iconFor(item.actionType) }}</div>
        <div class="pc-body" @click="emit('action', item)">
          <div class="pc-title">{{ item.ruleName || 'AI 推送' }}</div>
          <div class="pc-text">{{ item.text }}</div>
        </div>
        <el-button class="pc-close" link @click.stop="emit('dismiss', item.id)">
          ✕
        </el-button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.proactive-feed {
  position: sticky; top: 0; z-index: 100;
  padding: 8px 12px 0;
  display: flex; flex-direction: column; gap: 6px;
  pointer-events: none;
}
.proactive-card {
  pointer-events: auto;
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px;
  background: linear-gradient(135deg, #fff8e1, #fff3cd);
  border: 1px solid #ffe58f;
  border-radius: 10px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
  cursor: pointer;
  transition: transform 0.2s;
}
.proactive-card:hover { transform: translateY(-1px); }
.proactive-session {
  background: linear-gradient(135deg, #e1f5fe, #b3e5fc);
  border-color: #4fc3f7;
}
.pc-icon { font-size: 24px; flex-shrink: 0; }
.pc-body { flex: 1; min-width: 0; }
.pc-title {
  font-size: 12px; color: #909399; margin-bottom: 2px;
}
.pc-text {
  font-size: 14px; color: #303133;
  overflow: hidden; text-overflow: ellipsis;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
}
.pc-close { color: #c0c4cc; }

.proactive-enter-active, .proactive-leave-active {
  transition: all 0.3s;
}
.proactive-enter-from {
  opacity: 0; transform: translateY(-20px);
}
.proactive-leave-to {
  opacity: 0; transform: translateX(100%);
}
</style>