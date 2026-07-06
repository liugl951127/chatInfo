<template>
  <div class="chat-page">
    <header class="topbar">
      <div class="brand">在线客服 · 坐席端</div>
      <div class="user">
        <el-tag :type="connected ? 'success' : 'info'" size="small">
          {{ connected ? '在线' : '离线' }}
        </el-tag>
        <span class="nick">{{ userStore.nickname }}</span>
        <el-badge :value="waitingCount" :hidden="waitingCount === 0" class="waiting-badge">
          <el-button size="small" @click="claimOne" :disabled="!connected">
            抢一单
          </el-button>
        </el-badge>
        <el-button size="small" link @click="logout">退出</el-button>
      </div>
    </header>

    <main class="chat-main">
      <aside class="side">
        <h4>进行中的会话</h4>
        <div class="session-list">
          <div
            v-for="s in sessions"
            :key="s.id"
            class="session-item"
            :class="{ active: current?.id === s.id }"
            @click="select(s)">
            <div class="row1">
              <span>{{ s.sessionNo }}</span>
              <el-tag size="small" :type="statusTag(s.status)">{{ statusText(s.status) }}</el-tag>
            </div>
            <div class="row2">{{ s.lastMessage || '—' }}</div>
          </div>
          <el-empty v-if="!sessions.length" description="暂无会话" :image-size="60" />
        </div>
      </aside>

      <section class="chat-area">
        <div v-if="!current" class="empty">
          <el-empty description="等待客户接入中..." />
        </div>
        <template v-else>
          <div class="chat-header">
            <span>会话 {{ current.sessionNo }}</span>
            <el-button size="small" type="danger" plain @click="closeSession">关闭</el-button>
          </div>
          <div ref="messageListRef" class="message-list">
            <div v-for="(msg, idx) in messages" :key="idx" class="msg-row"
                 :class="{ mine: msg.senderId === userStore.id }">
              <div class="bubble">
                <div class="meta">
                  {{ msg.senderRole === 'AGENT' ? '我' : '客户' }}
                  · {{ formatTime(msg.createdAt) }}
                </div>
                <div class="text">{{ msg.content }}</div>
              </div>
            </div>
          </div>
          <div class="composer">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              placeholder="回复客户, Ctrl+Enter 发送"
              @keydown.ctrl.enter="send" />
            <el-button type="primary" :disabled="!draft.trim()" @click="send">发送</el-button>
          </div>
        </template>
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

const sessions = ref([])
const current = ref(null)
const messages = ref([])
const draft = ref('')
const connected = ref(false)
const waitingCount = ref(0)
const messageListRef = ref(null)
let stomp = null

onMounted(async () => {
  await refreshSessions()
  await refreshWaiting()
  connectWs()
  // 离线消息先尝试 drain 一次
  try {
    const offs = await imApi.drainOffline()
    offs.forEach(m => appendMessage(m))
  } catch {}
  // 定时刷新等待数
  setInterval(refreshWaiting, 5000)
})

onBeforeUnmount(() => stomp?.disconnect())

function connectWs() {
  stomp = new StompClient({
    token: userStore.token,
    onConnected: () => {
      connected.value = true
      stomp.subscribe('/user/queue/messages', (m) => {
        appendMessage(m)
        // 如果属于当前会话,自动滚到底; 不属于,刷新列表
        if (current.value && m.sessionId === current.value.id) {
          // 已经在 appendMessage 里滚了
        } else {
          refreshSessions()
        }
      })
      // 坐席监听新会话广播
      stomp.subscribe('/topic/sessions/new', async () => {
        await refreshWaiting()
        ElMessage.info('有新客户等待接入')
      })
    },
    onDisconnected: () => { connected.value = false },
    onError: () => { connected.value = false }
  })
  stomp.connect('/ws/agent')
}

async function refreshSessions() {
  try {
    sessions.value = await imApi.mySessions()
    if (!current.value && sessions.value.length) {
      select(sessions.value[0])
    }
  } catch {}
}

async function refreshWaiting() {
  try {
    const list = await imApi.waitingList()
    waitingCount.value = list.length
  } catch {}
}

async function claimOne() {
  try {
    const s = await imApi.claimSession()
    ElMessage.success(`已接入会话 ${s.sessionNo}`)
    await refreshSessions()
    select(s)
  } catch (e) {
    if (e.message?.includes('暂无')) ElMessage.warning(e.message)
  }
}

async function select(s) {
  current.value = s
  messages.value = []
  try {
    const list = await imApi.history(s.id, 100)
    messages.value = list
    await nextTick(); scrollToBottom()
  } catch {}
}

function send() {
  const text = draft.value.trim()
  if (!text || !current.value) return
  if (current.value.status === 'CLOSED') return ElMessage.warning('会话已关闭')
  stomp.send(`/app/send/${current.value.id}`, {
    sessionId: current.value.id,
    msgType: 'TEXT',
    content: text
  })
  draft.value = ''
}

function appendMessage(m) {
  if (current.value && m.sessionId !== current.value.id) {
    // 属于其他会话, 先刷新列表
    refreshSessions()
    return
  }
  messages.value.push(m)
  nextTick(scrollToBottom)
}

function scrollToBottom() {
  const el = messageListRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function formatTime(t) {
  if (!t) return ''
  return new Date(t).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function statusText(s) {
  return ({ WAITING: '等待', ACTIVE: '进行中', CLOSED: '已结束' })[s] || s
}
function statusTag(s) {
  return ({ WAITING: 'warning', ACTIVE: 'success', CLOSED: 'info' })[s] || ''
}

async function closeSession() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('关闭会话?', '提示', { type: 'warning' })
    await imApi.closeSession(current.value.id)
    current.value = { ...current.value, status: 'CLOSED' }
    await refreshSessions()
    ElMessage.success('会话已关闭')
  } catch {}
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
.waiting-badge :deep(.el-badge__content) { background: #f56c6c; }

.chat-main { flex: 1; display: flex; overflow: hidden; }
.side {
  width: 280px;
  background: #fff;
  border-right: 1px solid #ebeef5;
  padding: 16px;
  overflow-y: auto;
}
.side h4 { margin: 0 0 12px; }
.session-list { display: flex; flex-direction: column; gap: 8px; }
.session-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  background: #f5f7fa;
  transition: background .2s;
}
.session-item:hover { background: #ecf5ff; }
.session-item.active { background: #409eff; color: #fff; }
.session-item.active .row2 { color: rgba(255,255,255,.85); }
.row1 { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.row2 { font-size: 12px; color: #909399; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}
.empty { height: 100%; display: flex; align-items: center; justify-content: center; }
.chat-header {
  height: 48px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 500;
}
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px;
}
.msg-row { display: flex; margin-bottom: 12px; }
.msg-row.mine { justify-content: flex-end; }
.bubble {
  max-width: 60%;
  background: #fff;
  padding: 10px 14px;
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(0,0,0,.06);
}
.msg-row.mine .bubble { background: #409eff; color: #fff; }
.msg-row.mine .meta { color: rgba(255,255,255,.7); }
.meta { font-size: 11px; color: #909399; margin-bottom: 4px; }
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