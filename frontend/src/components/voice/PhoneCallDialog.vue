<script setup>
/**
 * PhoneCallDialog.vue - 智能电话 UI.
 * ----------------------------------------------------------------------------
 * 阶段 1: 按住说话 (push-to-talk).
 * 阶段 2: 实时 VAD 自动断句.
 */
import { ref, watch } from 'vue'
import { Phone, Microphone, Close, ChatLineRound } from '@element-plus/icons-vue'
import { usePhoneCall } from '@/composables/usePhoneCall'

const props = defineProps({
  modelValue: Boolean,
  calleeUid: { type: [Number, String], required: true },
  calleeName: { type: String, default: 'AI 客服' },
})

const emit = defineEmits(['update:modelValue', 'ended'])

const {
  callId, callState, aiText, transcript, recording, speaking,
  call, hangup, startRecording, stopRecording,
} = usePhoneCall()

const statusLabel = ref('准备中...')

watch(callState, (s) => {
  switch (s) {
    case 'RINGING':     statusLabel.value = '呼叫中...'; break
    case 'CONNECTED':   statusLabel.value = '通话中 - 按住说话'; break
    case 'AI_SPEAKING': statusLabel.value = 'AI 思考中...'; break
    case 'ENDED':       statusLabel.value = '已挂断'; break
    default:            statusLabel.value = s
  }
}, { immediate: true })

watch(() => props.modelValue, async (open) => {
  if (open) {
    try {
      await call(props.calleeUid, true)
    } catch (e) {
      console.error('[phone] call failed', e)
      emit('update:modelValue', false)
    }
  } else {
    hangup()
    emit('ended')
  }
})

async function onPressStart() {
  try { await startRecording() } catch (e) { console.error(e) }
}
function onPressEnd() {
  stopRecording()
}

function onHangup() {
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="(v) => emit('update:modelValue', v)"
    :show-close="false"
    :close-on-click-modal="false"
    width="380px"
    align-center
    class="phone-dialog"
    :title="`智能电话 - ${calleeName}`">
    <div class="phone-stage">
      <div class="phone-avatar">📞</div>
      <div class="phone-status" :class="callState.toLowerCase()">
        {{ statusLabel }}
      </div>
      <div v-if="aiText" class="phone-ai-text">
        <el-icon><ChatLineRound /></el-icon> AI: {{ aiText }}
      </div>
      <div v-if="transcript.length > 0" class="phone-transcript">
        <div v-for="(t, i) in transcript" :key="i" class="t-line" :class="t.speaker">
          <strong>{{ t.speaker === 'user' ? '我' : 'AI' }}:</strong> {{ t.text }}
        </div>
      </div>
    </div>

    <template #footer>
      <div class="phone-controls">
        <el-button
          v-if="callState === 'CONNECTED'"
          :icon="Microphone" circle size="large"
          :type="recording ? 'danger' : 'primary'"
          @mousedown="onPressStart"
          @mouseup="onPressEnd"
          @mouseleave="onPressEnd"
          @touchstart.prevent="onPressStart"
          @touchend.prevent="onPressEnd" />
        <el-button :icon="Phone" circle size="large" type="danger"
                   @click="onHangup" />
      </div>
      <div class="phone-hint">按住麦克风说话, 松开发送</div>
    </template>
  </el-dialog>
</template>

<style scoped>
.phone-dialog :deep(.el-dialog__header) {
  background: #1f2329; color: #fff;
  margin: 0; padding: 12px 20px;
}
.phone-dialog :deep(.el-dialog__title) { color: #fff; }
.phone-dialog :deep(.el-dialog__body) { padding: 24px; }
.phone-dialog :deep(.el-dialog__footer) { padding: 16px; }
.phone-stage { text-align: center; min-height: 200px; }
.phone-avatar {
  font-size: 64px;
  margin: 24px 0 12px;
  animation: ring 1.5s ease-in-out infinite;
}
@keyframes ring {
  0%, 100% { transform: rotate(0); }
  10%, 30% { transform: rotate(-15deg); }
  20%, 40% { transform: rotate(15deg); }
  50% { transform: rotate(0); }
}
.phone-status {
  font-size: 16px; color: #606266; margin-bottom: 12px;
}
.phone-status.ringing, .phone-status.ai_speaking {
  color: #E6A23C; font-weight: 600;
}
.phone-status.connected { color: #67C23A; font-weight: 600; }
.phone-ai-text {
  background: #f0f9ff; color: #303133;
  padding: 8px 12px; border-radius: 8px;
  margin: 12px 0; text-align: left;
  font-size: 14px;
  display: flex; align-items: flex-start; gap: 6px;
}
.phone-transcript {
  max-height: 200px; overflow-y: auto;
  text-align: left; font-size: 13px;
  background: #f5f7fa; padding: 8px;
  border-radius: 6px;
}
.t-line { margin: 4px 0; }
.t-line.user { color: #409EFF; }
.t-line.ai   { color: #67C23A; }
.phone-controls {
  display: flex; justify-content: center; gap: 24px; align-items: center;
}
.phone-hint {
  text-align: center; color: #909399; font-size: 12px; margin-top: 8px;
}
</style>