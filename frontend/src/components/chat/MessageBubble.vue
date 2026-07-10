<script setup>
/**
 * 单条消息气泡 (从 Customer/Agent 拆出, 共享).
 *  - msgType: TEXT / IMAGE / VOICE / FILE / SYSTEM / RECALL
 *  - senderRole: CUSTOMER / AGENT / BOT / SYSTEM
 *  - 通过 props 接收 readMap / recalledMap / userStore.id 判断是否是自己发的
 */
import { computed } from 'vue'
import { parseVoiceUrl, parseVoiceSeconds } from '@/composables/useVoiceMessage'

const props = defineProps({
  item: { type: Object, required: true },
  currentUserId: { type: Number, default: null },
  readMap: { type: Object, default: () => ({}) },
  recalledMap: { type: Object, default: () => ({}) },
  showFile: { type: Boolean, default: true },  // Agent.vue 显示文件链接, Customer.vue 隐藏
})
const emit = defineEmits(['preview-image', 'recall'])

const isMine = computed(() => props.item.senderId === props.currentUserId)
const isBot = computed(() => props.item.senderRole === 'BOT')
const isSystem = computed(() => props.item.msgType === 'SYSTEM' || props.item.msgType === 'RECALL')
const isRead = computed(() => isMine.value && props.item.id && props.readMap[props.item.id])
const isRecalled = computed(() => props.item.id && props.recalledMap[props.item.id])

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function roleLabel() {
  if (props.item.senderRole === 'BOT') return '🤖 智能客服'
  if (props.item.senderRole === 'AGENT') return '客服'
  return '我'
}
</script>

<template>
  <!-- 系统消息 / 撤回提示: 居中文字 -->
  <div v-if="isSystem" class="msg-system">{{ item.content }}</div>

  <!-- 普通消息: 左/右气泡 -->
  <div v-else class="msg-row" :class="{ mine: isMine, bot: isBot }">
    <div class="bubble" :class="{ bot: isBot }">
      <div class="meta">
        <span v-if="isBot" class="bot-badge">🤖 智能客服</span>
        <span v-else>{{ roleLabel() }}</span>
        · {{ formatTime(item.createdAt) }}
        <span v-if="isRead" class="read-tick" title="对方已读">✓✓</span>
      </div>

      <img v-if="item.msgType === 'IMAGE'" :src="item.content" class="msg-image"
           @click="emit('preview-image', item.content)" />

      <div v-else-if="item.msgType === 'VOICE'" class="msg-voice">
        <audio :src="parseVoiceUrl(item.content)" controls preload="metadata" class="audio-player" />
        <span class="voice-meta">{{ parseVoiceSeconds(item.content) }}s</span>
      </div>

      <a v-else-if="item.msgType === 'FILE' && showFile"
         :href="`/api/im/file/raw?path=${encodeURIComponent(item.content)}`"
         :download="item.fileName || (item.content && item.content.split('/').pop())"
         target="_blank" class="msg-file">
        <el-icon><Document /></el-icon>
        <div class="file-info">
          <div class="file-name">{{ item.fileName || (item.content && item.content.split('/').pop()) }}</div>
        </div>
      </a>

      <div v-else class="text">{{ item.content }}</div>

      <div v-if="isMine && item.id && !isRecalled" class="msg-actions">
        <slot name="actions" :item="item">
          <el-button v-if="!isSystem && item.recalled !== 1" link size="small"
                     @click="emit('recall', item.id)">撤回</el-button>
        </slot>
      </div>
    </div>
  </div>
</template>