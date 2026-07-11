<script setup>
/**
 * Agent.vue - 坐席接待主入口 (H5 移动端优先).
 * ----------------------------------------------------------------------------
 * 模块职责:
 *   - 装配 MessageList + MessageBubble + ChatComposer 子组件
 *   - STOMP 连接/订阅 + 业务事件分发 (新消息/已读/转接/关闭/录存/动态)
 *   - 进线客户列表 (waiting panel) + 手动接起 (防串线 CAS, 传 sessionId)
 *   - 会话转接 (转给其他坐席) + 模板回复 (快捷文本)
 *   - 消息发送 + 已读标记 + 撤回
 *   - 桌面通知 (useNotification 进线客户推送)
 *
 * v5 重构: 从 963 行压缩到 614 行 (-36%).
 *   - 录音/表情/响应式 → composables/ (同 Customer.vue)
 *   - 消息渲染 → components/chat/MessageList + MessageBubble
 *
 * 关键状态:
 *   - currentSession / messages: 当前会话 + 消息列表
 *   - waitingSessions: 进线客户列表 (接起前可见)
 *   - claimedSession: 已接起会话 (与 waiting 互斥)
 *   - isMobile / drawerVisible: 响应式 (useResponsive)
 *   - notifier: 桌面通知 hook (useNotification)
 *
 * 事件订阅 (STOMP):
 *   - /user/queue/messages: 新消息推送
 *   - /user/queue/events: 业务事件 (NEW_WAITING/READ/TRANSFERRED/CLOSED/PRESENCE)
 *   - /topic/sessions/new: 进线客户广播 → 加进 waitingSessions
 *   - /topic/typing/{sid}: 客户输入状态
 *
 * 防串线:
 *   - claimOne(sessionId) 传具体 ID → 走后端 CAS 防止多人同时接同一会话
 *   - 409 提示 "已被 #X 接起"
 */
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { Menu, Goblet, ChatLineRound, Bell, User, DataAnalysis, VideoCamera} from '@element-plus/icons-vue'
import { imApi } from '@/api/im'
import { useUserStore } from '@/stores/user'
import { StompClient } from '@/utils/ws-client'
import MessageList from '@/components/chat/MessageList.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import SmartReplySuggestions from '@/components/chat/SmartReplySuggestions.vue'
import AgentDashboard from '@/components/dashboard/AgentDashboard.vue'
import VideoCallDialog from '@/components/video/VideoCallDialog.vue'
import { useResponsive } from '@/composables/useResponsive'
import { useNotification } from '@/composables/useNotification'

const router = useRouter()
const userStore = useUserStore()
const { isMobile, drawerVisible, previewImageUrl } = useResponsive()
const showDashboard = ref(false)
const showVideo = ref(false)
function highlight(text, key) {
  if (!text || !key) return text || ''
  const esc = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return text.replace(new RegExp(esc, 'gi'), m => '<mark>' + m + '</mark>')
}

const showSearch = ref(false)
const searchKey = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
// v6: 桌面通知走 permission-sdk 统一管理
const { notify: desktopNotify, requestPermission: requestNotifPerm, status: notifStatus } = useNotification({ defaultCooldownMs: 30_000 })

// ============ 会话/状态 ============
const sessions = ref([])
const current = ref(null)
const messages = ref([])
const draft = ref('')

/** 最后一条客户消息 (供 SmartReplySuggestions 使用) */
const lastCustomerText = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.senderRole === 'CUSTOMER' && m.msgType === 'TEXT' && !m.recalled) {
      return m.content || ''
    }
  }
  return ''
})

/** 点选智能推荐 -> 一键填入输入框 */
function onSmartPick(text) {
  draft.value = text
}
const connected = ref(false)
const reconnecting = ref(false)
const waitingCount = ref(0)
const waitingList = ref([])
const claimingId = ref(null)
const unreadMap = ref({})

/** 总未读数 (用于欢迎页统计) */
const unreadTotal = computed(() =>
  Object.values(unreadMap.value || {}).reduce((s, n) => s + (n || 0), 0)
)
const readMap = ref({})
const recalledMap = ref({})
const peerTyping = ref('')
const agentStatus = ref('ONLINE')
const messageListRef = ref(null)

const activeSessions = computed(() => sessions.value.filter((s) => s.status !== 'CLOSED'))

function appendMessage(m) {
  if (current.value && m.sessionId !== current.value.id) return
  if (!m.id) m.id = `tmp-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  messages.value.push(m)
  if (m.recalled) recalledMap.value[m.id] = true
  nextTick(scrollToBottom)
}

function scrollToBottom() {
  const el = messageListRef.value
  if (!el) return
  const scroller = el.$el || el
  if (scroller.scrollTo) scroller.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' })
  else scroller.scrollTop = scroller.scrollHeight
}

// ============ STOMP 消息分发 ============
let stomp = null
let subscribedSessionId = null
let typingTimer = null


async function onSearch() {
  if (!searchKey.value.trim() || !current.value) return
  searchLoading.value = true
  try {
    const r = await imApi.searchMessages(current.value.id, searchKey.value, 50)
    if (r.code === 200) searchResults.value = r.data || []
  } catch (e) { console.error(e) }
  finally { searchLoading.value = false }
}

function onStompMessage(payload) {
  if (!payload) return
  // 1) 业务事件
  if (payload.type) {
    if (payload.type === 'PRESENCE') {
      const target = sessions.value.find((s) => s.id === payload.sessionId)
      if (target) target.peerOnline = payload.online
      if (current.value?.id === payload.sessionId) current.value = { ...current.value, peerOnline: payload.online }
      return
    }
    if (payload.type === 'NEW_WAITING') {
      refreshWaiting()
      if (payload.sessionId) {
        // 页面内通知 (始终推送)
        ElNotification.info({
          title: '新客户进线',
          message: `会话 #${payload.sessionId} 等待接单${payload.skillTag ? ' (' + payload.skillTag + ')' : ''}`,
          duration: 4000,
        })
        // 桌面系统通知 (按 tag 去重, 30s 冷却)
        desktopNotify({
          title: '新客户进线',
          body: `会话 #${payload.sessionId} 等待接单${payload.skillTag ? ' (' + payload.skillTag + ')' : ''}`,
          tag: 'new-waiting-' + payload.sessionId,
          onClick: () => {
            // 点击通知后自动接单
            const s = waitingList.value.find((x) => x.id === payload.sessionId)
            if (s) claimOne(s.id)
          },
        })
      }
      return
    }
    if (payload.type === 'CLOSED') {
      const target = sessions.value.find((s) => s.id === payload.sessionId)
      if (target) target.status = 'CLOSED'
      if (current.value?.id === payload.sessionId) {
        current.value = { ...current.value, status: 'CLOSED' }
        ElMessage.info('客户已结束会话')
      }
      return
    }
    if (payload.type === 'READ' && payload.messageId) {
      readMap.value = { ...readMap.value, [payload.messageId]: true }
      return
    }
    if (payload.type === 'RECALL' && payload.messageId) {
      recalledMap.value = { ...recalledMap.value, [payload.messageId]: true }
      const m = messages.value.find((x) => x.id === payload.messageId)
      if (m) { m.recalled = true; m.msgType = 'RECALL'; m.content = '对方撤回了一条消息' }
      return
    }
    return
  }
  // 2) 普通消息 (推送给当前会话才追加)
  if (current.value && payload.sessionId === current.value.id) {
    appendMessage(payload)
  }
}

function onTypingEvent(payload) {
  if (!payload) return
  if (current.value && payload.userId === current.value.customerId) {
    peerTyping.value = payload.typing ? '客户' : ''
  }
}

function subscribeCurrentSession() {
  if (!stomp) return
  if (subscribedSessionId) {
    stomp.unsubscribe(`/user/queue/typing/${subscribedSessionId}`)
    subscribedSessionId = null
  }
  if (!current.value) return
  subscribedSessionId = current.value.id
  stomp.subscribe(`/user/queue/typing/${subscribedSessionId}`, onTypingEvent)
}

// ============ 拉取会话/等待 ============
async function refreshSessions() {
  try {
    const list = await imApi.mySessions()
    sessions.value = list
    // 拉未读数
    for (const s of list) {
      try { unreadMap.value[s.id] = await imApi.unread(s.id) } catch {}
    }
  } catch {}
}

async function refreshWaiting() {
  try {
    const list = await imApi.waitingList()
    waitingList.value = list
    waitingCount.value = list.length
  } catch {}
}

async function select(s) {
  if (current.value && current.value.id !== s.id) {
    stomp?.unsubscribe(`/user/queue/typing/${current.value.id}`, onTypingEvent)
  }
  current.value = s
  drawerVisible.value = false
  messages.value = []
  recalledMap.value = {}
  readMap.value = {}
  peerTyping.value = ''
  await loadHistory(s.id)
  subscribeCurrentSession()
  if (s.status === 'ACTIVE' && s.unreadCount > 0) {
    imApi.readAll(s.id).catch(() => {})
  }
}

async function loadHistory(sid) {
  try {
    const list = await imApi.history(sid, 100)
    messages.value = list.map((m) => ({
      ...m,
      id: m.id || `tmp-h-${Math.random().toString(36).slice(2, 8)}`,
    }))
    nextTick(scrollToBottom)
  } catch {}
}

// ============ 接单 (防串线 CAS) ============
async function claimOne(sessionId = null) {
  // v4 fix: 防止 Vue 3 @click 不传参时把 PointerEvent 当 sessionId
  if (sessionId !== null && typeof sessionId !== 'number' && typeof sessionId !== 'string') {
    sessionId = null
  }
  claimingId.value = typeof sessionId === 'number' ? sessionId : null
  try {
    const s = sessionId != null
      ? await imApi.claimSession(sessionId)
      : await imApi.claimSession()
    ElMessage.success(`已接入 ${s.sessionNo}`)
    await refreshWaiting()
    await refreshSessions()
    select(s)
  } catch (e) {
    if (e.code === 409) ElMessage.warning(e.message || '会话已被其他坐席接起, 请选择其他会话')
    else if (e.message?.includes('暂无')) ElMessage.warning(e.message)
    else ElMessage.error('接单失败: ' + (e?.message || '未知错误'))
    await refreshWaiting()
  } finally {
    claimingId.value = null
  }
}

// ============ 发送消息 ============
async function send() {
  if (!current.value || !draft.value.trim()) return
  const text = draft.value.trim()
  draft.value = ''
  if (stomp && stomp.connected) {
    stomp.send(`/app/send/${current.value.id}`, { msgType: 'TEXT', content: text })
  } else {
    try {
      await fetch(`/api/im/session/${current.value.id}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${userStore.token}` },
        body: JSON.stringify({ msgType: 'TEXT', content: text }),
      })
    } catch (e) { console.warn('rest send failed', e) }
  }
}

function sendTyping(typing) {
  if (!stomp || !current.value) return
  stomp.send(`/app/typing/${current.value.id}`, { typing })
}

function onTyping() {
  if (!current.value) return
  sendTyping(true)
  clearTimeout(typingTimer)
  typingTimer = setTimeout(() => sendTyping(false), 1500)
}

// ============ 撤回/已读 ============
async function recall(messageId) {
  try {
    await imApi.recallMessage(messageId)
    recalledMap.value = { ...recalledMap.value, [messageId]: true }
    const m = messages.value.find((x) => x.id === messageId)
    if (m) { m.recalled = true; m.msgType = 'RECALL'; m.content = '对方撤回了一条消息' }
  } catch (e) {
    ElMessage.error(e.message || '撤回失败')
  }
}

function canRecall(msg) {
  if (!msg.id || !msg.createdAt) return false
  return Date.now() - new Date(msg.createdAt).getTime() < 2 * 60 * 1000
}

// ============ 文件/图片/语音 ============
function onImagePick(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file || !current.value) return
  const sid = current.value.id
  if (file.type.startsWith('image/')) {
    if (file.size > 5 * 1024 * 1024) return ElMessage.warning('图片不能超过 5MB')
    const reader = new FileReader()
    reader.onload = () => {
      stomp?.send(`/app/send/${sid}`, { sessionId: sid, msgType: 'IMAGE', content: reader.result })
    }
    reader.readAsDataURL(file)
    return
  }
  if (file.size > 50 * 1024 * 1024) return ElMessage.warning('文件不能超过 50MB')
  const loading = ElMessage({ message: '上传中...', duration: 0, type: 'info' })
  ;(async () => {
    try {
      const r = await imApi.uploadFile(sid, file)
      if (r.code !== 0) return ElMessage.error('上传失败: ' + r.message)
      const url = r.data.fileUrl
      stomp?.send(`/app/send/${sid}`, {
        msgType: 'FILE',
        content: url,
        fileName: file.name,
      })
      ElMessage.success('已发送')
    } catch (err) {
      ElMessage.error('上传失败: ' + (err?.message || ''))
    } finally {
      loading.close()
    }
  })()
}

async function onVoiceBlob({ blob, seconds, mimeType }) {
  if (!current.value) return
  try {
    const file = new File([blob], `voice-${Date.now()}.webm`, { type: blob.type || mimeType })
    const res = await imApi.uploadFile(current.value.id, file)
    const data = res.data || res
    const url = data?.url || data?.rawUrl || ''
    stomp?.send(`/app/send/${current.value.id}`, {
      msgType: 'VOICE',
      content: JSON.stringify({ url, seconds, mimeType }),
    })
    ElMessage.success(`语音 ${seconds}s 已发送`)
  } catch (err) {
    ElMessage.error('上传失败: ' + (err?.message || '未知错误'))
  }
}

// ============ 转接 ============
const showTransfer = ref(false)
const transferTo = ref(null)
const transferReason = ref('')
const otherAgents = ref([])

async function openTransfer() {
  showTransfer.value = true
  try { otherAgents.value = await imApi.listAgents() } catch {}
}

async function doTransfer() {
  if (!current.value || !transferTo.value) return
  try {
    await imApi.transferSession(current.value.id, transferTo.value, transferReason.value || null)
    ElMessage.success('已转接')
    showTransfer.value = false
    transferTo.value = null
    transferReason.value = ''
    current.value = null
    await refreshSessions()
  } catch (e) {
    ElMessage.error(e.message || '转接失败')
  }
}

// ============ 模板回复 ============
const showCanned = ref(false)
const cannedList = ref([])
const cannedFilter = ref('')

async function openCanned() {
  showCanned.value = true
  try {
    cannedList.value = await imApi.listCanned(current.value?.skillTag || null)
  } catch {}
}

function pickCanned(c) {
  draft.value = c.content
  showCanned.value = false
  ElMessage.success('已填入模板 (记得点击发送)')
}

// ============ 关闭会话 ============
async function closeSession() {
  if (!current.value) return
  try {
    await ElMessageBox.confirm('确认关闭当前会话? 关闭后客户将看到评分弹窗。', '关闭会话', { type: 'warning' })
    await imApi.closeSession(current.value.id)
    ElMessage.success('已关闭')
    current.value = null
    await refreshSessions()
  } catch (e) {
    if (e === 'cancel' || e?.message === 'cancel') return
    ElMessage.error(e.message || '关闭失败')
  }
}

// ============ 坐席状态 ============
async function onStatusChange(v) {
  try { await imApi.setAgentStatus(v); agentStatus.value = v; ElMessage.success('状态已切换') }
  catch (e) { ElMessage.error(e.message || '切换失败') }
}

// ============ 导航 ============
function goReplay() {
  if (!current.value) return
  router.push(`/replay?sessionId=${current.value.id}`)
}

function logout() {
  stomp?.disconnect()
  userStore.logout()
  router.push('/login')
}

// ============ 弹窗事件包装 ============
function onPreviewImage(url) { previewImageUrl.value = url }
function onRecall(id) { recall(id) }

// ============ 生命周期 ============
let waitingTimer = null
onMounted(async () => {
  stomp = new StompClient({
    token: userStore.token,
    userId: userStore.id,
    role: userStore.role,
    onConnect: () => { connected.value = true; refreshWaiting(); refreshSessions() },
    onDisconnect: () => { connected.value = false },
    onReconnected: () => { refreshSessions(); if (current.value) loadHistory(current.value.id) },
    onMessage: onStompMessage,
  })
  stomp.connect()
  await refreshSessions()
  await refreshWaiting()
  // 坐席状态 (默认 ONLINE)
  try { agentStatus.value = await imApi.getAgentStatus() } catch {}
  // 桌面通知权限 (仅在已授权过的场景跳过弹窗, 默认 prompt 时跳过让用户手动开)
  if (notifStatus.value === 'prompt') {
    requestNotifPerm().then((ok) => {
      if (!ok) console.log('[notification] 用户未授权桌面通知')
    }).catch(() => {})
  }
  // 定时刷新 waiting 列表 (10s 一次, 防止服务端推送丢失)
  waitingTimer = setInterval(refreshWaiting, 10000)
})

onBeforeUnmount(() => {
  if (waitingTimer) clearInterval(waitingTimer)
  if (typingTimer) clearTimeout(typingTimer)
  stomp?.disconnect()
})
</script>

<template>
  <div class="agent-shell" :class="{ mobile: isMobile }">
    <!-- 顶栏 -->
    <header class="topbar no-record">
      <el-button v-if="isMobile" link class="menu-btn" @click="drawerVisible = true">
        <el-icon><Menu /></el-icon>
      </el-button>
      <span class="title">
        <span class="title-icon">🎧</span> 坐席工作台
        <el-tag v-if="agentStatus === 'ONLINE'" type="success" size="small" class="status-tag">
          <span class="status-dot"></span>在线
        </el-tag>
        <el-tag v-else-if="agentStatus === 'BUSY'" type="warning" size="small" class="status-tag">忙碌</el-tag>
        <el-tag v-else-if="agentStatus === 'AWAY'" type="info" size="small" class="status-tag">离开</el-tag>
        <el-tag v-else type="danger" size="small" class="status-tag">离线</el-tag>
      </span>
      <span class="conn-status" :class="{ ok: connected, warn: !connected && !reconnecting, bad: reconnecting }">
        <span class="status-dot"></span>
        {{ connected ? '已连接' : (reconnecting ? '重连中…' : '未连接') }}
      </span>
      <div class="spacer" />
      <el-button size="small" round class="claim-btn" :disabled="!connected" @click="claimOne()">
        <el-icon><Goblet /></el-icon>&nbsp;抢单 ({{ waitingList.length }})
      </el-button>
      <el-select :model-value="agentStatus" size="small" style="width: 90px;" @change="onStatusChange">
        <el-option label="在线" value="ONLINE" />
        <el-option label="离开" value="AWAY" />
        <el-option label="忙碌" value="BUSY" />
        <el-option label="离线" value="OFFLINE" />
      </el-select>
      <el-button size="small" round plain class="search-btn" @click="showSearch = true" v-if="current">
        <el-icon><Search /></el-icon>&nbsp;搜消息
      </el-button>
      <el-button size="small" round plain @click="$router.push('/monitor')" class="mon-btn">
        <el-icon><Monitor /></el-icon>&nbsp;监控
      </el-button>
      <el-button size="small" round class="dash-btn" @click="showDashboard = true">
        <el-icon><DataAnalysis /></el-icon>&nbsp;看板
      </el-button>
      <el-button size="small" link @click="logout">退出</el-button>
    </header>

    <!-- 坐席数据看板 -->
    <el-dialog v-model="showDashboard" title="数据看板" width="900px" top="5vh">
      <AgentDashboard />
    </el-dialog>

    <!-- 视频通话 (V3 渠道 4) -->
    <VideoCallDialog v-if="showVideo" v-model="showVideo" :session="current" :stomp="stomp" />

    <!-- 消息搜索 -->
    <el-dialog v-model="showSearch" title="消息搜索" width="600px" top="8vh">
      <div class="search-bar">
        <el-input v-model="searchKey" placeholder="输入关键词" clearable autofocus
                  @keyup.enter="onSearch">
          <template #append>
            <el-button :icon="Search" :loading="searchLoading" @click="onSearch">搜索</el-button>
          </template>
        </el-input>
      </div>
      <div v-loading="searchLoading" class="search-results">
        <div v-if="searchResults.length === 0 && !searchLoading" class="search-empty">
          {{ searchKey ? '没有匹配的消息' : '输入关键词搜索当前会话消息' }}
        </div>
        <div v-for="m in searchResults" :key="m.id" class="search-item">
          <div class="search-item-header">
            <el-tag size="small" :type="m.fromRole === 'CUSTOMER' ? 'primary' : 'success'">
              {{ m.fromRole === 'CUSTOMER' ? '客户' : '我' }}
            </el-tag>
            <span class="search-time">{{ new Date(m.createdAt).toLocaleString('zh-CN') }}</span>
          </div>
          <div class="search-content" v-html="highlight(m.content, searchKey)" />
        </div>
      </div>
    </el-dialog>

    <main>
      <!-- 桌面侧栏: 会话列表 -->
      <aside v-if="!isMobile" class="sidebar">
        <div class="sidebar-header">
          <span>会话列表 ({{ activeSessions.length }})</span>
          <el-button link size="small" @click="refreshSessions">刷新</el-button>
        </div>
        <div class="session-list">
          <div v-for="s in activeSessions" :key="s.id"
               class="session-item"
               :class="{ active: current?.id === s.id }"
               @click="select(s)">
            <div class="si-line1">
              <span class="online-dot" :class="{ on: s.peerOnline === true, off: s.peerOnline === false }"></span>
              <span class="sno">{{ s.sessionNo }}</span>
              <el-tag size="small" v-if="s.skillTag">{{ s.skillTag }}</el-tag>
              <span class="badge" v-if="unreadMap[s.id] > 0">{{ unreadMap[s.id] }}</span>
            </div>
            <div class="si-line2">{{ s.lastMessage || '...' }}</div>
          </div>
          <el-empty v-if="!activeSessions.length" description="暂无会话" :image-size="60" />
        </div>
      </aside>

      <!-- 聊天区 -->
      <section class="chat-area">
        <div v-if="!current" class="agent-welcome">
          <div class="welcome-bg">
            <div class="welcome-blob welcome-blob-1"></div>
            <div class="welcome-blob welcome-blob-2"></div>
            <div class="welcome-blob welcome-blob-3"></div>
          </div>

          <!-- 无客户等候: 大欢迎页 + 统计 -->
          <div v-if="waitingList.length === 0" class="welcome-card">
            <div class="welcome-emoji">🎧</div>
            <h2 class="welcome-title">欢迎回来, {{ userStore.nickname || '坐席' }}!</h2>
            <p class="welcome-subtitle">当前无等候客户. 休息一下, 客户接入时会自动提示</p>
            <div class="welcome-stats">
              <div class="stat-item">
                <div class="stat-value">{{ activeSessions.length }}</div>
                <div class="stat-label">进行中</div>
              </div>
              <div class="stat-divider"></div>
              <div class="stat-item">
                <div class="stat-value">{{ waitingList.length }}</div>
                <div class="stat-label">等候中</div>
              </div>
              <div class="stat-divider"></div>
              <div class="stat-item">
                <div class="stat-value" :class="{ pulse: unreadTotal > 0 }">{{ unreadTotal }}</div>
                <div class="stat-label">未读消息</div>
              </div>
            </div>
            <div class="welcome-tip">
              <el-icon><Bell /></el-icon>
              <span>系统将自动监听新进线, 你也可以点击右上角"抢单"主动接入</span>
            </div>
          </div>

          <!-- 有客户等候: 列表 + 抢单 CTA -->
          <div v-else class="waiting-panel">
            <div class="waiting-header">
              <h4><el-icon><ChatLineRound /></el-icon> 等候客户 ({{ waitingList.length }})</h4>
              <el-tag type="warning" effect="dark" size="small">需尽快接入</el-tag>
            </div>
            <div class="waiting-list">
              <div v-for="s in waitingList" :key="s.id" class="waiting-item">
                <div class="wi-info">
                  <span class="wi-avatar">{{ (s.skillTag || '通').charAt(0) }}</span>
                  <div class="wi-meta">
                    <div class="wi-line1">
                      <span class="sno">{{ s.sessionNo }}</span>
                      <el-tag size="small" v-if="s.skillTag">{{ s.skillTag }}</el-tag>
                    </div>
                    <div class="wi-line2">客户 #{{ s.customerId }} · 等待接入</div>
                  </div>
                </div>
                <el-button size="small" type="primary" round :loading="claimingId === s.id" @click="claimOne(s.id)">
                  接起
                </el-button>
              </div>
            </div>
            <p class="hint">
              <el-icon><User /></el-icon>
              点击「接起」手动接入客户 (系统保证唯一坐席成功)
            </p>
          </div>
        </div>
        <template v-else>
          <!-- 头部 -->
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
              <el-button size="small" @click="goReplay" :disabled="!current">回溯</el-button>
              <el-button v-if="!isMobile" size="small" type="danger" plain @click="closeSession">关闭</el-button>
              <el-button v-else size="small" type="danger" plain @click="closeSession">×</el-button>
            </div>
          </div>

          <!-- 智能回复推荐 -->
          <SmartReplySuggestions :last-user-text="lastCustomerText" @pick="onSmartPick" />

          <!-- 消息列表 + 输入框 -->
          <MessageList :messages="messages" :session-id="current?.id" :peer-typing="peerTyping">
            <template #bubble="{ item }">
              <MessageBubble
                :item="item"
                :current-user-id="userStore.id"
                :read-map="readMap"
                :recalled-map="recalledMap"
                :show-file="true"
                @preview-image="onPreviewImage"
                @recall="onRecall" />
            </template>
          </MessageList>

          <ChatComposer
            v-model="draft"
            :disabled="current.status === 'CLOSED'"
            image-accept="image/*,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.zip,.txt,.csv"
            placeholder="回复客户…"
            @send="send"
            @typing="onTyping"
            @image-pick="onImagePick"
            @voice-blob="onVoiceBlob">
            <template #toolbar-extra>
              <el-button size="small" @click="openCanned" plain>📋 模板</el-button>
              <el-button size="small" type="primary" plain @click="showVideo = true">
                <el-icon><VideoCamera /></el-icon>&nbsp;视频
              </el-button>
            </template>
          </ChatComposer>
        </template>
      </section>
    </main>

    <!-- 移动端抽屉: 会话列表 -->
    <el-drawer v-if="isMobile" v-model="drawerVisible" title="会话列表" direction="rtl" size="80%">
      <div class="session-list">
        <div v-for="s in activeSessions" :key="s.id" class="session-item" :class="{ active: current?.id === s.id }" @click="select(s)">
          <div class="si-line1">
            <span class="online-dot" :class="{ on: s.peerOnline === true, off: s.peerOnline === false }"></span>
            <span class="sno">{{ s.sessionNo }}</span>
            <el-tag size="small" v-if="s.skillTag">{{ s.skillTag }}</el-tag>
          </div>
          <div class="si-line2">{{ s.lastMessage || '...' }}</div>
        </div>
        <el-empty v-if="!activeSessions.length" description="暂无会话" :image-size="60" />
      </div>
    </el-drawer>

    <!-- 转接弹窗 -->
    <el-dialog v-model="showTransfer" title="转接会话" width="420px">
      <el-select v-model="transferTo" placeholder="选择目标坐席" style="width: 100%">
        <el-option v-for="a in otherAgents.filter((a) => a.id !== userStore.id)" :key="a.id"
                   :label="`${a.nickname} (${a.username})`" :value="a.id" />
      </el-select>
      <el-input v-model="transferReason" type="textarea" :rows="2" placeholder="转接原因 (可选)" style="margin-top: 12px;" />
      <template #footer>
        <el-button size="large" @click="showTransfer = false">取消</el-button>
        <el-button size="large" type="primary" :disabled="!transferTo" @click="doTransfer">确认转接</el-button>
      </template>
    </el-dialog>

    <!-- 模板回复弹窗 -->
    <el-dialog v-model="showCanned" title="快捷回复模板" width="560px">
      <el-input v-model="cannedFilter" placeholder="搜索模板..." clearable style="margin-bottom: 12px;" />
      <div class="canned-list">
        <div v-for="c in cannedList.filter((c) => !cannedFilter || c.title.includes(cannedFilter) || c.content.includes(cannedFilter))" :key="c.id" class="canned-item" @click="pickCanned(c)">
          <div class="canned-title">{{ c.title }}</div>
          <div class="canned-content">{{ c.content }}</div>
        </div>
        <el-empty v-if="!cannedList.length" description="该技能暂无模板" :image-size="60" />
      </div>
    </el-dialog>

    <!-- 图片预览 -->
    <el-image-viewer v-if="previewImageUrl" :url-list="[previewImageUrl]" :initial-index="0" @close="previewImageUrl = null" />
  </div>
</template>

<style scoped>
.agent-shell { display: flex; flex-direction: column; height: 100vh; background: #f7f8fa; }
.agent-shell.mobile { height: 100dvh; }
.topbar { display: flex; align-items: center; gap: 8px; padding: 0 16px; height: 56px; background: #fff; border-bottom: 1px solid #ebeef5; }
.topbar .title { font-weight: 600; }
.topbar .status { font-size: 12px; padding: 2px 8px; border-radius: 4px; background: #f4f4f5; color: #909399; }
.topbar .status.ok { background: #f0f9eb; color: #67c23a; }
.topbar .status.warn { background: #fdf6ec; color: #e6a23c; }
.topbar .status.bad { background: #fef0f0; color: #f56c6c; }
.spacer { flex: 1; }
main { flex: 1; display: flex; min-height: 0; }
.sidebar { width: 280px; background: #fff; border-right: 1px solid #ebeef5; display: flex; flex-direction: column; }
.sidebar-header { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid #ebeef5; font-weight: 600; }
.session-list { flex: 1; overflow-y: auto; }
.session-item { padding: 12px 16px; border-bottom: 1px solid #f4f4f5; cursor: pointer; transition: background 0.15s; }
.session-item:hover { background: #f7f8fa; }
.session-item.active { background: #ecf5ff; border-left: 3px solid #409eff; }
.si-line1 { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.si-line2 { font-size: 12px; color: #909399; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sno { font-weight: 500; }
.online-dot { width: 8px; height: 8px; border-radius: 50%; background: #c0c4cc; }
.online-dot.on { background: #67c23a; }
.online-dot.off { background: #909399; }
.badge { background: #f56c6c; color: #fff; font-size: 11px; padding: 0 6px; border-radius: 8px; margin-left: auto; }
.chat-area { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #fff; }
.empty { flex: 1; display: flex; align-items: center; justify-content: center; }

/* ===== V3 坐席欢迎页 ===== */
.agent-welcome {
  flex: 1;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  overflow: hidden;
  background: #fafbfc;
}
.welcome-bg {
  position: absolute; inset: 0;
  overflow: hidden;
  pointer-events: none;
}
.welcome-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.35;
  animation: blob-float 14s ease-in-out infinite;
}
.welcome-blob-1 {
  width: 320px; height: 320px;
  background: linear-gradient(135deg, #409EFF, #909399);
  top: -120px; left: -80px;
}
.welcome-blob-2 {
  width: 280px; height: 280px;
  background: linear-gradient(135deg, #67C23A, #E6A23C);
  bottom: -100px; right: -80px;
  animation-delay: -5s;
}
.welcome-blob-3 {
  width: 200px; height: 200px;
  background: linear-gradient(135deg, #F56C6C, #E6A23C);
  top: 50%; left: 50%;
  animation-delay: -9s;
}
@keyframes blob-float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(20px, -20px) scale(1.1); }
}
.welcome-card {
  position: relative;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 24px;
  padding: 40px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.08);
  text-align: center;
  max-width: 540px;
  width: 100%;
  animation: welcome-in 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes welcome-in {
  from { opacity: 0; transform: translateY(20px) scale(0.95); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.welcome-emoji {
  font-size: 56px;
  margin-bottom: 12px;
  animation: emoji-bounce 1.5s ease-in-out infinite;
  display: inline-block;
}
@keyframes emoji-bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}
.welcome-title {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 600;
  background: linear-gradient(135deg, #303133, #409EFF);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.welcome-subtitle {
  margin: 0 0 24px;
  color: #909399;
  font-size: 14px;
}
.welcome-stats {
  display: flex; align-items: center;
  justify-content: space-around;
  padding: 20px 0;
  margin: 20px 0;
  border-top: 1px solid #ebeef5;
  border-bottom: 1px solid #ebeef5;
}
.stat-item { flex: 1; text-align: center; }
.stat-value {
  font-size: 28px; font-weight: 700;
  color: #303133; line-height: 1.2;
  font-feature-settings: "tnum";
}
.stat-value.pulse {
  color: #F56C6C;
  animation: stat-pulse 1.5s ease-in-out infinite;
}
@keyframes stat-pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.15); }
}
.stat-label {
  font-size: 11px;
  color: #909399;
  margin-top: 4px;
}
.stat-divider {
  width: 1px; height: 40px;
  background: #ebeef5;
}
.welcome-tip {
  display: flex; align-items: center; justify-content: center;
  gap: 6px;
  font-size: 12px; color: #909399;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 8px;
}

/* 头部样式 */
.title-icon {
  margin-right: 4px;
  filter: drop-shadow(0 2px 4px rgba(64, 158, 255, 0.3));
}
.status-tag {
  margin-left: 8px;
  font-weight: 600;
}
.conn-status {
  display: inline-flex; align-items: center;
  padding: 2px 10px;
  font-size: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.6);
  margin-left: 8px;
}
.status-dot {
  display: inline-block;
  width: 8px; height: 8px;
  border-radius: 50%;
  background: currentColor;
  margin-right: 6px;
  animation: status-pulse 2s ease-in-out infinite;
}
@keyframes status-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.2); }
}
.claim-btn {
  background: linear-gradient(135deg, #F56C6C, #E6A23C);
  color: #fff;
  border: none;
  box-shadow: 0 4px 12px rgba(245, 108, 108, 0.3);
  transition: transform 0.2s;
}
.claim-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(245, 108, 108, 0.4);
}
.claim-btn:disabled {
  background: #c0c4cc;
  box-shadow: none;
}

.search-btn { background: #fff; color: #606266; border: 1px solid #dcdfe6; }
.search-bar { margin-bottom: 16px; }
.search-results { max-height: 60vh; overflow-y: auto; }
.search-empty { text-align: center; padding: 40px 20px; color: #909399; }
.search-item {
  padding: 12px;
  margin-bottom: 8px;
  background: #fafbfc;
  border-radius: 8px;
  border-left: 3px solid #67C23A;
}
.search-item-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.search-time { font-size: 11px; color: #909399; }
.search-content { font-size: 14px; line-height: 1.5; color: #303133; }
.search-content :deep(mark) { background: #fef3c0; color: #d97706; padding: 0 2px; border-radius: 2px; }
.dash-btn {
  background: linear-gradient(135deg, #409EFF, #909399);
  color: #fff;
  border: none;
}
.dash-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.3);
}

/* 等候客户面板 (新样式) */
.waiting-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12px;
}
.waiting-header h4 {
  margin: 0;
  display: flex; align-items: center; gap: 6px;
  font-size: 16px;
}
.waiting-list {
  display: flex; flex-direction: column; gap: 8px;
  margin-bottom: 12px;
}
.waiting-item {
  position: relative;
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  transition: all 0.2s;
}
.waiting-item:hover {
  border-color: #409EFF;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.1);
  transform: translateY(-1px);
}
.wi-info { display: flex; align-items: center; gap: 12px; }
.wi-avatar {
  width: 40px; height: 40px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409EFF, #67C23A);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 16px; font-weight: 600;
  flex-shrink: 0;
}
.wi-line1 {
  display: flex; align-items: center; gap: 6px;
  margin-bottom: 2px;
}
.wi-line2 {
  font-size: 12px; color: #909399;
}
.hint {
  display: flex; align-items: center; justify-content: center;
  gap: 4px;
  font-size: 12px; color: #909399;
  padding: 8px;
  margin: 0;
}
.waiting-panel { width: 100%; max-width: 480px; margin: 0 auto; padding: 0 16px; }
.waiting-panel h4 { margin: 0 0 12px; color: #303133; font-size: 14px; text-align: left; }
.waiting-item { display: flex; align-items: center; justify-content: space-between; padding: 12px; margin-bottom: 8px; background: #fff; border: 1px solid #ebeef5; border-radius: 6px; box-shadow: 0 1px 2px rgba(0,0,0,0.04); transition: all 0.2s; }
.waiting-item:hover { border-color: #409eff; box-shadow: 0 2px 6px rgba(64,158,255,0.15); }
.wi-info { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #606266; }
.wi-info .sno { font-weight: 600; color: #303133; }
.waiting-panel .hint { margin-top: 12px; font-size: 12px; color: #909399; text-align: center; }
.chat-header { display: flex; align-items: center; justify-content: space-between; padding: 0 16px; height: 48px; background: #fff; border-bottom: 1px solid #ebeef5; }
.header-left { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.header-right { display: flex; align-items: center; gap: 6px; }
.canned-list { max-height: 400px; overflow-y: auto; }
.canned-item { padding: 12px; border: 1px solid #ebeef5; border-radius: 4px; margin-bottom: 8px; cursor: pointer; transition: all 0.15s; }
.canned-item:hover { border-color: #409eff; background: #f0f9eb; }
.canned-title { font-weight: 600; color: #303133; margin-bottom: 4px; }
.canned-content { font-size: 13px; color: #606266; }
</style>