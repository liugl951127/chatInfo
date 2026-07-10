/**
 * usePhoneCall.js - 智能电话软电话 composable.
 * ----------------------------------------------------------------------------
 * 浏览器内 WebRTC AudioTrack + 周期录音分片上传 ASR + 播放 TTS 音频.
 * 阶段 1: 简化为"按住说话"模式 (录一段, 松开发送).
 * 阶段 2: 实时流式 ASR.
 */
import { ref, onUnmounted } from 'vue'
import { permission } from '@/utils/permission-sdk'
import { voiceApi } from '@/api/voice'

export function usePhoneCall() {
  const callId = ref(null)
  const callState = ref('IDLE')         // IDLE / RINGING / CONNECTED / AI_SPEAKING / ENDED
  const aiText = ref('')                // AI 当前说话
  const transcript = ref([])            // 完整对话历史
  const recording = ref(false)
  const speaking = ref(false)           // AI 在说话
  let mediaRecorder = null
  let chunks = []
  let audioEl = null

  /** 初始化（创建 audio 元素用于播放 TTS） */
  function init() {
    if (!audioEl) {
      audioEl = new Audio()
      audioEl.onended = () => {
        speaking.value = false
      }
    }
  }

  /** 发起通话 */
  async function call(callee, aiEnabled = true) {
    init()
    callState.value = 'RINGING'
    const r = await voiceApi.init(callee, aiEnabled)
    if (r.code !== 200) throw new Error(r.message)
    callId.value = r.data.id
    if (aiEnabled) {
      // AI 自动接听
      await voiceApi.answer(callId.value)
      callState.value = 'CONNECTED'
    }
    return callId.value
  }

  /** 挂断 */
  async function hangup() {
    if (!callId.value) return
    try { await voiceApi.end(callId.value) } catch (e) {}
    callState.value = 'ENDED'
    if (audioEl) audioEl.pause()
    callId.value = null
  }

  /** 开始录音 */
  async function startRecording() {
    if (recording.value || !callId.value) return
    const r = await permission.request('microphone')
    if (!r.ok) throw new Error(r.error?.message || '麦克风权限失败')

    const stream = r.stream
    mediaRecorder = new MediaRecorder(stream, { mimeType: 'audio/webm' })
    chunks = []
    mediaRecorder.ondataavailable = (e) => { if (e.data.size > 0) chunks.push(e.data) }
    mediaRecorder.onstop = async () => {
      const blob = new Blob(chunks, { type: 'audio/webm' })
      // 释放麦克风
      stream.getTracks().forEach(t => t.stop())
      // 上传 ASR
      try {
        callState.value = 'AI_SPEAKING'
        const r = await voiceApi.asr(callId.value, blob)
        if (r.code === 200) {
          const { userText, aiText: ai, audioB64 } = r.data
          transcript.value.push({ speaker: 'user', text: userText })
          transcript.value.push({ speaker: 'ai', text: ai })
          aiText.value = ai
          // 播放 TTS
          if (audioB64) playAudio(audioB64)
        }
      } catch (e) {
        console.error('[phone] asr failed', e)
      } finally {
        callState.value = 'CONNECTED'
        recording.value = false
      }
    }
    mediaRecorder.start()
    recording.value = true
  }

  /** 停止录音 -> 触发 ASR */
  function stopRecording() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      mediaRecorder.stop()
    }
  }

  /** 播放 base64 音频 */
  function playAudio(b64) {
    speaking.value = true
    audioEl.src = `data:audio/mp3;base64,${b64}`
    audioEl.play().catch(e => { speaking.value = false; console.warn(e) })
  }

  onUnmounted(() => {
    hangup()
  })

  return {
    callId, callState, aiText, transcript, recording, speaking,
    call, hangup, startRecording, stopRecording,
  }
}