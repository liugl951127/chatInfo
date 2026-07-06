<template>
  <div class="chat-page">
    <header class="topbar">
      <div class="brand">在线客服 · 客户端</div>
      <div class="user">
        <el-tag :type="connected ? 'success' : 'info'" size="small">
          {{ connected ? '在线' : '离线' }}
        </el-tag>
        <span class="nick">{{ userStore.nickname }}</span>
        <el-button size="small" link @click="logout">退出</el-button>
      </div>
    </header>

    <main class="chat-main">
      <aside class="side">
        <el-button type="primary" :disabled="!!session" @click="startSession">
          {{ session ? '会话进行中' : '开始咨询' }}
        </el-button>

        <div v-if="session" class="session-info">
          <p>会话号: <code>{{ session.sessionNo }}</code></p>
          <p>状态: <el-tag size="small" :type="statusTagType">{{ statusText }}</el-tag></p>
          <el-button size="small" type="danger" plain @click="closeSession" style="margin-top: 8px;">
            结束会话
          </el-button>
        </div>
      </aside>

      <section class="chat-area">
        <div ref="messageListRef" class="message-list">
          <div v-if="!session" class="empty">
            <el-empty description="点击左侧「开始咨询」创建会话" />
          </div>
          <template v-else>
            <div v-for="(msg, idx) in messages" :key="idx" class="msg-row"
                 :class="{ mine: msg.senderId === userStore.id }">
              <div class="bubble">
                <div class="meta">
                  {{ msg.senderRole === 'AGENT' ? '客服' : '我' }}
                  · {{ formatTime(msg.createdAt) }}
                </div>
                <div class="text">{{ msg.content }}</div>
              </div>
            </div>
          </template>
        </div>

        <div v-if="session" class="composer">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            placeholder="输入消息, Ctrl+Enter 发送"
            @keydown.ctrl.enter="send" />
          <el-button type="primary" :disabled="!draft.trim()" @click="send">发送</el-button>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { imApi } from '@/api/im'
import { useUserStore } from '@/stores/user'
import { StompClient } from '@/utils/ws-client'

const router = useRouter()
const userStore = useUserStore()

const session = ref(null)
const messages = ref([])
const draft = ref('')
const connected = ref(false)
const messageListRef = ref(null)
let stomp = null

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
  // 1) 先看是否有进行中的会话
  try {
    const list = await imApi.mySessions()
    const active = list.find(s => s.status !== 'CLOSED')
    if (active) {
      session.value = active
      await loadHistory()
    }
  } catch (e) { /* noop */ }

  // 2) 建 WebSocket
  connectWs()

  // 3) 拉离线消息
  try {
    const offs = await imApi.drainOffline()
    offs.forEach(m => appendMessage(m))
  } catch {}
})

onBeforeUnmount(() => {
  stomp?.disconnect()
})

function connectWs() {
  stomp = new StompClient({
    token: userStore.token,
    onConnected: () => {
      connected.value = true
      stomp.subscribe('/user/queue/messages', (m) => appendMessage(m))
      stomp.subscribe('/user/queue/errors', (m) => ElMessage.error(m.message || '发送失败'))
    },
    onDisconnected: () => { connected.value = false },
    onError: () => { connected.value = false }
  })
  stomp.connect('/ws/customer')
}

async function startSession() {
  try {
    session.value = await imApi.createSession()
    messages.value = []
    appendMessage({
      senderRole: 'SYSTEM',
    content: session.value.status === 'WAITING'
      ? '已创建会话, 正在为您匹配客服...'
      : '客服已接入, 请开始提问',
      createdAt: new Date().toISOString()
    }, /*skipScroll*/ true)
    scrollToBottom()
  } catch (e) { /* noop */ }
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
    ElMessage.warning('会话已结束')
    return
  }
  stomp.send(`/app/send/${session.value.id}`, {
    sessionId: session.value.id,
    msgType: 'TEXT',
    content: text
  })
  draft.value = ''
}

function appendMessage(m, skipScroll = false) {
  messages.value.push(m)
  if (!skipScroll) nextTick(scrollToBottom)
}

function scrollToBottom() {
  const el = messageListRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

async function closeSession() {
  if (!session.value) return
  try {
    await ElMessageBox.confirm('确定结束此次会话?', '提示', { type: 'warning' })
    await imApi.closeSession(session.value.id)
    session.value = { ...session.value, status: 'CLOSED' }
    ElMessage.success('会话已结束')
  } catch {}
}

function logout() {
  stomp?.disconnect()
  userStore.logout()
  router.replace('/login')
}
</script>

<style scoped>
.chat-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
}
.topbar {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  box-shadow: 0 1px 4px rgba(0,21,41,.08);
}
.brand { font-weight: bold; font-size: 16px; }
.user { display: flex; align-items: center; gap: 12px; }
.nick { font-size: 14px; color: #606266; }

.chat-main {
  flex: 1;
  display: flex;
  overflow: hidden;
}
.side {
  width: 240px;
  background: #fff;
  border-right: 1px solid #ebeef5;
  padding: 16px;
}
.session-info {
  margin-top: 16px;
  font-size: 13px;
  color: #606266;
}
.session-info code {
  font-family: 'Courier New', monospace;
  color: #409eff;
}
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px;
}
.empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.msg-row {
  display: flex;
  margin-bottom: 12px;
}
.msg-row.mine {
  justify-content: flex-end;
}
.bubble {
  max-width: 60%;
  background: #fff;
  padding: 10px 14px;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(0,0,0,.06);
}
.msg-row.mine .bubble {
  background: #409eff;
  color: #fff;
}
.msg-row.mine .meta { color: rgba(255,255,255,.7); }
.meta {
  font-size: 11px;
  color: #909399;
  margin-bottom: 4px;
}
.text { font-size: 14px; line-height: 1.5; word-break: break-word; }

.composer {
  background: #fff;
  padding: 12px 16px;
  display: flex;
  gap: 12px;
  align-items: flex-end;
  border-top: 1px solid #ebeef5;
}
</style>