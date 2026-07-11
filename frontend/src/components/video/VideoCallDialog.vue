<script setup>
/**
 * VideoCallDialog.vue - 视频通话弹窗 (1v1 WebRTC).
 * ----------------------------------------------------------------------------
 * v6 客户体验: 客户/坐席一键视频通话.
 *
 * Props:
 *   - modelValue: 是否显示
 *   - peerId: 对端用户 ID
 *   - peerName: 对端昵称
 *   - chatSessionId: 关联的 IM 会话 (可选)
 *   - stomp: 复用的 StompClient (从父组件传)
 *
 * Emits:
 *   - update:modelValue
 *   - ended: 通话结束
 *
 * 流程:
 *   1. open() -> init() 拿 sessionId+iceServers -> startLocalCamera -> call()
 *   2. STOMP 收到对端 answer/ice -> 自动处理
 *   3. 用户点挂断 -> hangup() -> 后端 /end
 */
import { ref, computed, onUnmounted, watch } from 'vue'
import { VideoCamera, Microphone, VideoPause, Phone } from '@element-plus/icons-vue'
import { videoApi } from '@/api/video'
import { VideoCall } from '@/utils/webrtc-sdk'
import { permission } from '@/utils/permission-sdk'

const props = defineProps({
  modelValue: Boolean,
  peerId: { type: Number, required: true },
  peerName: { type: String, default: '对端' },
  chatSessionId: { type: Number, default: null },
  stomp: { type: Object, default: null },   // StompClient 实例
  localUid: { type: Number, required: true },
})
const emit = defineEmits(['update:modelValue', 'ended'])

const localVideoRef = ref(null)
const remoteVideoRef = ref(null)
const call = ref(null)
const state = ref('IDLE')            // IDLE / CALLING / CONNECTING / CONNECTED / ENDED / ERROR
const errorMsg = ref('')
const micOn = ref(true)
const cameraOn = ref(true)
const elapsed = ref(0)
let timer = null

const stateLabel = computed(() => {
  switch (state.value) {
    case 'IDLE': return '准备中...'
    case 'CALLING': return '呼叫中...'
    case 'CONNECTING': return '连接中...'
    case 'CONNECTED': return `通话中 (${format(elapsed.value)})`
    case 'ENDED': return '已挂断'
    case 'ERROR': return errorMsg.value || '出错了'
    default: return ''
  }
})

function format(sec) {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

watch(() => props.modelValue, async (open) => {
  if (open) {
    await startCall()
  } else {
    teardown()
  }
})

async function startCall() {
  state.value = 'IDLE'
  errorMsg.value = ''
  try {
    // 1) 申请摄像头 + 麦克风权限 (v6: 用 permission-sdk)
    const r = await permission.requestMultiple(['camera', 'microphone'])
    if (!r.camera.ok || !r.microphone.ok) {
      throw new Error(`权限不足: ${r.camera.error?.message || ''} ${r.microphone.error?.message || ''}`)
    }
    // 2) 创建后端会话
    const initR = await videoApi.init(props.chatSessionId, props.peerId)
    if (initR.code !== 200) throw new Error(initR.message || 'init failed')
    const { sessionId, iceServers } = initR.data
    // 3) 启动本地摄像头
    const localStream = await navigator.mediaDevices.getUserMedia({
      video: { width: 1280, height: 720, frameRate: 25 },
      audio: true,
    })
    if (localVideoRef.value) {
      localVideoRef.value.srcObject = localStream
    }
    // 4) 建 VideoCall
    call.value = new VideoCall({
      sessionId,
      localUid: props.localUid,
      peerUid: props.peerId,
      peerName: props.peerName,
      iceServers,
      stompClient: props.stomp,
      onRemoteStream: (stream) => {
        if (remoteVideoRef.value) remoteVideoRef.value.srcObject = stream
      },
      onStateChange: (s) => {
        state.value = s
        if (s === 'CONNECTED' && !timer) {
          timer = setInterval(() => elapsed.value++, 1000)
        }
        if (s === 'ENDED') {
          if (timer) { clearInterval(timer); timer = null }
          setTimeout(() => emit('update:modelValue', false), 1500)
        }
      },
    })
    // 5) A 主动 call
    await call.value.call()
  } catch (e) {
    console.error('[video] start failed', e)
    errorMsg.value = e.message || '启动失败'
    state.value = 'ERROR'
  }
}

function toggleMic() {
  micOn.value = !micOn.value
  call.value?.toggleMic(micOn.value)
}
function toggleCamera() {
  cameraOn.value = !cameraOn.value
  call.value?.toggleCamera(cameraOn.value)
}

function hangup() {
  call.value?.hangup()
  // 调 REST /end 记录状态
  if (call.value?.sessionId) {
    videoApi.end(call.value.sessionId).catch(() => {})
  }
  emit('ended')
}

function teardown() {
  if (timer) { clearInterval(timer); timer = null }
  if (call.value) {
    call.value.hangup()
    call.value = null
  }
  elapsed.value = 0
}

onUnmounted(teardown)
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    @update:model-value="(v) => emit('update:modelValue', v)"
    :title="`视频通话 - ${peerName}`"
    width="90%"
    :show-close="false"
    :close-on-click-modal="false"
    class="video-dialog"
    align-center>
    <div class="video-stage">
      <!-- 远端画面 -->
      <video ref="remoteVideoRef" autoplay playsinline class="remote-video" />
      <!-- 本端小画面 (画中画) -->
      <video ref="localVideoRef" autoplay muted playsinline class="local-video" />
      <!-- 状态文字 -->
      <div class="call-state" :class="state.toLowerCase()">
        {{ stateLabel }}
      </div>
    </div>

    <template #footer>
      <div class="video-controls">
        <el-button :icon="Microphone" circle :type="micOn ? 'primary' : 'danger'"
                   :disabled="state !== 'CONNECTED'" @click="toggleMic" />
        <el-button :icon="cameraOn ? VideoCamera : VideoPause" circle
                   :type="cameraOn ? 'primary' : 'danger'"
                   :disabled="state !== 'CONNECTED'" @click="toggleCamera" />
        <el-button :icon="Phone" circle type="danger" size="large" @click="hangup" />
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.video-dialog {
  border-radius: 12px;
  overflow: hidden;
}
:deep(.el-dialog__header) {
  background: #1f2329;
  color: #fff;
  margin: 0;
  padding: 12px 20px;
}
:deep(.el-dialog__title) {
  color: #fff;
}
:deep(.el-dialog__body) {
  padding: 0;
  background: #000;
}
:deep(.el-dialog__footer) {
  background: #1f2329;
  padding: 16px;
  border-top: 1px solid #2c3036;
}
.video-stage {
  position: relative;
  width: 100%;
  aspect-ratio: 16/9;
  background: #000;
  overflow: hidden;
}
.remote-video {
  width: 100%; height: 100%;
  object-fit: cover;
  background: #1a1a1a;
}
.local-video {
  position: absolute;
  right: 12px; bottom: 12px;
  width: 25%; max-width: 200px;
  border-radius: 8px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  object-fit: cover;
  background: #2c3036;
  z-index: 2;
}
.call-state {
  position: absolute;
  top: 12px; left: 12px;
  padding: 4px 12px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  border-radius: 16px;
  font-size: 13px;
  z-index: 2;
}
.call-state.connecting, .call-state.calling {
  background: rgba(230, 162, 60, 0.9);
  animation: pulse 1.5s infinite;
}
.call-state.connected {
  background: rgba(103, 194, 58, 0.9);
}
.call-state.error {
  background: rgba(245, 108, 108, 0.9);
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}
.video-controls {
  display: flex; justify-content: center; gap: 16px;
}
</style>