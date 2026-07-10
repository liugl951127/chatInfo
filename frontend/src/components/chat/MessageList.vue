<script setup>
/**
 * MessageList.vue - 虚拟滚动消息列表 (Customer/Agent 共享).
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 渲染聊天消息列表 (虚拟滚动, 1000+ 条不偏)
 *   - 支持 typing indicator (peerTyping 状态)
 *   - 通过 #bubble slot 暴露每条消息给调用方渲染 (避免 prop drilling)
 *
 * Props:
 *   - messages: ChatMessage[]   消息列表
 *   - sessionId: number|null    当前会话 ID (切换时强制重新挂载)
 *   - peerTyping: boolean       对端是否正在输入
 *
 * 设计要点:
 *   - DynamicScroller 用 key-field="id", 需保证 id 唯一
 *   - 消息无 id 字段 (如 SYSTEM 消息) 需在 appendMessage 处 fallback: tmp-{ts}-{rand}
 *   - :key="sessionId" 让 session 切换时强制重新挂载, 避免内部 vnode 引用悬空
 *   - DynamicScrollerItem 给虚拟项提供 size/observe
 */
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'

defineProps({
  messages: { type: Array, required: true },
  sessionId: { type: [Number, String], default: null },  // 用于 :key 强制重挂载
  peerTyping: { type: String, default: '' },            // 对端正在输入 (e.g. '客服')
})
const emit = defineEmits(['scroll'])
</script>

<template>
  <DynamicScroller
    :key="sessionId || 'empty'"
    ref="messageListRef"
    :items="messages"
    :min-item-size="48"
    key-field="id"
    class="message-list scroll-smooth"
    @scroll="(e) => emit('scroll', e)">
    <template #default="{ item, index, active }">
      <DynamicScrollerItem
        :item="item"
        :active="active"
        :data-index="index"
        :size-dependencies="[item.content, item.msgType, item.recalled, messages.length]">
        <slot name="bubble" :item="item" />
      </DynamicScrollerItem>
    </template>
  </DynamicScroller>

  <div v-if="peerTyping" class="typing-indicator">
    <span class="dot"></span><span class="dot"></span><span class="dot"></span>
    <span class="text">{{ peerTyping }} 正在输入...</span>
  </div>
</template>

<style scoped>
/* 这里放共享的滚动列表样式, 调用的 view 不需要重复 */
:deep(.message-list) {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
  background: #f7f8fa;
}
:deep(.typing-indicator) {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 0; color: #909399; font-size: 12px;
}
:deep(.typing-indicator .dot) {
  width: 6px; height: 6px; border-radius: 50%; background: #909399;
  animation: typing-bounce 1.2s infinite ease-in-out;
}
:deep(.typing-indicator .dot:nth-child(2)) { animation-delay: 0.2s; }
:deep(.typing-indicator .dot:nth-child(3)) { animation-delay: 0.4s; }
@keyframes typing-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-4px); opacity: 1; }
}
</style>