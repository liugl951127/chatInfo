/**
 * 浏览器麦克风录音 hook (从 Customer/Agent 拆出, 两个页面共享).
 *  - toggleRecording(): 开始/停止录音 (自动判断)
 *  - 上传完成后通过 onUpload(blob, seconds, mimeType) 回调给调用方发 STOMP MSG_VOICE
 *  - 60 秒自动截止, 超时 stopRecording()
 *  - 浏览器不支持/权限拒绝时返错误
 *  - mediaRecorder / recordedChunks / recordTimer 是闭包内 ref, 跨调用保留
 */
import { ref } from 'vue'

export function useRecorder({ onUpload, maxSeconds = 60, onError } = {}) {
  const recording = ref(false)
  const uploadingAudio = ref(false)
  const recordSeconds = ref(0)

  let mediaRecorder = null
  let recordedChunks = []
  let recordTimer = null

  function pickBestMime() {
    if (typeof MediaRecorder === 'undefined') return ''
    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) return 'audio/webm;codecs=opus'
    if (MediaRecorder.isTypeSupported('audio/webm')) return 'audio/webm'
    return ''
  }

  async function toggleRecording() {
    if (recording.value) return stopRecording()
    if (!navigator.mediaDevices?.getUserMedia) {
      onError?.(new Error('浏览器不支持录音'))
      return
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      recordedChunks = []
      const mime = pickBestMime()
      mediaRecorder = mime
        ? new MediaRecorder(stream, { mimeType: mime, audioBitsPerSecond: 64_000 })
        : new MediaRecorder(stream)
      mediaRecorder.ondataavailable = (e) => {
        if (e.data.size > 0) recordedChunks.push(e.data)
      }
      mediaRecorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop())
        const blob = new Blob(recordedChunks, { type: mediaRecorder.mimeType || 'audio/webm' })
        if (blob.size < 1000) {
          onError?.(new Error('录音太短, 请重试'))
          recordSeconds.value = 0
          return
        }
        uploadingAudio.value = true
        try {
          await onUpload?.(blob, recordSeconds.value, blob.type)
        } finally {
          uploadingAudio.value = false
          recordSeconds.value = 0
        }
      }
      mediaRecorder.start(100)  // 100ms chunks
      recording.value = true
      recordSeconds.value = 0
      recordTimer = setInterval(() => {
        recordSeconds.value++
        if (recordSeconds.value >= maxSeconds) stopRecording()
      }, 1000)
    } catch (e) {
      onError?.(e instanceof Error ? e : new Error(String(e)))
    }
  }

  function stopRecording() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      try { mediaRecorder.stop() } catch (e) { /* noop */ }
    }
    recording.value = false
    if (recordTimer) { clearInterval(recordTimer); recordTimer = null }
  }

  function reset() {
    stopRecording()
    recordSeconds.value = 0
    uploadingAudio.value = false
  }

  return { recording, uploadingAudio, recordSeconds, toggleRecording, stopRecording, reset }
}