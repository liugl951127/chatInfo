<script setup>
/**
 * ChatComposer.vue - 聊天输入框组件 (Customer/Agent 共享).
 * ----------------------------------------------------------------------------
 * 职责:
 *   - 输入文本 + Ctrl+Enter 发送
 *   - 工具栏: 图片 (accept prop), 录音 (useRecorder), 表情 (useEmojiPicker), 模板 (slot)
 *   - 录音中: 显示 "录音 Ns / 60s" + 红点闪烁
 *   - 输入状态: @input 触发 onTyping emit (接 STOMP /topic/typing)
 *   - 模板/快捷回复由调用方通过 slot 注入 (Agent 可用 canned replies)
 *
 * Props:
 *   - accept: string   图片 accept 属性 (默认 image/*)
 *
 * Emits:
 *   - send(text: string)              发送文本
 *   - image-pick(file: File)          选择图片
 *   - upload-voice-blob(blob: Blob)   录音结束
 *   - typing()                        输入中 (300ms debounce)
 *
 * Slots:
 *   - toolbar-extra                   额外工具按钮 (坐席可加模板按钮)
 *   - toolbar-bottom                  输入框下方的额外控件
 */
import { ref } from 'vue'
import { Picture, Microphone } from '@element-plus/icons-vue'
import { useEmojiPicker, EMOJI_LIST } from '@/composables/useEmojiPicker'
import { useRecorder } from '@/composables/useRecorder'

const props = defineProps({
  modelValue: { type: String, default: '' },
  disabled: { type: Boolean, default: false },           // 没会话时禁用
  imageAccept: { type: String, default: 'image/*' },    // 客服侧可以发更多类型
  showCanned: { type: Boolean, default: false },         // 坐席侧显示模板按钮
  placeholder: { type: String, default: '输入消息…' },
})
const emit = defineEmits(['update:modelValue', 'send', 'typing', 'image-pick', 'voice-blob', 'open-canned'])

const { emojiOpen, insertEmoji } = useEmojiPicker()
const fileInputRef = ref(null)

// 录音 hook — 上传完成后 emit 'voice-blob' 给父组件
const { recording, uploadingAudio, recordSeconds, toggleRecording } = useRecorder({
  onUpload: async (blob, seconds, mimeType) => {
    emit('voice-blob', { blob, seconds, mimeType })
  },
  onError: (err) => {
    // eslint-disable-next-line no-console
    console.warn('[record]', err.message)
    alert(err.message || '录音失败')
  },
})

function onInput(e) {
  emit('update:modelValue', e)
  emit('typing')
}

function onKeydown(e) {
  if (e.ctrlKey && e.key === 'Enter') {
    e.preventDefault()
    emit('send')
  }
}

function onImagePick(e) {
  emit('image-pick', e)
  e.target.value = ''
}

function pickImage() {
  fileInputRef.value?.click()
}
</script>

<template>
  <div class="composer">
    <input ref="fileInputRef" type="file" :accept="imageAccept" style="display:none" @change="onImagePick" />
    <el-button :icon="Picture" size="large" class="icon-btn" @click="pickImage" title="发图片" />
    <el-button :icon="Microphone" size="large" class="icon-btn"
               :type="recording ? 'danger' : ''"
               :loading="uploadingAudio"
               @click="toggleRecording"
               :title="recording ? '点击停止并发送' : '按住录音 (最长60秒)'" />

    <slot name="toolbar-extra" />

    <el-popover v-model:visible="emojiOpen" placement="top-start" :width="280" trigger="click">
      <template #reference>
        <el-button size="large" class="icon-btn" title="表情">😊</el-button>
      </template>
      <div class="emoji-grid">
        <button v-for="e in EMOJI_LIST" :key="e" class="emoji-btn"
                @click="insertEmoji(e, $event)">{{ e }}</button>
      </div>
    </el-popover>

    <el-input
      :model-value="modelValue"
      type="textarea"
      :rows="2"
      :autosize="{ minRows: 1, maxRows: 4 }"
      :placeholder="placeholder"
      :disabled="disabled"
      @update:model-value="onInput"
      @keydown="onKeydown" />

    <el-button type="primary" size="large" class="send-btn"
               :disabled="disabled || !modelValue.trim()" @click="emit('send')">发送</el-button>
  </div>

  <div v-if="recording" class="recording-bar">
    <span class="rec-dot"></span>
    录音中 {{ recordSeconds }}s / 60s
  </div>
</template>

<style scoped>
.composer {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  background: #fff;
  border-top: 1px solid #ebeef5;
}
.icon-btn {
  padding: 8px;
  min-width: 40px;
}
.send-btn { min-width: 64px; }
.emoji-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
  max-height: 200px;
  overflow-y: auto;
}
.emoji-btn {
  border: none;
  background: transparent;
  font-size: 20px;
  padding: 4px;
  cursor: pointer;
  border-radius: 4px;
}
.emoji-btn:hover { background: #f0f9eb; }
.recording-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #fef0f0;
  border-radius: 4px;
  font-size: 12px;
  color: #f56c6c;
}
.rec-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f56c6c;
  animation: blink 1s infinite;
}
@keyframes blink { 50% { opacity: 0.3; } }
</style>