<template>
  <div class="chat-page customer-shell">
    <header class="topbar">
      <div class="brand-area">
        <el-button v-if="isMobile" link class="menu-btn" @click="drawerVisible = true">
          <el-icon size="20"><Menu /></el-icon>
        </el-button>
        <span class="brand">在线客服</span>
      </div>
      <div class="user-area">
        <el-tag :type="connected ? 'success' : 'warning'" size="small">
          {{ connected ? '已连接' : (reconnecting ? '重连中...' : '已断开') }}
        </el-tag>
        <el-button size="small" link @click="logout">退出</el-button>
      </div>
    </header>

    <main class="chat-main">
      <!-- 桌面侧栏 / 移动抽屉 -->
      <aside v-if="!isMobile" class="side">
        <el-button v-if="!session" type="primary" class="side-btn" @click="showSkillPicker = true">
          开始咨询
        </el-button>
        <div v-if="session" class="session-info">
          <p>会话号: <code>{{ session.sessionNo }}</code></p>
          <p>技能: <el-tag size="small">{{ session.skillTag || '通用' }}</el-tag></p>
          <p>状态: <el-tag size="small" :type="statusTagType">{{ statusText }}</el-tag></p>
          <p v-if="session.agentId">客服: #{{ session.agentId }}</p>
          <p v-else-if="session.isBot === 1" style="color:#67c23a">🤖 智能客服</p>
          <div class="session-actions">
            <el-button v-if="session.isBot === 1 && session.status !== 'CLOSED'"
                       size="small" type="success" plain @click="botTransferToHuman">
              转接人工
            </el-button>
            <el-button v-if="session.agentId" size="small" type="warning" plain :disabled="exiting || session.status === 'CLOSED'" @click="requestTransfer">
              转接客服
            </el-button>
            <el-button size="small" type="danger" plain :disabled="exiting || session.status === 'CLOSED'" @click="customerExit">
              主动退出
            </el-button>
            <el-button size="small" plain :disabled="exiting || session.status === 'CLOSED'" @click="closeSession">
              结束会话
            </el-button>
          </div>
        </div>
      </aside>

      <section class="chat-area">
        <div v-if="!session" class="empty">
          <el-empty description="点击「开始咨询」选择问题类型" />
          <el-button v-if="isMobile" type="primary" size="large" @click="showSkillPicker = true">
            开始咨询
          </el-button>
        </div>
        <template v-else>
          <DynamicScroller
            ref="messageListRef"
            :items="messages"
            :min-item-size="48"
            key-field="id"
            class="message-list scroll-smooth"
            @scroll="onScroll">
            <template #default="{ item, index, active }">
              <DynamicScrollerItem
                :item="item"
                :active="active"
                :data-index="index"
                :size-dependencies="[item.content, item.msgType, item.recalled, messages.length]">
                <div v-if="item.msgType === 'SYSTEM' || item.msgType === 'RECALL'" class="msg-system">
                  {{ item.content }}
                </div>
                <div v-else class="msg-row" :class="{ mine: item.senderId === userStore.id, bot: item.senderRole === 'BOT' }">
                  <div class="bubble" :class="{ bot: item.senderRole === 'BOT' }">
                    <div class="meta">
                      <span v-if="item.senderRole === 'BOT'" class="bot-badge">🤖 智能客服</span>
                      <span v-else>{{ item.senderRole === 'AGENT' ? '客服' : '我' }}</span>
                      · {{ formatTime(item.createdAt) }}
                      <span v-if="item.senderId === userStore.id && item.id && readMap[item.id]" class="read-tick" title="对方已读">
                        ✓✓
                      </span>
                    </div>
                    <img v-if="item.msgType === 'IMAGE'" :src="item.content" class="msg-image" @click="previewImage(item.content)" />
                    <div v-else-if="item.msgType === 'VOICE'" class="msg-voice">
                      <audio :src="parseVoiceUrl(item.content)" controls preload="metadata" class="audio-player" />
                      <span class="voice-meta">{{ parseVoiceSeconds(item.content) }}s</span>
                    </div>
                    <div v-else class="text">{{ item.content }}</div>
                    <div v-if="item.senderId === userStore.id && item.id && !recalledMap[item.id]" class="msg-actions">
                      <el-button link size="small" @click="recall(item.id)" v-if="canRecall(item)">
                        撤回
                      </el-button>
                    </div>
                  </div>
                </div>
              </DynamicScrollerItem>
            </template>
          </DynamicScroller>
          <div v-if="peerTyping" class="typing-indicator">
            <span class="dot"></span><span class="dot"></span><span class="dot"></span>
            <span class="text">{{ peerTyping }} 正在输入...</span>
          </div>

          <div class="composer">
            <input ref="fileInputRef" type="file" accept="image/*" style="display:none" @change="onImagePick" />
            <input ref="audioInputRef" type="file" accept="audio/*" style="display:none" />
            <el-button :icon="Picture" size="large" class="icon-btn" @click="fileInputRef?.click()" title="发图片" />
            <el-button :icon="Microphone" size="large" class="icon-btn"
                       :type="recording ? 'danger' : ''"
                       :loading="uploadingAudio"
                       @click="toggleRecording"
                       :title="recording ? '点击停止并发送' : '按住录音 (最长60秒)'" />
            <el-popover v-model:visible="emojiOpen" placement="top-start" :width="280" trigger="click">
              <template #reference>
                <el-button size="large" class="icon-btn" title="表情">😊</el-button>
              </template>
              <div class="emoji-grid">
                <button v-for="e in EMOJI_LIST" :key="e" class="emoji-btn" @click="insertEmoji(e)">{{ e }}</button>
              </div>
            </el-popover>
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              :autosize="{ minRows: 1, maxRows: 4 }"
              placeholder="输入消息…"
              @keydown.ctrl.enter.prevent="send"
              @input="onTyping" />
            <el-button type="primary" size="large" class="send-btn" :disabled="!draft.trim()" @click="send">发送</el-button>
          </div>
          <div v-if="recording" class="recording-bar">
            <span class="rec-dot"></span>
            录音中 {{ recordSeconds }}s / 60s
          </div>
        </template>
      </section>
    </main>

    <!-- 移动端侧栏抽屉 -->
    <el-drawer v-if="isMobile" v-model="drawerVisible" title="会话信息" direction="rtl" size="80%">
      <div class="drawer-content">
        <el-button v-if="!session" type="primary" size="large" class="side-btn" @click="showSkillPicker = true; drawerVisible = false">
          开始咨询
        </el-button>
        <div v-if="session" class="session-info">
          <p>会话号: <code>{{ session.sessionNo }}</code></p>
          <p>技能: <el-tag size="small">{{ session.skillTag || '通用' }}</el-tag></p>
          <p>状态: <el-tag size="small" :type="statusTagType">{{ statusText }}</el-tag></p>
          <p v-if="session.agentId">客服: #{{ session.agentId }}</p>
          <el-button size="large" type="danger" plain class="side-btn" @click="closeSession">结束会话</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 技能选择弹窗 -->
    <el-dialog v-model="showSkillPicker" title="请选择问题类型" width="380px" :show-close="false" :close-on-click-modal="false">
      <el-radio-group v-model="selectedSkill" class="skill-group">
        <el-radio-button value="">通用</el-radio-button>
        <el-radio-button value="billing">账单</el-radio-button>
        <el-radio-button value="refund">退款</el-radio-button>
        <el-radio-button value="tech">技术</el-radio-button>
        <el-radio-button value="general">一般咨询</el-radio-button>
      </el-radio-group>
      <el-divider><span style="font-size:13px;color:#909399">或选择客服模式</span></el-divider>
      <el-radio-group v-model="chatMode" class="skill-group">
        <el-radio-button value="human">🤝 人工客服</el-radio-button>
        <el-radio-button value="bot">🤖 智能客服</el-radio-button>
      </el-radio-group>
      <p v-if="chatMode==='bot'" style="font-size:12px;color:#909399;margin:12px 0 0">
        智能客服可解答常见问题, 随时输入 “人工” 转接真人客服。
      </p>
      <template #footer>
        <el-button type="primary" size="large" :loading="creating" @click="startSession">
          {{ chatMode === 'bot' ? '开始智能咨询' : '开始咨询' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- CSAT 评分弹窗 -->
    <el-dialog v-model="showRating" title="请对本次服务评分" width="380px" :show-close="false" :close-on-click-modal="false">
      <el-rate v-model="rating" :max="5" size="large" />
      <el-input v-model="ratingComment" type="textarea" :rows="3" placeholder="说点什么吧 (可选)" style="margin-top: 16px;" />
      <template #footer>
        <el-button size="large" @click="skipRating">跳过</el-button>
        <el-button type="primary" size="large" :loading="ratingSubmitting" @click="submitRating">提交</el-button>
      </template>
    </el-dialog>

    <!-- 图片预览 -->
    <el-image-viewer
      v-if="previewImageUrl"
      :url-list="[previewImageUrl]"
      :initial-index="0"
      @close="previewImageUrl = null" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture, Microphone, Menu } from '@element-plus/icons-vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { imApi } from '@/api/im'
import { recordApi } from '@/api/record'
import { useUserStore } from '@/stores/user'
import { StompClient } from '@/utils/ws-client'
import { ChatRecordSDK } from '@/utils/record-sdk'

const router = useRouter()
const userStore = useUserStore()

const session = ref(null)
const messages = ref([])
const draft = ref('')
const connected = ref(false)
const reconnecting = ref(false)
const exiting = ref(false)
const messageListRef = ref(null)
const fileInputRef = ref(null)
const readMap = ref({})
const recalledMap = ref({})
const peerTyping = ref('')

// 语音录音 (MediaRecorder)
const recording = ref(false)
const uploadingAudio = ref(false)
const recordSeconds = ref(0)
let mediaRecorder = null
let recordedChunks = []
let recordTimer = null

// 表情选择器
const emojiOpen = ref(false)
const EMOJI_LIST = '😀😁😂🤣😃😄😅😆😉😊😋😎😍😘🥰😗😙😚🙂🤗🤩🤔🤨😐😑😶🙄😏😣😥😮🤐😯😪😫😴😌😛😜😝🤤😒😓😔😕🙃🤑😲☹️🙁😖😞😟😤😢😭😦😧😨😩🤯😬😰😱🥵🥶😳🤪😵😡😠🤬😷🤒🤕🤢🤮🤧🥳🥺🤠🤡🤥🤫🤭🧐🤓👻💀☠️👽👾🤖💩❤️🧡💛💚💙💜🖤🤍🤎💔❣️💕💞💓💗💖💘💝👍👎👌✌️🤞🤟🤘🤙👈👉👆👇✋🤚🖐️🖖👋🤝🙏💪🦾'.split('')

function insertEmoji(e) {
  draft.value = (draft.value || '') + e
  emojiOpen.value = false
}

// VOICE 消息内容是 JSON {url, seconds, mimeType}
function parseVoiceUrl(content) {
  try { return JSON.parse(content || '{}').url || '' } catch { return '' }
}
function parseVoiceSeconds(content) {
  try { return JSON.parse(content || '{}').seconds || 0 } catch { return 0 }
}

const showSkillPicker = ref(false)
const selectedSkill = ref('')
const chatMode = ref('human')  // human | bot
const creating = ref(false)

const showRating = ref(false)
const rating = ref(5)
const ratingComment = ref('')
const ratingSubmitting = ref(false)
const pendingRatingSessionId = ref(null)

const isMobile = ref(false)
const drawerVisible = ref(false)
const previewImageUrl = ref(null)

let stomp = null
let typingTimer = null
let resizeHandler = null
let recorder = null

const statusText = computed(() => ({
  WAITING: '等待客服接入...',
  ACTIVE: '对话中',
  CLOSED: '已结束'
}[session.value?.status] || '-'))

const statusTagType = computed(() => ({
  WAITING: 'warning',
  ACTIVE: 'success',
  CLOSED: 'info'
}[session.value?.status] || 'info'))

const mqMobile = typeof window !== 'undefined' ? window.matchMedia('(max-width: 768px)') : null

function updateIsMobile() {
  isMobile.value = mqMobile?.matches ?? false
}

onMounted(async () => {
  if (mqMobile) {
    updateIsMobile()
    mqMobile.addEventListener('change', updateIsMobile)
    resizeHandler = () => updateIsMobile()
    window.addEventListener('resize', resizeHandler)
  }

  try {
    const list = await imApi.mySessions()
    const active = list.find(s => s.status !== 'CLOSED')
    if (active) {
      session.value = active
      await loadHistory()
      // 找到现有会话 -> 启动 / 续录 (企业级连续录制)
      tryRecorder().catch(() => {})
    }
  } catch {}
  try {
    const offs = await imApi.drainOffline()
    offs.forEach(m => appendMessage(m))
  } catch {}
  connectWs()
})

onBeforeUnmount(() => {
  stomp?.disconnect()
  // v3: 不在这里 stop recorder.
  // 让 SDK 走 _onUnload 兑底 (fetch keepalive 同步上传分片, 不调 /end),
  // 这样页面刷新后能续上同一条 record.
  // 真正的 /end 只在:
  //   - customerExit() 客户主动退出会话
  //   - closeSession() 客户结束会话 (会弹 CSAT)
  //   - 服务端推送 CLOSED 事件 (onEvent 中处理)
})

onUnmounted(() => {
  if (mqMobile) mqMobile.removeEventListener('change', updateIsMobile)
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
})

function connectWs() {
  if (stomp) stomp.disconnect()
  reconnecting.value = !connected.value
  stomp = new StompClient({
    token: userStore.token,
    onConnected: () => {
      // 只更新 UI 状态 (首次连接触发, 重连不触发)
      connected.value = true
      reconnecting.value = false
    },
    onReconnected: () => {
      // 重连成功后拉一次历史, 兜底断网期间可能丢失的推送消息
      console.log('[stomp] 重连成功, 拉历史补漏')
      if (session.value) {
        loadHistory()
      }
    },
    onDisconnected: () => { connected.value = false; reconnecting.value = true },
    onError: () => { connected.value = false; reconnecting.value = true }
  })
  // 订阅 (幂等) — 必须在 connect() 之前调, 这样重连时会自动重订
  const typingDest = session.value?.id
    ? '/topic/typing/' + session.value.id
    : '/topic/typing/0'
  stomp.subscribe('/user/queue/messages', onIncomingMessage)
  stomp.subscribe('/user/queue/events', onEvent)
  stomp.subscribe(typingDest, onTypingEvent)
  stomp.connect('/ws/customer')
}

function onIncomingMessage(msg) {
  if (session.value && msg.sessionId && msg.sessionId !== session.value.id) return
  appendMessage(msg)
  if (msg.id && msg.senderId !== userStore.id) {
    imApi.readMessage(msg.id).catch(() => {})
  }
}

function onTypingEvent(payload) {
  if (!payload || !session.value) return
  if (payload.sessionId !== session.value.id) return
  if (payload.userId === userStore.id) return
  peerTyping.value = payload.typing ? '客服' : ''
}

function onEvent(payload) {
  if (!payload) return
  if (payload.type === 'READ' && payload.messageId) {
    readMap.value = { ...readMap.value, [payload.messageId]: true }
  } else if (payload.type === 'RECALL' && payload.messageId) {
    recalledMap.value = { ...recalledMap.value, [payload.messageId]: true }
    const m = messages.value.find(x => x.id === payload.messageId)
    if (m) {
      m.recalled = true; m.msgType = 'RECALL'; m.content = '对方撤回了一条消息'
    }
  } else if (payload.type === 'TRANSFERRED') {
    ElMessage.info('会话已转接给其他客服')
    drawerVisible.value = false
    refreshSessionFromServer()
  } else if (payload.type === 'CLOSED') {
    if (session.value && payload.sessionId === session.value.id && session.value.status !== 'CLOSED') {
      session.value = { ...session.value, status: 'CLOSED' }
      stopRecorder('SESSION_CLOSED').catch(() => {})
      ElMessage.info(payload.reason === 'CUSTOMER_EXIT' ? '您已退出会话' : '会话已结束')
    }
  } else if (payload.type === 'PRESENCE') {
    if (session.value && payload.sessionId === session.value.id) {
      session.value = { ...session.value, peerOnline: payload.online }
    }
  }
}

async function refreshSessionFromServer() {
  try {
    const list = await imApi.mySessions()
    const fresh = list.find(s => s.id === session.value?.id)
    if (fresh) session.value = fresh
  } catch {}
}

function previewImage(url) {
  previewImageUrl.value = url
}

async function startSession() {
  creating.value = true
  try {
    session.value = await imApi.createSession(selectedSkill.value || null, chatMode.value)
    messages.value = []
    showSkillPicker.value = false
    if (isMobile.value) drawerVisible.value = false
    const isBot = session.value.isBot === 1
    appendMessage({
      msgType: 'SYSTEM', senderRole: 'SYSTEM',
      content: isBot
        ? '已连接智能客服, 请描述您的问题 (输入 “人工” 转接真人)'
        : (session.value.status === 'WAITING' ? '已创建会话, 正在为您匹配客服...' : '客服已接入, 请开始提问'),
      createdAt: new Date().toISOString()
    }, true)
    scrollToBottom()
    // 会话创建成功后, 询问是否同意录制 (合规要求) → 启动 SDK
    if (!recorder || !recorder.recordId) {
      tryRecorder().catch(() => {})
    }
  } finally {
    creating.value = false
  }
}

/**
 * 询问用户同意录制并启动 SDK. 调用方负责 catch 异常.
 * 同意框由 ElMessageBox 弹出, 包含 "服务回溯/合规要求" 等说明.
 */
async function tryRecorder() {
  if (!session.value) return
  if (recorder && recorder.recordId) return  // 已在录 (避免重复 start)

  // v3: 检查是否有可续的 record (上次刷新/切后台后留下的未结束 record)
  let existingRecordId = null
  try {
    const resp = await recordApi.sessionRecords(session.value.id)
    const records = (resp && resp.records) || []
    const candidate = records.find(r => !r.endedAt && r.userId === userStore.id)
    if (candidate) existingRecordId = candidate.id
  } catch (e) {
    console.warn('[record] failed to query resumable records', e)
  }

  // 有续录对象 -> 不弹同意框 (用户已对当前会话同意过, 续上是自然过程)
  if (!existingRecordId) {
    let ok = false
    try {
      await ElMessageBox.confirm(
        '为了服务质量与合规要求, 我们会对本次会话页面进行视频录制 (包括聊天内容和页面操作), 仅供内部审计/质量回溯使用。\n\n注: 录制会在您切换页面、切后台后自动恢复, 仅在主动退出时才结束。',
        '开始会话前需告知',
        {
          confirmButtonText: '同意并开始',
          cancelButtonText: '不同意 (将不能发起会话)',
          type: 'warning',
        }
      )
      ok = true
    } catch {
      ok = false  // 用户取消
    }
    if (!ok) {
      ElMessage.warning('您未同意录制, 为合规要求, 建议退出当前会话')
      return
    }
  }

  recorder = new ChatRecordSDK({
    apiBase: '/api/im/record',
    api: recordApi,
    token: userStore.token,
    sessionId: session.value.id,
    userId: userStore.id,
    nickname: userStore.nickname || userStore.username || '',
    target: '.customer-shell',
    fps: 4,
    chunkDurationMs: 5000,
    bitrate: 500_000,
    watermark: true,
    brandText: '本会话已开启录制 — 用于服务回溯',
    ignoreSelector: '.no-record',
    existingRecordId,  // v3: 续上之前未结束的 record (若有)
    pauseOnHidden: true,  // v3: 切后台自动暂停
    onError: (e) => console.error('[record]', e),
    onState: (s) => console.log('[record] state =', s),
  })
  await recorder.start()
}

async function loadHistory() {
  if (!session.value) return
  try {
    const list = await imApi.history(session.value.id, 100)
    messages.value = list
    await nextTick(); scrollToBottom()
  } catch {}
}

function send() {
  const text = draft.value.trim()
  if (!text || !session.value) return
  if (session.value.status === 'CLOSED') return ElMessage.warning('会话已结束')
  const ok = stomp.send(`/app/send/${session.value.id}`, {
    sessionId: session.value.id, msgType: 'TEXT', content: text
  })
  if (ok) { draft.value = ''; sendTyping(false) }
}

function onImagePick(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) return ElMessage.warning('图片不能超过 5MB')
  const reader = new FileReader()
  reader.onload = () => {
    stomp?.send(`/app/send/${session.value.id}`, {
      sessionId: session.value.id, msgType: 'IMAGE', content: reader.result
    })
  }
  reader.readAsDataURL(file)
  e.target.value = ''
}

// 语音录音: MediaRecorder 采集 webm/opus, 发送到 /api/im/file/upload, 发 MSG_VOICE 文本
async function toggleRecording() {
  if (recording.value) {
    stopRecording()
    return
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    return ElMessage.warning('浏览器不支持录音')
  }
  if (!session.value) return ElMessage.warning('请先创建会话')
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    recordedChunks = []
    const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : (MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : '')
    mediaRecorder = mimeType ? new MediaRecorder(stream, { mimeType, audioBitsPerSecond: 64_000 })
                            : new MediaRecorder(stream)
    mediaRecorder.ondataavailable = (e) => {
      if (e.data.size > 0) recordedChunks.push(e.data)
    }
    mediaRecorder.onstop = async () => {
      stream.getTracks().forEach(t => t.stop())
      const blob = new Blob(recordedChunks, { type: mediaRecorder.mimeType || 'audio/webm' })
      if (blob.size < 1000) {
        return ElMessage.warning('录音太短, 请重试')
      }
      uploadingAudio.value = true
      try {
        const file = new File([blob], `voice-${Date.now()}.webm`, { type: blob.type })
        const res = await imApi.uploadFile(session.value.id, file)
        const data = res.data || res  // ApiResponse 包装
        const url = data?.url || data?.rawUrl || ''
        const seconds = Math.round(recordSeconds.value)
        const content = JSON.stringify({ url, seconds, mimeType: blob.type })
        stomp?.send(`/app/send/${session.value.id}`, {
          msgType: 'VOICE', content
        })
        ElMessage.success(`语音 ${seconds}s 已发送`)
      } catch (err) {
        ElMessage.error('上传失败: ' + (err?.message || '未知错误'))
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
      if (recordSeconds.value >= 60) stopRecording()  // 最长 60 秒
    }, 1000)
  } catch (e) {
    ElMessage.error('麦克风权限被拒绝: ' + (e?.message || ''))
  }
}
function stopRecording() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
  }
  recording.value = false
  if (recordTimer) { clearInterval(recordTimer); recordTimer = null }
}

function onTyping() {
  if (!session.value) return
  sendTyping(true)
  clearTimeout(typingTimer)
  typingTimer = setTimeout(() => sendTyping(false), 1500)
}
function sendTyping(typing) {
  if (!stomp || !session.value) return
  stomp.send(`/app/typing/${session.value.id}`, { typing })
}

async function recall(messageId) {
  try {
    await imApi.recallMessage(messageId)
    recalledMap.value = { ...recalledMap.value, [messageId]: true }
    const m = messages.value.find(x => x.id === messageId)
    if (m) { m.recalled = true; m.msgType = 'RECALL'; m.content = '你撤回了一条消息' }
    ElMessage.success('已撤回')
  } catch (e) {
    ElMessage.error(e.message || '撤回失败')
  }
}

function canRecall(msg) {
  if (!msg.id || !msg.createdAt) return false
  return Date.now() - new Date(msg.createdAt).getTime() < 2 * 60 * 1000
}

function appendMessage(m, skipScroll = false) {
  messages.value.push(m)
  if (m.id && m.recalled) recalledMap.value[m.id] = true
  if (!skipScroll) nextTick(scrollToBottom)
}

function scrollToBottom() {
  const el = messageListRef.value
  if (!el) return
  // DynamicScroller 提供了 scrollToBottom() / scrollToItem() 实例方法
  if (typeof el.scrollToBottom === 'function') {
    el.scrollToBottom()
  } else if (el.$el) {
    el.$el.scrollTop = el.$el.scrollHeight
  }
}

function onScroll() { /* 简化: 不做向上翻页加载历史 */ }

function formatTime(t) {
  if (!t) return ''
  return new Date(t).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

async function closeSession() {
  if (!session.value) return
  try {
    await imApi.closeSession(session.value.id)
    session.value = { ...session.value, status: 'CLOSED' }
    pendingRatingSessionId.value = session.value.id
    showRating.value = true
    drawerVisible.value = false
    // 客户点了结束会话 -> 显式停录制
    await stopRecorder('NORMAL')
  } catch {}
}

/**
 * 客户主动退出会话 (区别于 closeSession):
 *  - 推系统消息 '客户已主动退出' 给坐席
 *  - 不可评分 (避免客户退出后系统弹评分, 干扰体验)
 *  - 触发后服务端还会推 CLOSED 事件, onEvent 中清理本地状态
 */
async function customerExit() {
  if (!session.value || exiting.value) return
  try {
    await ElMessageBox.confirm(
      '退出会话后本次聊天将结束, 且无法继续. 确定要主动退出吗?',
      '主动退出',
      { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return  // 用户取消
  }
  exiting.value = true
  try {
    await imApi.customerExit(session.value.id)
    ElMessage.success('已退出本次会话')
    // 服务端还会推 CLOSED 事件; 这里也本地落状态, 避免依赖推送
    session.value = { ...session.value, status: 'CLOSED' }
    await stopRecorder('USER_STOP')
    drawerVisible.value = false
  } catch (e) {
    ElMessage.error('退出失败: ' + (e?.message || '未知错误'))
  } finally {
    exiting.value = false
  }
}

/**
 * 客户申请转接其他坐席. 会话记录保留, agent_id 换成其他可用坐席.
 */
async function requestTransfer() {
  if (!session.value || exiting.value) return
  try {
    const { value: reason } = await ElMessageBox.prompt(
      '告诉我们需要转接的原因 (可选), 我们会为您换一个更合适的客服.',
      '申请转接客服',
      { confirmButtonText: '提交申请', cancelButtonText: '取消', inputPlaceholder: '例如: 这个问题A客服处理不了' }
    )
    await imApi.requestTransfer(session.value.id, session.value.skillTag)
    ElMessage.success('已为您申请转接, 请稍等')
    drawerVisible.value = false
    // 不需要清 session, agentId 会被推送的 TRANSFERRED 事件更新
  } catch (e) {
    if (e === 'cancel' || e?.message === 'cancel') return  // 用户取消 prompt
    ElMessage.error('申请转接失败: ' + (e?.message || '未知错误'))
  }
}

/**
 * 机器人会话中转人工 (关闭 bot 会话, 创建新人工会话).
 */
async function botTransferToHuman() {
  if (!session.value || exiting.value) return
  try {
    const res = await imApi.transferToHuman(session.value.id, session.value.skillTag)
    const newSession = res.data || res  // ApiResponse 包装
    ElMessage.success('已为您转接人工客服, 请稍等接入')
    drawerVisible.value = false
    // 切到新人工会话
    session.value = newSession
    messages.value = []
    appendMessage({
      msgType: 'SYSTEM', senderRole: 'SYSTEM',
      content: '已退出机器人会话, 正在为您匹配人工客服...',
      createdAt: new Date().toISOString()
    }, true)
    // 机器人会话不录制 (文本), 但人工会话需要重新问询同意
    if (recorder && recorder.recordId) {
      await stopRecorder('TRANSFER_BOT_TO_HUMAN')
    }
  } catch (e) {
    ElMessage.error('转人工失败: ' + (e?.message || '未知错误'))
  }
}

/**
 * 停止录制 (处理成功或异常退出).
 */
async function stopRecorder(reason = 'NORMAL') {
  if (!recorder || !recorder.recordId) return
  try {
    await recorder.stop(reason)
  } catch (e) {
    console.warn('[record] stop failed', e)
  } finally {
    recorder = null
  }
}

async function submitRating() {
  if (!pendingRatingSessionId.value) return
  ratingSubmitting.value = true
  try {
    await imApi.rateSession(pendingRatingSessionId.value, rating.value, ratingComment.value)
    ElMessage.success('感谢您的评价!')
    showRating.value = false
    ratingComment.value = ''
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    ratingSubmitting.value = false
  }
}
function skipRating() { showRating.value = false; ratingComment.value = '' }

function logout() {
  stomp?.disconnect()
  userStore.logout()
  router.replace('/login')
}
</script>

<style scoped>
/* ============ 共用 (桌面 + 手机) ============ */
.chat-page {
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  min-height: 52px;
  padding-top: var(--safe-top);
  padding-top: var(--safe-top-legacy);
  height: calc(52px + var(--safe-top) + var(--safe-top-legacy));
}
.brand-area { display: flex; align-items: center; gap: 8px; }
.brand { font-weight: bold; font-size: 16px; }
.menu-btn { padding: 8px; }
.user-area { display: flex; align-items: center; gap: 10px; }

.chat-main { flex: 1; display: flex; min-height: 0; overflow: hidden; }

.side {
  width: 240px;
  background: #fff;
  border-right: 1px solid #ebeef5;
  padding: 16px;
}
.session-info { margin-top: 16px; font-size: 13px; color: #606266; line-height: 1.8; }
.session-info code { font-family: 'Courier New', monospace; color: #409eff; }
.side-btn { width: 100%; margin-top: 8px; }
.session-actions { display: flex; flex-direction: column; gap: 6px; margin-top: 12px; }
.session-actions .el-button { width: 100%; }

.chat-area { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #f5f7fa; }
.message-list { flex: 1; overflow-y: auto; padding: 16px 20px; min-height: 0; }
.empty { height: 100%; display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 16px; }

.msg-row { display: flex; margin-bottom: 12px; }
.msg-row.mine { justify-content: flex-end; }
.msg-row.bot .bubble { background: #f0f9eb; border: 1px solid #e1f3d8; }
.bot-badge {
  display: inline-block;
  background: #67c23a;
  color: #fff;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  margin-right: 4px;
}
.msg-system { text-align: center; color: #909399; font-size: 12px; margin: 8px 0; }
.bubble {
  max-width: min(60%, 480px);
  background: #fff;
  padding: 10px 14px;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
  position: relative;
  word-break: break-word;
}
.msg-row.mine .bubble { background: #409eff; color: #fff; }
.msg-row.mine .meta { color: rgba(255, 255, 255, 0.7); }
.meta { font-size: 11px; color: #909399; margin-bottom: 4px; }
.read-tick { color: #5cd600; font-weight: bold; margin-left: 4px; }
.msg-row.mine .read-tick { color: #c8f5a0; }
.text { font-size: 14px; line-height: 1.5; white-space: pre-wrap; }
.msg-image { max-width: 240px; max-height: 240px; border-radius: 4px; display: block; cursor: pointer; }
.msg-voice { display: flex; align-items: center; gap: 8px; min-width: 180px; }
.audio-player { height: 32px; flex: 1; min-width: 160px; }
.voice-meta { font-size: 12px; color: #909399; white-space: nowrap; }
.msg-row.mine .voice-meta { color: rgba(255,255,255,0.8); }
.recording-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; background: #fef0f0; border-radius: 4px;
  font-size: 12px; color: #f56c6c;
}
.rec-dot {
  width: 8px; height: 8px; border-radius: 50%; background: #f56c6c;
  animation: blink 1s infinite;
}
@keyframes blink { 50% { opacity: 0.3; } }
.emoji-grid {
  display: grid; grid-template-columns: repeat(8, 1fr);
  gap: 4px; max-height: 200px; overflow-y: auto;
}
.emoji-btn {
  border: none; background: transparent; font-size: 20px;
  padding: 4px; cursor: pointer; border-radius: 4px;
}
.emoji-btn:hover { background: #f0f9eb; }
.msg-actions { position: absolute; top: 2px; right: 4px; opacity: 0; transition: opacity 0.2s; }
.bubble:hover .msg-actions, .bubble:active .msg-actions { opacity: 1; }

.typing-indicator {
  display: flex; align-items: center; gap: 4px;
  padding: 8px 0; color: #909399; font-size: 12px;
}
.typing-indicator .dot {
  width: 6px; height: 6px; border-radius: 50%; background: #909399;
  animation: blink 1.4s infinite;
}
.typing-indicator .dot:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator .dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%, 60%, 100% { opacity: 0.3; } 30% { opacity: 1; } }

.composer {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  background: #fff;
  padding: 10px 12px;
  border-top: 1px solid #ebeef5;
  padding-bottom: calc(10px + var(--safe-bottom) + var(--safe-bottom-legacy));
}
.composer .el-textarea { flex: 1; min-width: 0; }
.composer .icon-btn,
.composer .send-btn { flex-shrink: 0; }

.skill-group { display: flex; flex-wrap: wrap; }

/* ============ 移动端 (≤ 768px) ============ */
@media (max-width: 768px) {
  .brand { font-size: 15px; }
  .topbar { padding: 0 8px 0 4px; min-height: 48px; }
  .chat-area { background: #f5f7fa; }
  .message-list { padding: 12px 12px; }
  .bubble {
    max-width: 82%;
    padding: 8px 12px;
  }
  .text { font-size: 15px; }
  .msg-image { max-width: 200px; max-height: 200px; }
  /* 按键区紧凑 */
  .composer { padding: 6px 8px; gap: 6px; }
  /* 移动端气泡可点撤回 (PC 用 hover) */
  .msg-actions { opacity: 0.6; }
}

@media (max-width: 380px) {
  .bubble { max-width: 88%; }
  .text { font-size: 14px; }
}
</style>