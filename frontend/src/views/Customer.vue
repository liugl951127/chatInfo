<template>
  <div class="chat-page">
    <header class="topbar">
      <div class="brand">在线客服 · 客户端</div>
      <div class="user">
        <el-tag :type="connected ? 'success' : 'warning'" size="small">
          {{ connected ? '已连接' : (reconnecting ? '重连中...' : '已断开') }}
        </el-tag>
        <span class="nick">{{ userStore.nickname }}</span>
        <el-button size="small" link @click="logout">退出</el-button>
      </div>
    </header>

    <main class="chat-main">
      <aside class="side">
        <el-button v-if="!session" type="primary" @click="showSkillPicker = true">
          开始咨询
        </el-button>

        <div v-if="session" class="session-info">
          <p>会话号: <code>{{ session.sessionNo }}</code></p>
          <p>技能: <el-tag size="small">{{ session.skillTag || '通用' }}</el-tag></p>
          <p>状态: <el-tag size="small" :type="statusTagType">{{ statusText }}</el-tag></p>
          <p v-if="session.agentId">客服: #{{ session.agentId }}</p>
          <el-button size="small" type="danger" plain @click="closeSession" style="margin-top: 8px;">
            结束会话
          </el-button>
        </div>
      </aside>

      <section class="chat-area">
        <div v-if="!session" class="empty">
          <el-empty description="点击左侧「开始咨询」选择问题类型" />
        </div>
        <template v-else>
          <div ref="messageListRef" class="message-list" @scroll="onScroll">
            <template v-for="(msg, idx) in messages" :key="msg.id || idx">
              <!-- 系统消息 -->
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
                  <!-- 图片消息 -->
                  <img v-if="msg.msgType === 'IMAGE'" :src="msg.content" class="msg-image" />
                  <div v-else class="text">{{ msg.content }}</div>
                  <div v-if="msg.senderId === userStore.id && msg.id && !recalledMap[msg.id]" class="msg-actions">
                    <el-button link size="small" @click="recall(msg.id)" v-if="canRecall(msg)">
                      撤回
                    </el-button>
                  </div>
                </div>
              </div>
            </template>

            <!-- 对方正在输入 -->
            <div v-if="peerTyping" class="typing-indicator">
              <span class="dot"></span><span class="dot"></span><span class="dot"></span>
              <span class="text">{{ peerTyping }} 正在输入...</span>
            </div>
          </div>

          <div class="composer">
            <input ref="fileInputRef" type="file" accept="image/*" style="display:none" @change="onImagePick" />
            <el-button :icon="Picture" @click="fileInputRef?.click()" title="发图片"></el-button>
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              placeholder="输入消息, Ctrl+Enter 发送"
              @keydown.ctrl.enter="send"
              @input="onTyping" />
            <el-button type="primary" :disabled="!draft.trim()" @click="send">发送</el-button>
          </div>
        </template>
      </section>
    </main>

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
        <el-button type="primary" :loading="creating" @click="startSession">开始咨询</el-button>
      </template>
    </el-dialog>

    <!-- CSAT 评分弹窗 -->
    <el-dialog v-model="showRating" title="请对本次服务评分" width="380px" :show-close="false" :close-on-click-modal="false">
      <el-rate v-model="rating" :max="5" size="large" />
      <el-input v-model="ratingComment" type="textarea" :rows="3" placeholder="说点什么吧 (可选)" style="margin-top: 16px;" />
      <template #footer>
        <el-button @click="skipRating">跳过</el-button>
        <el-button type="primary" :loading="ratingSubmitting" @click="submitRating">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import { imApi } from '@/api/im'
import { useUserStore } from '@/stores/user'
import { StompClient } from '@/utils/ws-client'

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

let stomp = null
let typingTimer = null

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

onMounted(async () => {
  // 1) 看是否有进行中会话
  try {
    const list = await imApi.mySessions()
    const active = list.find(s => s.status !== 'CLOSED')
    if (active) {
      session.value = active
      await loadHistory()
    }
  } catch {}
  // 2) 拉离线
  try {
    const offs = await imApi.drainOffline()
    offs.forEach(m => appendMessage(m))
  } catch {}
  // 3) 起 WebSocket
  connectWs()
})

onBeforeUnmount(() => {
  stomp?.disconnect()
})

function connectWs() {
  if (stomp) stomp.disconnect()
  reconnecting.value = !connected.value
  stomp = new StompClient({
    token: userStore.token,
    onConnected: () => {
      connected.value = true
      reconnecting.value = false
      stomp.subscribe('/user/queue/messages', onIncomingMessage)
      stomp.subscribe('/user/queue/events', onEvent)
      stomp.subscribe('/topic/typing/' + (session.value?.id || 0), onTypingEvent)
    },
    onDisconnected: () => {
      connected.value = false
      reconnecting.value = true
    },
    onError: () => { connected.value = false; reconnecting.value = true }
  })
  stomp.connect('/ws/customer')
}

function onIncomingMessage(msg) {
  // 消息对象: { id, sessionId, senderId, senderRole, msgType, content, createdAt }
  if (session.value && msg.sessionId && msg.sessionId !== session.value.id) return
  appendMessage(msg)
  // 自动 ACK 已读 (除了自己发的)
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
      m.recalled = true
      m.msgType = 'RECALL'
      m.content = '对方撤回了一条消息'
    }
  } else if (payload.type === 'TRANSFERRED') {
    ElMessage.info('会话已转接给其他客服')
  }
}

function onTypingEvent(payload) {
  if (!payload || !session.value) return
  if (payload.sessionId !== session.value.id) return
  if (payload.userId === userStore.id) return
  if (payload.typing) {
    peerTyping.value = payload.role === 'AGENT' ? '客服' : '对方'
  } else {
    peerTyping.value = ''
  }
}

async function startSession() {
  creating.value = true
  try {
    session.value = await imApi.createSession(selectedSkill.value || null)
    messages.value = []
    showSkillPicker.value = false
    appendMessage({
      msgType: 'SYSTEM',
      senderRole: 'SYSTEM',
      content: session.value.status === 'WAITING'
        ? '已创建会话, 正在为您匹配客服...'
        : '客服已接入, 请开始提问',
      createdAt: new Date().toISOString()
    }, true)
    scrollToBottom()
  } finally {
    creating.value = false
  }
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
  if (session.value.status === 'CLOSED') {
    return ElMessage.warning('会话已结束')
  }
  const ok = stomp.send(`/app/send/${session.value.id}`, {
    sessionId: session.value.id,
    msgType: 'TEXT',
    content: text
  })
  if (ok) {
    draft.value = ''
    sendTyping(false)
  }
}

function onImagePick(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 5MB')
    return
  }
  // v2 demo: 用 data URL (生产建议上传到 OSS / MinIO)
  const reader = new FileReader()
  reader.onload = () => {
    stomp?.send(`/app/send/${session.value.id}`, {
      sessionId: session.value.id,
      msgType: 'IMAGE',
      content: reader.result
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
    if (m) {
      m.recalled = true
      m.msgType = 'RECALL'
      m.content = '你撤回了一条消息'
    }
    ElMessage.success('已撤回')
  } catch (e) {
    ElMessage.error(e.message || '撤回失败')
  }
}

function canRecall(msg) {
  if (!msg.id || !msg.createdAt) return false
  const ms = new Date(msg.createdAt).getTime()
  return Date.now() - ms < 2 * 60 * 1000
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

function onScroll() {
  // 简化: 不做向上翻页加载历史
}

function formatTime(t) {
  if (!t) return ''
  return new Date(t).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

async function closeSession() {
  if (!session.value) return
  try {
    await imApi.closeSession(session.value.id)
    session.value = { ...session.value, status: 'CLOSED' }
    // 弹评分
    pendingRatingSessionId.value = session.value.id
    showRating.value = true
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

function skipRating() {
  showRating.value = false
  ratingComment.value = ''
}

function logout() {
  stomp?.disconnect()
  userStore.logout()
  router.replace('/login')
}
</script>

<style scoped>
.chat-page { height: 100vh; display: flex; flex-direction: column; }
.topbar {
  height: 56px; background: #fff; border-bottom: 1px solid #ebeef5;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; box-shadow: 0 1px 4px rgba(0,21,41,.08);
}
.brand { font-weight: bold; font-size: 16px; }
.user { display: flex; align-items: center; gap: 12px; }
.nick { font-size: 14px; color: #606266; }
.chat-main { flex: 1; display: flex; overflow: hidden; }
.side {
  width: 240px; background: #fff; border-right: 1px solid #ebeef5; padding: 16px;
}
.session-info { margin-top: 16px; font-size: 13px; color: #606266; line-height: 1.8; }
.session-info code { font-family: 'Courier New', monospace; color: #409eff; }
.chat-area { flex: 1; display: flex; flex-direction: column; background: #f5f7fa; }
.message-list {
  flex: 1; overflow-y: auto; padding: 16px 24px;
}
.empty { height: 100%; display: flex; align-items: center; justify-content: center; }
.msg-row { display: flex; margin-bottom: 12px; }
.msg-row.mine { justify-content: flex-end; }
.msg-system {
  text-align: center; color: #909399; font-size: 12px; margin: 8px 0;
}
.bubble {
  max-width: 60%; background: #fff; padding: 10px 14px; border-radius: 8px;
  box-shadow: 0 1px 2px rgba(0,0,0,.06); position: relative;
}
.msg-row.mine .bubble { background: #409eff; color: #fff; }
.msg-row.mine .meta { color: rgba(255,255,255,.7); }
.meta { font-size: 11px; color: #909399; margin-bottom: 4px; }
.read-tick { color: #5cd600; font-weight: bold; margin-left: 4px; }
.msg-row.mine .read-tick { color: #c8f5a0; }
.text { font-size: 14px; line-height: 1.5; word-break: break-word; white-space: pre-wrap; }
.msg-image { max-width: 240px; max-height: 240px; border-radius: 4px; display: block; }
.msg-actions { position: absolute; top: 2px; right: 4px; opacity: 0; transition: opacity .2s; }
.bubble:hover .msg-actions { opacity: 1; }

.typing-indicator {
  display: flex; align-items: center; gap: 4px; padding: 8px 0; color: #909399; font-size: 12px;
}
.typing-indicator .dot {
  width: 6px; height: 6px; border-radius: 50%; background: #909399;
  animation: blink 1.4s infinite;
}
.typing-indicator .dot:nth-child(2) { animation-delay: .2s; }
.typing-indicator .dot:nth-child(3) { animation-delay: .4s; }
@keyframes blink { 0%, 60%, 100% { opacity: .3; } 30% { opacity: 1; } }

.composer {
  background: #fff; padding: 12px 16px; display: flex; gap: 8px; align-items: flex-end;
  border-top: 1px solid #ebeef5;
}
.composer .el-textarea { flex: 1; }
.skill-group { display: flex; flex-wrap: wrap; }
</style>