<template>
  <div class="replay-page">
    <header class="topbar">
      <span class="title">📼 会话回溯</span>
      <span class="meta">会话 #{{ sessionId }} · 共 {{ records.length }} 段录像</span>
      <el-button size="small" @click="$router.back()">返回</el-button>
    </header>

    <main class="main">
      <!-- 录像列表 -->
      <aside class="sidebar">
        <div class="sb-header">录像片段</div>
        <div v-if="!records.length" class="empty">
          <el-empty description="该会话暂无录像" :image-size="60" />
        </div>
        <div
          v-for="r in records"
          :key="r.id"
          class="rec-item"
          :class="{ active: current?.id === r.id }"
          @click="selectRecord(r)"
        >
          <div class="rec-row1">
            <span class="rec-id">#{{ r.id }}</span>
            <el-tag size="small" :type="r.endReason ? 'info' : 'warning'">
              {{ r.endReason ? endReasonText(r.endReason) : '录制中' }}
            </el-tag>
          </div>
          <div class="rec-row2">
            <span>起: {{ formatTime(r.startedAt) }}</span>
            <span v-if="r.endedAt">止: {{ formatTime(r.endedAt) }}</span>
          </div>
          <div class="rec-row3">
            <span>{{ r.chunkCount }} 段</span>
            <span>{{ formatBytes(r.totalBytes) }}</span>
            <span v-if="r.userId !== userStore.id" class="rec-peer">被录制方 #{{ r.userId }}</span>
          </div>
        </div>
      </aside>

      <!-- 播放器 -->
      <section class="player-area">
        <div v-if="!current" class="placeholder">
          <el-empty description="选择左侧的录像片段查看" :image-size="100" />
        </div>
        <div v-else class="player">
          <div class="player-header">
            <div>
              <span class="rec-num">录像 #{{ current.id }}</span>
              <span class="rec-status">
                <el-tag size="small" :type="current.endReason ? 'info' : 'warning'">
                  {{ current.endReason ? endReasonText(current.endReason) : '录制中' }}
                </el-tag>
                <el-tag size="small" type="success" v-if="current.consentGiven">已获同意</el-tag>
                <el-tag size="small" type="primary" v-if="mseBuffer.length > 1">🎬 无缝播放 (MSE)</el-tag>
              </span>
            </div>
            <div class="actions">
              <el-button size="small" @click="downloadAll" :disabled="!allBlobs.length">
                <el-icon><Download /></el-icon> 下载完整录像
              </el-button>
              <el-button size="small" @click="rebuildBlob" :disabled="!currentChunks.length" :loading="rebuilding">
                <el-icon><Refresh /></el-icon> 重新加载
              </el-button>
            </div>
          </div>
          <video
            v-if="videoSrc"
            ref="videoEl"
            :src="videoSrc"
            controls
            autoplay
            class="video"
          />
          <div v-else class="video-placeholder">
            <el-icon v-if="loading" class="is-loading"><Loading /></el-icon>
            <span v-else>正在准备视频...</span>
          </div>

          <div v-if="current" class="meta-table">
            <div class="row"><span class="k">起止时间</span><span class="v">{{ formatTime(current.startedAt) }} → {{ current.endedAt ? formatTime(current.endedAt) : '进行中' }}</span></div>
            <div class="row"><span class="k">结束原因</span><span class="v">{{ current.endReason ? endReasonText(current.endReason) : '—' }}</span></div>
            <div class="row"><span class="k">分片数</span><span class="v">{{ current.chunkCount }} 段 / {{ formatBytes(current.totalBytes) }}</span></div>
            <div class="row"><span class="k">用户授权</span><span class="v">{{ current.consentGiven ? '✅ 是' : '❌ 否' }}</span></div>
            <div class="row"><span class="k">分片明细</span><span class="v chunks-list">
              <span v-for="c in currentChunks" :key="c.id" class="chunk-pill" :title="`#${c.sequenceNo} · ${formatBytes(c.byteSize)} · ${c.durationMs}ms`">
                #{{ c.sequenceNo }} {{ formatBytes(c.byteSize) }}
              </span>
            </span></div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Loading } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { recordApi } from '@/api/record'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const sessionId = computed(() => Number(route.params.sessionId))
const records = ref([])
const current = ref(null)
const currentChunks = ref([])
const videoSrc = ref('')
const loading = ref(false)
const rebuilding = ref(false)
const allBlobs = ref([])  // 缓存已加载的所有 chunk binary (用于合并下载)
const videoEl = ref(null)
// MSE 状态
const mseSupported = ref(true)
const mseBuffer = ref([])
const mseAppending = ref(false)

onMounted(async () => {
  try {
    const r = await recordApi.sessionRecords(sessionId.value)
    // axios 拦截器已解包, r 直接是 {sessionId, records, chunks}
    records.value = (r && r.records) || []
  } catch (e) {
    console.error('加载录像列表失败', e)
    ElMessage.error('加载录像列表失败: ' + (e?.message || '网络异常'))
  }
})

onBeforeUnmount(() => {
  if (videoSrc.value) URL.revokeObjectURL(videoSrc.value)
  allBlobs.value = []
})

async function selectRecord(r) {
  if (current.value?.id === r.id) return
  current.value = r
  currentChunks.value = []
  if (videoSrc.value) { URL.revokeObjectURL(videoSrc.value); videoSrc.value = '' }
  allBlobs.value = []
  await rebuildBlob()
}

async function rebuildBlob() {
  if (!current.value) return
  loading.value = true
  rebuilding.value = true
  try {
    // 拉分片列表 (axios 拦截器已解包, resp 直接是数组)
    const chunks = await recordApi.recordChunks(current.value.id)
    currentChunks.value = chunks || []
    if (!chunks || !chunks.length) {
      ElMessage.warning('该录像没有分片')
      return
    }
    // 按 sequenceNo 排序
    chunks.sort((a, b) => a.sequenceNo - b.sequenceNo)

    // 优先用 MediaSource Extensions (MSE) 实现跨分片无缝播放
    // 兑底方案: Blob 直拼 (只能播首段)
    if (canMSE() && chunks.length > 1) {
      try {
        await playWithMSE(chunks)
        mseSupported.value = true
        return
      } catch (e) {
        console.warn('[replay] MSE 播放失败, 兑底 Blob 直拼', e)
        mseSupported.value = false
      }
    }

    // 兑底: 并发下载所有分片 + Blob 直拼
    const blobs = await Promise.all(chunks.map(c => recordApi.downloadChunkBlob(c.id)))
    allBlobs.value = blobs
    const merged = new Blob(blobs, { type: chunks[0].mimeType || 'video/webm' })
    if (videoSrc.value) URL.revokeObjectURL(videoSrc.value)
    videoSrc.value = URL.createObjectURL(merged)
  } catch (e) {
    console.error(e)
    ElMessage.error('加载分片失败: ' + (e?.message || e))
  } finally {
    loading.value = false
    rebuilding.value = false
  }
}

/**
 * 判断浏览器是否支持 MSE (视频/音频 MIME).
 */
function canMSE() {
  if (typeof window.MediaSource === 'undefined') return false
  return MediaSource.isTypeSupported('video/webm;codecs=vp9')
      || MediaSource.isTypeSupported('video/webm;codecs=vp8')
      || MediaSource.isTypeSupported('video/webm')
}

/**
 * 使用 MediaSource Extensions 逐段喂给 <video>, 实现跨分片无缝连续播放.
 * 原理:
 *   - 每个 5s WebM 片段以 ArrayBuffer 加到 sourceBuffer
 *   - sourceBuffer 顺序处理, 拼接成完整时间线
 *   - browser 内部完成 EBML/Cluster 重调度, 无需 ffmpeg
 * 限制:
 *   - 首段必须包含 EBML header + init segment (浏览器才能解码后续)
 *   - sourceBuffer.mode = 'sequence' 会拼接 init segment (针对同一段编码器)
 */
async function playWithMSE(chunks) {
  return new Promise((resolve, reject) => {
    const v = videoEl.value
    if (!v) return reject(new Error('video element not ready'))

    const mime = chunks[0].mimeType || 'video/webm'
    const ms = new MediaSource()
    const blobUrl = URL.createObjectURL(ms)
    videoSrc.value = blobUrl

    ms.addEventListener('sourceopen', async () => {
      try {
        const sb = ms.addSourceBuffer(mime + ';codecs=vp8')
        sb.mode = 'sequence'  // sequence 模式让 MSE 接受不同 init segment
        const appendQueue = []
        let appending = false

        sb.addEventListener('updateend', () => {
          if (appendQueue.length && !appending) {
            const next = appendQueue.shift()
            try { sb.appendBuffer(next) } catch (e) { console.error('[replay] appendBuffer err', e) }
          } else if (!appendQueue.length && mseBuffer.value.length === chunks.length && !sb.updating) {
            if (ms.readyState === 'open') ms.endOfStream()
          }
        })
        sb.addEventListener('error', (e) => console.error('[replay] sb error', e))

        function appendBuffer(buf) {
          if (!buf || buf.byteLength === 0) return
          if (sb.updating) appendQueue.push(buf)
          else try { sb.appendBuffer(buf) } catch (e) { console.error('[replay] append err', e) }
        }

        // 按 sequence 顺序下载并 append
        for (let i = 0; i < chunks.length; i++) {
          const c = chunks[i]
          const blob = await recordApi.downloadChunkBlob(c.id)
          allBlobs.value.push(blob)
          const buf = await blob.arrayBuffer()
          mseBuffer.value.push(c.sequenceNo)
          appendBuffer(buf)
          // 给浏览器喘息机会, 避免 queue 过大
          if (i % 4 === 3) await new Promise(r => setTimeout(r, 0))
        }

        // 全部喂完后 sourceBuffer 的 updateend 会 endOfStream
        appending = false
      } catch (e) {
        reject(e)
      }
    })

    ms.addEventListener('sourceended', () => resolve())
    ms.addEventListener('error', (e) => reject(new Error('MSE error: ' + (e?.message || 'unknown'))))

    // 2s 超时保护 (避免加载慢时 UI 卡死)
    setTimeout(() => resolve(), 2000)
  })
}

function downloadAll() {
  if (!allBlobs.value.length || !current.value) return
  const merged = new Blob(allBlobs.value, { type: 'video/webm' })
  const url = URL.createObjectURL(merged)
  const a = document.createElement('a')
  a.href = url
  a.download = `record-${current.value.id}-${formatTimeForFile(current.value.startedAt)}.webm`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  ElMessage.success('下载已开始')
}

function endReasonText(r) {
  return { NORMAL: '正常结束', USER_STOP: '用户停止', PAGE_CLOSE: '页面关闭', PROCESS_KILLED: '进程被杀', ERROR: '异常结束' }[r] || r
}
function formatTime(s) {
  if (!s) return '—'
  const d = new Date(s)
  return d.toLocaleString('zh-CN', { hour12: false })
}
function formatTimeForFile(s) {
  if (!s) return 'unknown'
  return new Date(s).toISOString().replace(/[:.]/g, '-')
}
function formatBytes(n) {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}
</script>

<style scoped>
.replay-page {
  display: flex; flex-direction: column;
  height: 100vh;
  background: linear-gradient(180deg, #f5f7fa 0%, #e8eef5 100%);
  position: relative;
  overflow: hidden;
}
.replay-page::before {
  content: '';
  position: absolute; top: 0; right: -100px;
  width: 400px; height: 400px;
  background: radial-gradient(circle, rgba(64, 158, 255, 0.08) 0%, transparent 70%);
  pointer-events: none;
}
.replay-page::after {
  content: '';
  position: absolute; bottom: -100px; left: -100px;
  width: 350px; height: 350px;
  background: radial-gradient(circle, rgba(103, 194, 58, 0.06) 0%, transparent 70%);
  pointer-events: none;
}

.topbar {
  height: 56px; padding: 0 20px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(235, 239, 245, 0.6);
  display: flex; align-items: center; gap: 12px;
  position: relative; z-index: 2;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}
.title {
  font-size: 16px; font-weight: 600;
  display: flex; align-items: center; gap: 6px;
}
.title-icon { font-size: 20px; }
.meta {
  font-size: 12px; color: #909399;
  background: #f0f2f5;
  padding: 4px 10px;
  border-radius: 10px;
}
.topbar-spacer { flex: 1; }

.main {
  flex: 1; display: flex; min-height: 0;
  position: relative; z-index: 1;
  padding: 12px;
  gap: 12px;
}

/* 侧栏 */
.sidebar {
  width: 300px; flex-shrink: 0;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
  border-radius: 16px;
  border: 1px solid #ebeef5;
  display: flex; flex-direction: column;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}
.sb-header {
  padding: 14px 16px;
  font-size: 13px; font-weight: 600;
  color: #303133;
  display: flex; align-items: center; gap: 6px;
  border-bottom: 1px solid #ebeef5;
  background: linear-gradient(90deg, rgba(64, 158, 255, 0.05), transparent);
}
.sidebar > div:not(.sb-header) { flex: 1; overflow-y: auto; }
.rec-item {
  margin: 8px;
  padding: 12px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.rec-item:hover {
  border-color: #409EFF;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.1);
  transform: translateX(2px);
}
.rec-item.active {
  background: linear-gradient(135deg, #ecf5ff, #f0f9ff);
  border-color: #409EFF;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.15);
}
.rec-row1 {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 4px;
}
.rec-id { font-size: 14px; font-weight: 600; color: #303133; }
.rec-row2 {
  font-size: 12px; color: #909399;
  display: flex; gap: 8px;
  margin-bottom: 4px;
}
.rec-row3 {
  display: flex; gap: 8px; flex-wrap: wrap;
  font-size: 11px; color: #909399;
}
.rec-peer {
  background: #f0f9ff;
  color: #1976d2;
  padding: 1px 6px;
  border-radius: 6px;
}
.empty {
  padding: 40px 20px;
  text-align: center;
}

/* 播放器 */
.player-area {
  flex: 1; min-width: 0;
  display: flex; flex-direction: column;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
  border-radius: 16px;
  border: 1px solid #ebeef5;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.04);
}
.placeholder {
  flex: 1;
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  background: linear-gradient(135deg, #f5f7fa, #fff);
  gap: 12px;
}
.placeholder-icon {
  font-size: 64px;
  animation: bounce 2s ease-in-out infinite;
}
@keyframes bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}
.placeholder-text { color: #909399; font-size: 14px; }
.player {
  display: flex; flex-direction: column;
  height: 100%;
}
.player-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 20px;
  background: linear-gradient(90deg, rgba(64, 158, 255, 0.04), transparent);
  border-bottom: 1px solid #ebeef5;
}
.rec-num { font-size: 15px; font-weight: 600; margin-right: 8px; }
.rec-status { display: inline-flex; gap: 6px; }
.actions { display: flex; gap: 8px; }
.video {
  flex: 1;
  width: 100%;
  background: #000;
  min-height: 0;
  object-fit: contain;
}
.video-placeholder {
  flex: 1;
  display: flex; align-items: center; justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: #909399;
  background: linear-gradient(135deg, #1a1a1a, #2a2a2a);
  font-size: 14px;
}
.video-placeholder .is-loading {
  font-size: 32px;
  color: #409EFF;
}
.meta-table {
  padding: 16px 20px;
  background: #fafbfc;
  border-top: 1px solid #ebeef5;
  max-height: 200px;
  overflow-y: auto;
}
</style>
