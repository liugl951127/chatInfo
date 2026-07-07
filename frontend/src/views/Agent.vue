<template>
  <div class="chat-page">
    <header class="topbar">
      <div class="brand-area">
        <el-button v-if="isMobile" link class="menu-btn" @click="drawerVisible = true">
          <el-icon size="20"><Menu /></el-icon>
        </el-button>
        <span class="brand">坐席工作台</span>
        <el-tag v-if="activeSessions.length" size="small" type="primary" class="session-badge">
          {{ activeSessions.length }}
        </el-tag>
      </div>
      <div class="user-area">
        <el-select v-model="agentStatus" size="small" style="width: 96px;" @change="onStatusChange">
          <el-option label="🟢 在线" value="ONLINE" />
          <el-option label="🟡 忙碌" value="BUSY" />
          <el-option label="⚫ 离开" value="AWAY" />
        </el-select>
        <el-tag :type="connected ? 'success' : 'warning'" size="small">
          {{ connected ? '已连接' : (reconnecting ? '重连' : '断开') }}
        </el-tag>
        <el-badge :value="waitingCount" :hidden="waitingCount === 0" class="waiting-badge">
          <el-button size="small" @click="claimOne" :disabled="!connected">抢单</el-button>
        </el-badge>
        <el-button size="small" link @click="logout">退出</el-button>
      </div>
    </header>

    <main class="chat-main">
      <!-- 桌面: 左侧固定; 手机: 抽屉 -->
      <aside v-if="!isMobile" class="side">
        <div class="side-header">
          <span>进行中 ({{ activeSessions.length }})</span>
          <div>
            <el-button link size="small" @click="refreshSessions">刷新</el-button>
            <el-button link size="small" @click="goReplay" :disabled="!current">回溯</el-button>
          </div>
        </div>
        <div class="session-list">
          <div v-for="s in activeSessions" :key="s.id"
               class="session-item"
               :class="{ active: current?.id === s.id }"
               @click="select(s)">
            <div class="row1">
              <span class="online-dot" :class="{ on: s.peerOnline === true, off: s.peerOnline === false }" :title="s.peerOnline ? '客户在线' : '客户离线'"></span>
              <span class="sno">{{ s.sessionNo }}</span>
              <el-badge :value="unreadMap[s.id] || 0" :hidden="!unreadMap[s.id]" :max="99">
                <el-tag size="small" :type="statusTag(s.status)">{{ statusText(s.status) }}</el-tag>
              </el-badge>
            </div>
            <div class="row2">
              <el-tag v-if="s.skillTag" size="small" type="info">{{ s.skillTag }}</el-tag>
              {{ s.lastMessage || '—' }}
            </div>
          </div>
          <el-empty v-if="!activeSessions.length" description="暂无会话" :image-size="60" />
        </div>
      </aside>

      <!-- 移动端: 抽屉 -->
      <el-drawer v-if="isMobile" v-model="drawerVisible"
                 :title="`会话 (${activeSessions.length})`" direction="ltr" size="80%">
        <div class="session-list session-list-mobile">
          <div v-for="s in activeSessions" :key="s.id"
               class="session-item"
               :class="{ active: current?.id === s.id }"
               @click="select(s)">
            <div class="row1">
              <span class="online-dot" :class="{ on: s.peerOnline === true, off: s.peerOnline === false }" :title="s.peerOnline ? '客户在线' : '客户离线'"></span>
              <span class="sno">{{ s.sessionNo }}</span>
              <el-badge :value="unreadMap[s.id] || 0" :hidden="!unreadMap[s.id]" :max="99">
                <el-tag size="small" :type="statusTag(s.status)">{{ statusText(s.status) }}</el-tag>
              </el-badge>
            </div>
            <div class="row2">
              <el-tag v-if="s.skillTag" size="small" type="info">{{ s.skillTag }}</el-tag>
              {{ s.lastMessage || '—' }}
            </div>
          </div>
          <el-empty v-if="!activeSessions.length" description="暂无会话" :image-size="60" />
        </div>
      </el-drawer>

      <section class="chat-area">
        <div v-if="!current" class="empty">
          <el-empty description="等待客户接入中…" />
          <el-button v-if="isMobile && waitingCount > 0" type="primary" size="large"
                     @click="claimOne">抢单 ({{ waitingCount }})</el-button>
        </div>
        <template v-else>
          <div class="chat-header">
            <div class="header-left">
              <span class="online-dot" :class="{ on: current.peerOnline === true, off: current.peerOnline === false }" :title="current.peerOnline ? '客户在线' : '客户离线'"></span>
              <span class="sno">{{ current.sessionNo }}</span>
              <el-tag size="small" type="info" v-if="current.skillTag">{{ current.skillTag }}</el-tag>
              <span class="cust" v-if="!isMobile">客户 #{{ current.customerId }}</span>
              <el-tag v-if="current.peerOnline === false" size="small" type="warning" effect="plain">客户已离线</el-tag>
            </div>
            <div class="header-right">
              <el-button size="small" @click="openTransfer">转接</el-button>
              <el-button size="small" @click="openCanned">模板</el-button>
              <el-button v-if="!isMobile" size="small" type="danger" plain @click="closeSession">关闭</el-button>
              <el-button v-else size="small" type="danger" plain @click="closeSession">×</el-button>
            </div>
          </div>
          <div ref="messageListRef" class="message-list scroll-smooth">
            <div v-for="(msg, idx) in messages" :key="msg.id || idx">
              <div v-if="msg.msgType === 'SYSTEM' || msg.msgType === 'RECALL'" class="msg-system">
                {{ msg.content }}
              </div>
              <div v-else class="msg-row" :class="{ mine: msg.senderId === userStore.id }">
                <div class="bubble">
                  <div class="meta">
                    {{ msg.senderRole === 'AGENT' ? '我' : '客户' }}
                    · {{ formatTime(msg.createdAt) }}
                    <span v-if="msg.senderId === userStore.id && msg.id && readMap[msg.id]" class="read-tick">✓✓</span>
                  </div>
                  <img v-if="msg.msgType === 'IMAGE'" :src="msg.content" class="msg-image"
                       @click="previewImageUrl = msg.content" />
                  <div v-else class="text">{{ msg.content }}</div>
                </div>
              </div>
            </div>
            <div v-if="peerTyping" class="typing-indicator">
              <span class="dot"></span><span class="dot"></span><span class="dot"></span>
              <span class="text">客户正在输入...</span>
            </div>
          </div>
          <div class="composer">
            <input ref="fileInputRef" type="file" accept="image/*" style="display:none" @change="onImagePick" />
            <el-button v-if="!isMobile" :icon="Picture" size="large" class="icon-btn"
                       @click="fileInputRef?.click()" title="发图片" />
            <el-button v-if="!isMobile" size="small" @click="openCanned" plain>模板</el-button>
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              :autosize="{ minRows: 1, maxRows: 4 }"
              placeholder="回复客户…"
              @keydown.ctrl.enter.prevent="send"
              @input="onTyping" />
            <el-button type="primary" size="large" class="send-btn" :disabled="!draft.trim()" @click="send">发送</el-button>
          </div>
          <!-- 移动端快捷工具: 浮动按钮或额外按钮条 -->
          <div v-if="isMobile" class="mobile-tools">
            <el-button size="small" :icon="Picture" @click="fileInputRef?.click()" />
            <el-button size="small" @click="openCanned">模板</el-button>
            <el-button size="small" type="danger" plain @click="closeSession">关闭</el-button>
          </div>
        </template>
      </section>
    </main>

    <el-dialog v-model="showTransfer" title="转接到其他坐席" :width="isMobile ? '92vw' : '420px'">
      <el-select v-model="transferTo" placeholder="选择目标坐席" filterable style="width: 100%;">
        <el-option v-for="a in otherAgents" :key="a.id"
          :label="`${a.nickname} (${a.skillTags || '无技能'}) ${a.online ? '🟢' : '⚫'}`"
          :value="a.id" :disabled="a.self" />
      </el-select>
      <el-input v-model="transferReason" type="textarea" :rows="2"
                placeholder="转接原因 (可选)" style="margin-top: 12px;" />
      <template #footer>
        <el-button size="large" @click="showTransfer = false">取消</el-button>
        <el-button size="large" type="primary" :disabled="!transferTo" @click="doTransfer">确认转接</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showCanned" title="快捷回复" :width="isMobile ? '92vw' : '600px'">
      <div style="margin-bottom: 12px;">
        <el-input v-model="cannedFilter" placeholder="搜索…" clearable size="default" />
      </div>
      <el-table :data="filteredCanned" max-height="380" @row-click="useCanned" style="cursor: pointer;">
        <el-table-column prop="title" label="标题" width="100" />
        <el-table-column prop="skillTag" label="技能" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.skillTag" size="small">{{ row.skillTag }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" />
      </el-table>
      <template #footer>
        <el-button size="large" @click="showCanned = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-image-viewer v-if="previewImageUrl" :url-list="[previewImageUrl]" :initial-index="0"
      @close="previewImageUrl = null" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, onUnmounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture, Menu } from '@element-plus/icons-vue'
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
const reconnecting = ref(false)
const waitingCount = ref(0)
const unreadMap = ref({})
const readMap = ref({})
const peerTyping = ref('')
const messageListRef = ref(null)
const fileInputRef = ref(null)
const previewImageUrl = ref(null)

const agentStatus = ref('ONLINE')

const showTransfer = ref(false)
const transferTo = ref(null)
const transferReason = ref('')
const otherAgents = ref([])

const showCanned = ref(false)
const cannedList = ref([])
const cannedFilter = ref('')

const isMobile = ref(false)
const drawerVisible = ref(false)

const filteredCanned = computed(() => {
  if (!cannedFilter.value) return cannedList.value
  const kw = cannedFilter.value.toLowerCase()
  return cannedList.value.filter(c =>
    c.title.toLowerCase().includes(kw) || c.content.toLowerCase().includes(kw))
})

const activeSessions = computed(() => sessions.value.filter(s => s.status !== 'CLOSED'))

let stomp = null
let typingTimer = null
let waitingTimer = null
const mqMobile = typeof window !== 'undefined' ? window.matchMedia('(max-width: 768px)') : null
function updateIsMobile() { isMobile.value = mqMobile?.matches ?? false }

watch(current, async (newCur, oldCur) => {
  if (newCur && oldCur && newCur.id !== oldCur.id) {
    try {
      await imApi.readAll(newCur.id)
      unreadMap.value = { ...unreadMap.value, [newCur.id]: 0 }
    } catch {}
  }
})

onMounted(async () => {
  if (mqMobile) {
    updateIsMobile()
    mqMobile.addEventListener('change', updateIsMobile)
  }
  await refreshSessions()
  await loadAgentStatus()
  connectWs()
  try {
    const offs = await imApi.drainOffline()
    offs.forEach(m => appendMessage(m))
  } catch {}
  waitingTimer = setInterval(refreshWaiting, 5000)
})

onBeforeUnmount(() => {
  clearInterval(waitingTimer)
  stomp?.disconnect()
})

onUnmounted(() => {
  if (mqMobile) mqMobile.removeEventListener('change', updateIsMobile)
})

async function loadAgentStatus() {
  try {
    const r = await imApi.getAgentStatus()
    agentStatus.value = r.status || 'ONLINE'
  } catch {}
}

async function onStatusChange(v) {
  try {
    await imApi.setAgentStatus(v)
    ElMessage.success(`状态: ${v}`)
  } catch {
    ElMessage.error('状态切换失败')
  }
}

function connectWs() {
  if (stomp) stomp.disconnect()
  reconnecting.value = !connected.value
  stomp = new StompClient({
    token: userStore.token,
    onConnected: () => { connected.value = true; reconnecting.value = false },
    onReconnected: () => {
      // 重连成功 — 刷新会话列表 + 当前会话未读数, 兔底断网期间丢失的消息
      console.log('[stomp] 重连成功, 刷新会话状态')
      refreshSessions()
      if (current.value) {
        imApi.readAll(current.value.id).catch(() => {})
      }
    },
    onDisconnected: () => { connected.value = false; reconnecting.value = true },
    onError: () => { connected.value = false; reconnecting.value = true }
  })
  stomp.subscribe('/user/queue/messages', onIncomingMessage)
  stomp.subscribe('/user/queue/events', onEvent)
  stomp.subscribe('/topic/sessions/new', onNewSession)
  if (current.value) stomp.subscribe('/topic/typing/' + current.value.id, onTypingEvent)
  stomp.connect('/ws/agent')
}

function onIncomingMessage(m) {
  if (!m.sessionId) return
  if (current.value && m.sessionId === current.value.id) {
    appendMessage(m)
    if (m.id && m.senderId !== userStore.id) imApi.readMessage(m.id).catch(() => {})
  } else {
    unreadMap.value = { ...unreadMap.value, [m.sessionId]: (unreadMap.value[m.sessionId] || 0) + 1 }
    refreshSessions()
  }
}

function onEvent(payload) {
  if (!payload) return
  if (payload.type === 'READ' && payload.messageId) {
    readMap.value = { ...readMap.value, [payload.messageId]: true }
  } else if (payload.type === 'RECALL' && payload.messageId) {
    const m = messages.value.find(x => x.id === payload.messageId)
    if (m) { m.recalled = true; m.msgType = 'RECALL'; m.content = '对方撤回了一条消息' }
  } else if (payload.type === 'TRANSFERRED') {
    ElMessage.info('会话已被转接')
    refreshSessions()
  } else if (payload.type === 'PRESENCE') {
    // 对端 (客户) 上线/下线状态变更 → 更新会话列表中的在线状态点
    const i = sessions.value.findIndex(s => s.id === payload.sessionId)
    if (i >= 0) {
      sessions.value[i] = { ...sessions.value[i], peerOnline: payload.online, lastSeen: payload.ts }
    }
    // 如果是当前会话, 也更新顶部状态条
    if (current.value && current.value.id === payload.sessionId) {
      current.value = { ...current.value, peerOnline: payload.online, lastSeen: payload.ts }
    }
  }
}

function onNewSession(payload) {
  refreshWaiting()
  if (payload?.type === 'NEW_WAITING') {
    ElMessage.info(`新会话等待接入 [${payload.skillTag || '通用'}]`)
  }
}

function onTypingEvent(payload) {
  if (!payload || !current.value) return
  if (payload.sessionId !== current.value.id) return
  if (payload.userId === userStore.id) return
  peerTyping.value = payload.typing ? '客户' : ''
}

function goReplay() {
  if (!current.value) {
    ElMessage.warning('请先选择一个会话')
    return
  }
  router.push('/replay/' + current.value.id)
}

async function refreshSessions() {
  try {
    sessions.value = await imApi.mySessions()
    if (!current.value && activeSessions.value.length) select(activeSessions.value[0])
    activeSessions.value.forEach(async (s) => {
      try {
        const n = await imApi.unread(s.id)
        unreadMap.value = { ...unreadMap.value, [s.id]: n }
      } catch {}
    })
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
    ElMessage.success(`已接入 ${s.sessionNo}`)
    await refreshSessions()
    select(s)
  } catch (e) {
    if (e.message?.includes('暂无')) ElMessage.warning(e.message)
  }
}

async function select(s) {
  if (current.value && current.value.id !== s.id) {
    stomp?.unsubscribe('/topic/typing/' + current.value.id, onTypingEvent)
  }
  current.value = s
  drawerVisible.value = false
  messages.value = []
  try {
    const list = await imApi.history(s.id, 100)
    messages.value = list
    await nextTick(); scrollToBottom()
    stomp?.subscribe('/topic/typing/' + s.id, onTypingEvent)
    await imApi.readAll(s.id)
    unreadMap.value = { ...unreadMap.value, [s.id]: 0 }
  } catch {}
}

function send() {
  const text = draft.value.trim()
  if (!text || !current.value) return
  if (current.value.status === 'CLOSED') return ElMessage.warning('会话已关闭')
  const ok = stomp.send(`/app/send/${current.value.id}`, {
    sessionId: current.value.id, msgType: 'TEXT', content: text
  })
  if (ok) { draft.value = ''; sendTyping(false) }
}

function onImagePick(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) return ElMessage.warning('图片不能超过 5MB')
  const reader = new FileReader()
  reader.onload = () => {
    stomp?.send(`/app/send/${current.value.id}`, {
      sessionId: current.value.id, msgType: 'IMAGE', content: reader.result
    })
  }
  reader.readAsDataURL(file)
  e.target.value = ''
}

function onTyping() {
  if (!current.value) return
  sendTyping(true)
  clearTimeout(typingTimer)
  typingTimer = setTimeout(() => sendTyping(false), 1500)
}
function sendTyping(typing) {
  if (!stomp || !current.value) return
  stomp.send(`/app/typing/${current.value.id}`, { typing })
}

function appendMessage(m) {
  if (current.value && m.sessionId !== current.value.id) return
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

function statusText(s) { return ({ WAITING: '等待', ACTIVE: '进行中', CLOSED: '已结束' })[s] || s }
function statusTag(s) { return ({ WAITING: 'warning', ACTIVE: 'success', CLOSED: 'info' })[s] || '' }

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

async function openTransfer() {
  showTransfer.value = true
  try {
    otherAgents.value = await imApi.listAgents()
  } catch {}
}
function doTransfer() {
  if (!current.value || !transferTo.value) return
  imApi.transferSession(current.value.id, transferTo.value, transferReason.value)
    .then(() => {
      ElMessage.success('已转接')
      showTransfer.value = false; transferTo.value = null; transferReason.value = ''
      refreshSessions()
    })
    .catch(e => ElMessage.error(e.message || '转接失败'))
}

async function openCanned() {
  showCanned.value = true
  try {
    cannedList.value = await imApi.listCanned(current.value?.skillTag || null)
  } catch {}
}
function useCanned(row) {
  draft.value = row.content
  showCanned.value = false
  ElMessage.success('已填入, 可继续编辑')
}

function logout() {
  stomp?.disconnect()
  userStore.logout()
  router.replace('/login')
}
</script>

<style scoped>
.chat-page { height: 100vh; height: 100dvh; display: flex; flex-direction: column; background: #f5f7fa; }
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 12px;
  background: #fff; border-bottom: 1px solid #ebeef5;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  min-height: 52px;
  padding-top: var(--safe-top);
  padding-top: var(--safe-top-legacy);
  height: calc(52px + var(--safe-top) + var(--safe-top-legacy));
}
.brand-area { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; }
.brand { font-weight: bold; font-size: 16px; }
.session-badge { margin-left: 4px; }
.menu-btn { padding: 8px; }
.user-area { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.waiting-badge :deep(.el-badge__content) { background: #f56c6c; }

.chat-main { flex: 1; display: flex; min-height: 0; overflow: hidden; }

.side {
  width: 280px; background: #fff; border-right: 1px solid #ebeef5;
  display: flex; flex-direction: column;
}
.side-header {
  padding: 12px 16px; border-bottom: 1px solid #ebeef5;
  display: flex; justify-content: space-between; align-items: center; font-weight: 500;
}
.session-list { flex: 1; overflow-y: auto; padding: 8px; }
.session-list-mobile { padding: 16px; }
.session-item {
  padding: 10px 12px; border-radius: 6px; cursor: pointer;
  background: #f5f7fa; margin-bottom: 6px; transition: background 0.2s;
  min-height: 44px;
}
.session-item:hover { background: #ecf5ff; }
.session-item.active { background: #409eff; color: #fff; }
.session-item.active .row2 { color: rgba(255, 255, 255, 0.85); }
.row1 { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.sno { font-family: 'Courier New', monospace; }
.row2 { font-size: 12px; color: #909399; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.chat-area { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #f5f7fa; }
.empty { height: 100%; display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 16px; }

.chat-header {
  min-height: 48px;
  background: #fff; border-bottom: 1px solid #ebeef5;
  padding: 8px 12px; display: flex; align-items: center; justify-content: space-between;
  gap: 8px;
}
.online-dot {
  display: inline-block;
  width: 8px; height: 8px;
  border-radius: 50%;
  background: #c0c4cc;
  margin-right: 4px;
  flex-shrink: 0;
  transition: background 0.3s;
}
.online-dot.on { background: #67c23a; box-shadow: 0 0 4px #67c23a; }
.online-dot.off { background: #909399; }
.session-item .row1 { display: flex; align-items: center; gap: 6px; }
.session-item .row1 .sno { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.header-left { display: flex; align-items: center; gap: 6px; min-width: 0; flex: 1; overflow: hidden; }
.header-right { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.chat-header .sno { font-family: 'Courier New', monospace; font-weight: 500; font-size: 14px; }
.chat-header .cust { color: #909399; font-size: 12px; margin-left: 6px; }

.message-list { flex: 1; overflow-y: auto; padding: 14px 18px; }
.msg-system { text-align: center; color: #909399; font-size: 12px; margin: 8px 0; }
.msg-row { display: flex; margin-bottom: 12px; }
.msg-row.mine { justify-content: flex-end; }
.bubble {
  max-width: min(60%, 480px);
  background: #fff; padding: 10px 14px; border-radius: 8px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
  word-break: break-word;
}
.msg-row.mine .bubble { background: #409eff; color: #fff; }
.msg-row.mine .meta { color: rgba(255, 255, 255, 0.7); }
.meta { font-size: 11px; color: #909399; margin-bottom: 4px; }
.read-tick { color: #5cd600; font-weight: bold; margin-left: 4px; }
.msg-row.mine .read-tick { color: #c8f5a0; }
.text { font-size: 14px; line-height: 1.5; white-space: pre-wrap; }
.msg-image { max-width: 240px; max-height: 240px; border-radius: 4px; display: block; cursor: pointer; }

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
  display: flex; gap: 6px; align-items: flex-end;
  background: #fff; padding: 8px 10px;
  border-top: 1px solid #ebeef5;
  padding-bottom: calc(8px + var(--safe-bottom) + var(--safe-bottom-legacy));
}
.composer .el-textarea { flex: 1; min-width: 0; }
.composer .icon-btn, .composer .send-btn { flex-shrink: 0; }

.mobile-tools {
  display: flex; justify-content: center; gap: 12px;
  padding: 6px;
  background: #f5f7fa;
  border-top: 1px solid #ebeef5;
}

/* ============ 移动端 (≤ 768px) ============ */
@media (max-width: 768px) {
  .brand { font-size: 15px; }
  .topbar { padding: 0 8px 0 4px; min-height: 48px; }
  .user-area { gap: 6px; }
  .user-area .el-select { width: 80px !important; }
  .user-area .el-badge :deep(.el-button) { padding: 5px 8px; font-size: 12px; }

  .message-list { padding: 10px 10px; }
  .bubble { max-width: 82%; padding: 8px 12px; }
  .text { font-size: 15px; }
  .msg-image { max-width: 200px; max-height: 200px; }

  .chat-header { padding: 6px 10px; }
  .chat-header .el-button { padding: 4px 8px; font-size: 12px; }

  .composer { padding: 6px 8px; gap: 4px; }
}

@media (max-width: 380px) {
  .bubble { max-width: 88%; }
  .text { font-size: 14px; }
  .chat-header .header-right .el-button { padding: 4px 6px; }
}
</style>