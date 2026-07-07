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
          <el-button size="small" type="danger" plain class="side-btn" @click="closeSession">
            结束会话
          </el-button>
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
          <div ref="messageListRef" class="message-list scroll-smooth" @scroll="onScroll">
            <template v-for="(msg, idx) in messages" :key="msg.id || idx">
              <div v-if="msg.msgType === 'SYSTEM' || msg.msgType === 'RECALL'" class="msg-system">
                {{ msg.content }}
              </div>
              <div v-else class="msg-row" :class="{ mine: msg.senderId === userStore.id }">
                <div class="bubble">
                  <div class="meta">
                    {{ msg.senderRole === 'AGENT' ? '客服' : '我' }}
                    · {{ formatTime(msg.createdAt) }}
                    <span v-if="msg.senderId === userStore.id && msg.id && readMap[msg.id]" class="read-tick" title="对方已读">
                      ✓✓
                    </span>
                  </div>
                  <img v-if="msg.msgType === 'IMAGE'" :src="msg.content" class="msg-image" @click="previewImage(msg.content)" />
                  <div v-else class="text">{{ msg.content }}</div>
                  <div v-if="msg.senderId === userStore.id && msg.id && !recalledMap[msg.id]" class="msg-actions">
                    <el-button link size="small" @click="recall(msg.id)" v-if="canRecall(msg)">
                      撤回
                    </el-button>
                  </div>
                </div>
              </div>
            </template>
            <div v-if="peerTyping" class="typing-indicator">
              <span class="dot"></span><span class="dot"></span><span class="dot"></span>
              <span class="text">{{ peerTyping }} 正在输入...</span>
            </div>
          </div>

          <div class="composer">
            <input ref="fileInputRef" type="file" accept="image/*" style="display:none" @change="onImagePick" />
            <el-button :icon="Picture" size="large" class="icon-btn" @click="fileInputRef?.click()" title="发图片" />
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
      <template #footer>
        <el-button type="primary" size="large" :loading="creating" @click="startSession">开始咨询</el-button>
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
import { Picture, Menu } from '@element-plus/icons-vue'
import { imApi } from '@/api/im'
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
const messageListRef = ref(null)
const fileInputRef = ref(null)
const readMap = ref({})
const recalledMap = ref({})
const peerTyping = ref('')

const showSkillPicker = ref(false)
const selectedSkill = ref('')
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
  // 主动结束录制 (正常结束: USER_STOP; 其他会话关闭原因是 NORMAL)
  if (recorder && recorder.recordId) {
    recorder.stop('USER_STOP').catch(() => {})
  }
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
  }
}

function previewImage(url) {
  previewImageUrl.value = url
}

async function startSession() {
  creating.value = true
  try {
    session.value = await imApi.createSession(selectedSkill.value || null)
    messages.value = []
    showSkillPicker.value = false
    if (isMobile.value) drawerVisible.value = false
    appendMessage({
      msgType: 'SYSTEM', senderRole: 'SYSTEM',
      content: session.value.status === 'WAITING' ? '已创建会话, 正在为您匹配客服...' : '客服已接入, 请开始提问',
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
  let ok = false
  try {
    await ElMessageBox.confirm(
      '为了服务质量与合规要求, 我们会对本次会话页面进行视频录制 (包括聊天内容和页面操作), 仅供内部审计/质量回溯使用。',
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
  recorder = new ChatRecordSDK({
    apiBase: '/api/im/record',
    token: userStore.token,
    sessionId: session.value.id,
    userId: userStore.id,
    target: '.customer-shell',
    fps: 2,
    chunkDurationMs: 5000,
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
  if (el) el.scrollTop = el.scrollHeight
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
  } catch {}
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

.chat-area { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #f5f7fa; }
.message-list { flex: 1; overflow-y: auto; padding: 16px 20px; }
.empty { height: 100%; display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 16px; }

.msg-row { display: flex; margin-bottom: 12px; }
.msg-row.mine { justify-content: flex-end; }
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