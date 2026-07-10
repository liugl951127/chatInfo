<script setup>
/**
 * FloatingActionButton.vue - 浮动操作按钮 (FAB).
 * ----------------------------------------------------------------------------
 * v6 客户体验: 右下角常驻, 一秒触达 5 个核心操作.
 *
 * Props:
 *   - unreadCount: 推送未读数 (显示红点)
 *
 * Emits:
 *   - action(name): 用户点击某个 action
 *     可选: 'chat' / 'transfer-human' / 'scan' / 'orders' / 'profile' / 'community'
 */
import { ref } from 'vue'
import { ChatDotRound, User, Camera, List, House, Pointer } from '@element-plus/icons-vue'

defineProps({
  unreadCount: { type: Number, default: 0 },
})

const emit = defineEmits(['action'])

/** 展开/收起 */
const expanded = ref(false)

/** 5 个核心操作 */
const actions = [
  { name: 'chat',          label: '发起会话', icon: ChatDotRound, color: '#409EFF' },
  { name: 'transfer-human',label: '转人工',  icon: User,         color: '#67C23A' },
  { name: 'scan',          label: '扫一扫',  icon: Camera,       color: '#E6A23C' },
  { name: 'orders',        label: '我的订单', icon: List,         color: '#F56C6C' },
  { name: 'profile',       label: '个人中心', icon: House,        color: '#909399' },
]

function onAction(name) {
  expanded.value = false
  emit('action', name)
}
</script>

<template>
  <div class="fab-container">
    <!-- 展开后的 5 个操作 (从下往上排列) -->
    <Transition name="fab-expand">
      <div v-if="expanded" class="fab-actions">
        <div v-for="(a, i) in actions" :key="a.name"
             class="fab-action" :style="{ '--delay': `${i * 0.04}s` }"
             @click="onAction(a.name)">
          <span class="fab-action-label">{{ a.label }}</span>
          <el-button :icon="a.icon" circle :style="{ background: a.color, color: '#fff', border: 'none' }" />
        </div>
      </div>
    </Transition>

    <!-- 主 FAB 按钮 -->
    <el-button class="fab-main" :icon="Pointer" circle size="large"
               @click="expanded = !expanded" />
    <el-badge v-if="unreadCount > 0" :value="unreadCount" class="fab-badge" />
  </div>
</template>

<style scoped>
.fab-container {
  position: fixed;
  right: 16px;
  bottom: 80px;
  z-index: 1000;
}
.fab-main {
  width: 56px; height: 56px;
  background: linear-gradient(135deg, #409EFF, #67C23A);
  color: #fff; border: none;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.4);
  transition: transform 0.2s;
}
.fab-main:hover {
  transform: scale(1.1) rotate(90deg);
}
.fab-badge {
  position: absolute; top: 0; right: 0;
}
.fab-actions {
  position: absolute;
  right: 0; bottom: 72px;
  display: flex; flex-direction: column; gap: 12px;
  align-items: flex-end;
}
.fab-action {
  display: flex; align-items: center; gap: 8px;
  animation: fab-in 0.3s var(--delay) backwards;
}
.fab-action-label {
  background: rgba(0, 0, 0, 0.75);
  color: #fff;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 13px;
  white-space: nowrap;
}
@keyframes fab-in {
  from { opacity: 0; transform: translateY(20px) scale(0.7); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.fab-expand-enter-active, .fab-expand-leave-active {
  transition: opacity 0.2s;
}
.fab-expand-enter-from, .fab-expand-leave-to {
  opacity: 0;
}
</style>