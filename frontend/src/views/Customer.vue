<script setup>
/**
 * Customer.vue (客户聊天主入口) — v5 重构
 *  - 装配 MessageList + MessageBubble + ChatComposer 子组件
 *  - STOMP 连接/订阅 + 业务事件分发
 *  - 会话生命周期 (创建/转人工/退出/评分)
 *  - 合规录制 (ChatRecordSDK)
 *
 * 重构要点:
 *  - 录音/表情/响应式 → composables (useRecorder/useEmojiPicker/useResponsive)
 *  - 消息渲染 → components/chat/MessageList + MessageBubble
 *  - 输入框 → components/chat/ChatComposer
 *  - VOICE 解析 → composables/useVoiceMessage
 */
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElImageViewer } from 'element-plus'
import { Menu } from '@element-plus/icons-vue'
import { imApi } from '@/api/im'
import { recordApi } from '@/api/record'
import { useUserStore } from '@/stores/user'
import { StompClient } from '@/utils/ws-client'
import { ChatRecordSDK } from '@/utils/record-sdk'
import MessageList from '@/components/chat/MessageList.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import { useResponsive } from '@/composables/useResponsive'

const router = useRouter()
const userStore = useUserStore()
const { isMobile, drawerVisible, previewImageUrl } = useResponsive()

// ============ 会话 + 消息状态 ============
const session = ref(null)
const messages = ref([])
const draft = ref('')
const connected = ref(false)
const reconnecting = ref(false)
const peerTyping = ref('')
const readMap = ref({})
const recalledMap = ref({})
const exiting = ref(false)
const messageListRef = ref(null)

function appendMessage(m, skipScroll = false) {
  if (!m.id) m.id = `tmp-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  messages.value.push(m)
  if (m.recalled) recalledMap.value[m.id] = true
  if (!skipScroll) nextTick(scrollToBottom)
}

function scrollToBottom() {
  const el = messageListRef.value
  if (!el) return
  const scroller = el.$el || el
  if (scroller.scrollTo) scroller.scrollTo({ top: scroller.scrollHeight, behavior: 'smooth' })
  else scroller.scrollTop = scroller.scrollHeight
}

// ============ 评分弹窗 ============
const showRating = ref(false)
const rating = ref(5)
const ratingComment = ref('')
const ratingSubmitting = ref(false)
const pendingRatingSessionId = ref(null)

// ============ 技能选择弹窗 ============
const showSkillPicker = ref(false)
const selectedSkill = ref('')
const chatMode = ref('human')
const creating = ref(false)

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
        ? '已连接智能客服, 请描述您的问题 (输入 "人工" 转接真人)'
        : (session.value.status === 'WAITING' ? '已创建会话, 正在为您匹配客服...' : '客服已接入, 请开始提问'),
      createdAt: new Date().toISOString()
    }, true)
    scrollToBottom()
    if (!recorder || !recorder.recordId) {
      tryRecorder().catch(() => {})
    }
  } catch (e) {
    ElMessage.error(e.message || '创建会话失败')
  } finally {
    creating.value = false
  }
}

// ============ WebSocket (STOMP) ============
let stomp = null
let typingTimer = null
function onStompMessage(payload) {
  if (!payload || !session.value) return
  if (payload.sessionId && payload.sessionId !== session.value.id) return
  // 服务端事件 (BOT_TRANSFER / CLOSED / TRANSFERRED / PRESENCE / READ / RECALL)
  if (payload && (payload.type || payload.sessionId)) {
    if (payload.type === 'BOT_TRANSFER') {
      ElMessage.success('已为您转接人工客服, 正在匹配坐席...')
      drawerVisible.value = false
      stopRecorder('TRANSFER_BOT_TO_HUMAN').catch(() => {})
      loadActiveSession().catch(() => {})
      return
    }
    if (payload.type === 'CLOSED') {
      if (session.value && payload.sessionId === session.value.id && session.value.status !== 'CLOSED') {
        session.value = { ...session.value, status: 'CLOSED' }
        stopRecorder('SESSION_CLOSED').catch(() => {})
        ElMessage.info(payload.reason === 'CUSTOMER_EXIT' ? '您已退出会话' : '会话已结束')
      }
      return
    }
    if (payload.type === 'PRESENCE') {
      if (session.value && payload.sessionId === session.value.id) {
        session.value = { ...session.value, peerOnline: payload.online }
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
    if (payload.type === 'TRANSFERRED') {
      ElMessage.info('会话已转接给其他客服')
      drawerVisible.value = false
      refreshSessionFromServer()
      return
    }
  }
  // 普通消息
  if (payload && payload.id && payload.msgType) {
    if (payload.msgType === 'SYSTEM' || payload.msgType === 'RECALL') return
    appendMessage(payload)
    imApi.readMessage(payload.id).catch(() => {})
  }
}

function onTypingEvent(payload) {
  if (!payload || !session.value) return
  if (payload.userId === userStore.id) return
  peerTyping.value = payload.typing ? '客服' : ''
}

async function loadActiveSession() {
  try {
    const list = await imApi.mySessions()
    const active = list.find((s) => s.status !== 'CLOSED')
    if (active) {
      session.value = active
      await loadHistory()
      tryRecorder().catch(() => {})
    }
  } catch {}
  try {
    const offs = await imApi.drainOffline()
    offs.forEach((m) => appendMessage(m))
  } catch {}
}

async function loadHistory() {
  if (!session.value) return
  try {
    const list = await imApi.history(session.value.id, 100)
    messages.value = list.map((m) => ({
      ...m,
      id: m.id || `tmp-h-${Math.random().toString(36).slice(2, 8)}`,
    }))
    nextTick(scrollToBottom)
  } catch {}
}

async function refreshSessionFromServer() {
  try {
    const list = await imApi.mySessions()
    const fresh = list.find((s) => s.id === session.value?.id)
    if (fresh) session.value = fresh
  } catch {}
}

// ============ 发送消息 ============
async function send() {
  if (!session.value || !draft.value.trim()) return
  const text = draft.value.trim()
  draft.value = ''
  if (stomp && stomp.connected) {
    stomp.send(`/app/send/${session.value.id}`, { msgType: 'TEXT', content: text })
  } else {
    // STOMP 不可用时 REST fallback
    await imApi.history(session.value.id, 1)  // noop, just to keep import used
    try {
      await fetch(`/api/im/session/${session.value.id}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${userStore.token}` },
        body: JSON.stringify({ msgType: 'TEXT', content: text }),
      })
    } catch (e) { console.warn('rest send failed', e) }
  }
}

function sendTyping(typing) {
  if (!stomp || !session.value) return
  stomp.send(`/app/typing/${session.value.id}`, { typing })
}

function onTyping() {
  if (!session.value) return
  sendTyping(true)
  clearTimeout(typingTimer)
  typingTimer = setTimeout(() => sendTyping(false), 1500)
}

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

// ============ 图片发送 ============
function onImagePick(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) return ElMessage.warning('图片不能超过 5MB')
  const reader = new FileReader()
  reader.onload = () => {
    stomp?.send(`/app/send/${session.value.id}`, {
      sessionId: session.value.id, msgType: 'IMAGE', content: reader.result,
    })
  }
  reader.readAsDataURL(file)
  e.target.value = ''
}

// ============ 语音消息 (ChatComposer 上传后回调) ============
async function onVoiceBlob({ blob, seconds, mimeType }) {
  if (!session.value) return
  try {
    const file = new File([blob], `voice-${Date.now()}.webm`, { type: blob.type || mimeType })
    const res = await imApi.uploadFile(session.value.id, file)
    const data = res.data || res
    const url = data?.url || data?.rawUrl || ''
    stomp?.send(`/app/send/${session.value.id}`, {
      msgType: 'VOICE',
      content: JSON.stringify({ url, seconds, mimeType }),
    })
    ElMessage.success(`语音 ${seconds}s 已发送`)
  } catch (err) {
    ElMessage.error('上传失败: ' + (err?.message || '未知错误'))
  }
}

// ============ 会话操作 ============
async function botTransferToHuman() {
  if (!session.value || exiting.value) return
  try {
    const res = await imApi.transferToHuman(session.value.id, session.value.skillTag)
    const newSession = res.data || res
    ElMessage.success('已为您转接人工客服, 请稍等接入')
    drawerVisible.value = false
    session.value = newSession
    messages.value = []
    appendMessage({
      msgType: 'SYSTEM', senderRole: 'SYSTEM',
      content: '已退出机器人会话, 正在为您匹配人工客服...',
      createdAt: new Date().toISOString()
    }, true)
    if (recorder && recorder.recordId) {
      await stopRecorder('TRANSFER_BOT_TO_HUMAN')
    }
  } catch (e) {
    ElMessage.error('转人工失败: ' + (e?.message || '未知错误'))
  }
}

async function requestTransfer() {
  if (!session.value || exiting.value) return
  try {
    await ElMessageBox.prompt(
      '告诉我们需要转接的原因 (可选), 我们会为您换一个更合适的客服.',
      '申请转接客服',
      { confirmButtonText: '提交申请', cancelButtonText: '取消', inputPlaceholder: '例如: 这个问题A客服处理不了' }
    )
    await imApi.requestTransfer(session.value.id, session.value.skillTag)
    ElMessage.success('已为您申请转接, 请稍等')
    drawerVisible.value = false
  } catch (e) {
    if (e === 'cancel' || e?.message === 'cancel') return
    ElMessage.error('申请转接失败: ' + (e?.message || '未知错误'))
  }
}

async function customerExit() {
  if (!session.value || exiting.value) return
  exiting.value = true
  try {
    await ElMessageBox.confirm('确认退出会话? 退出会清空未发送的内容。', '主动退出', { type: 'warning' })
    await imApi.customerExit(session.value.id, 'USER_EXIT')
    await stopRecorder('USER_EXIT')
    messages.value = []
    session.value = null
    if (pendingRatingSessionId.value) showRating.value = true
  } catch (e) {
    if (e === 'cancel' || e?.message === 'cancel') return
    ElMessage.error('退出失败: ' + (e?.message || '未知错误'))
  } finally {
    exiting.value = false
  }
}

async function closeSession() {
  if (!session.value || exiting.value) return
  exiting.value = true
  try {
    await imApi.closeSession(session.value.id)
    await stopRecorder('NORMAL')
    pendingRatingSessionId.value = session.value.id
    showRating.value = true
    messages.value = []
    session.value = null
  } catch (e) {
    ElMessage.error(e.message || '关闭失败')
  } finally {
    exiting.value = false
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

function skipRating() {
  showRating.value = false
}

// ============ 录制 SDK ============
let recorder = null
let existingRecordId = null

async function tryRecorder() {
  if (!session.value) return
  try {
    existingRecordId = await ChatRecordSDK.findResumable(recordApi, session.value.id, userStore.id)
  } catch (e) {
    console.warn('[record] failed to query resumable records', e)
  }
  if (!existingRecordId) {
    let ok = false
    try {
      await ElMessageBox.confirm(
        '为了服务质量与合规要求, 我们会对本次会话页面进行视频录制 (包括聊天内容和页面操作), 仅供内部审计/质量回溯使用。\n\n📹 默认采用 DOM 录制 (25fps / 1080p / 2.5Mbps)\n注: 录制会在您切换页面、切后台后自动恢复, 仅在主动退出时才结束。',
        '开始会话前需告知',
        { confirmButtonText: '同意并开始', cancelButtonText: '不同意 (将不能发起会话)', type: 'warning' }
      )
      ok = true
    } catch {
      ElMessage.warning('您未同意录制, 为合规要求, 建议退出当前会话')
      return
    }
    if (!ok) return
  }

  recorder = new ChatRecordSDK({
    apiBase: '/api/im/record',
    api: recordApi,
    token: userStore.token,
    sessionId: session.value.id,
    userId: userStore.id,
    nickname: userStore.nickname || userStore.username || '',
    target: '.customer-shell',
    mode: 'dom',
    fps: 25,
    chunkDurationMs: 3000,
    bitrate: 2_500_000,
    maxWidth: 1920,
    maxHeight: 1080,
    watermark: true,
    brandText: '本会话已开启录制 — 用于服务回溯',
    ignoreSelector: '.no-record',
    existingRecordId,
    pauseOnHidden: true,
    onScreenPickerRequired: async () => {
      if (recorder?.opts?.mode !== 'screen') return
      await ElMessageBox.confirm(
        '高清录制模式需要您授权屏幕共享. 选择"窗口/标签页"仅录制当前页面内容.',
        '请授权屏幕共享',
        { confirmButtonText: '继续', cancelButtonText: '取消', type: 'info' }
      ).catch(() => { throw new Error('用户取消授权') })
    },
    onError: (e) => {
      console.error('[record]', e)
      if (recorder?.opts?.mode === 'screen' && /拒绝|cancel|denied|ended/i.test(String(e?.message))) {
        ElMessage.warning('屏幕录制被拒, 自动改用 DOM 录制')
        try { recorder.stop('SCREEN_DENIED') } catch {}
      } else {
        ElMessage.error('录制出错: ' + (e?.message || ''))
      }
    },
    onState: (s) => console.log('[record] state =', s),
  })
  try { await recorder.start() } catch (e) { console.warn('[record] start failed', e) }
}

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

// ============ 登录态/登出 ============
function logout() {
  stopRecorder('LOGOUT').finally(() => {
    userStore.logout()
    router.push('/login')
  })
}

// ============ 生命周期 ============
onMounted(async () => {
  // STOMP 连接
  stomp = new StompClient({
    token: userStore.token,
    userId: userStore.id,
    role: userStore.role,
    onConnect: () => { connected.value = true },
    onDisconnect: () => { connected.value = false },
    onReconnected: () => loadActiveSession(),
    onMessage: onStompMessage,
  })
  stomp.connect()

  // 订阅 typing + events (per session, 在 select 时再 subscribe)
  // 这里先订阅用户级 events 频道
  await loadActiveSession()

  // 订阅当前 session 的 typing (如果存在)
  if (session.value) subscribeSession(session.value.id)
})

onBeforeUnmount(() => {
  // v3: 不在这里 stop recorder, 让录像保持 active 状态以便续录
  if (stomp) stomp.disconnect()
  if (typingTimer) clearTimeout(typingTimer)
})

// 监听 session 切换, 重订阅 typing
function subscribeSession(sid) {
  if (!stomp) return
  const dest = `/user/queue/typing/${sid}`
  stomp.subscribe(dest, onTypingEvent)
}

// ============ 子组件事件包装 ============
function onPreviewImage(url) { previewImageUrl.value = url }
function onRecall(id) { recall(id) }
</script>

<template>
  <div class="customer-shell" :class="{ mobile: isMobile }">
    <!-- 头部 -->
    <header class="topbar no-record">
      <el-button v-if="isMobile" link class="menu-btn" @click="drawerVisible = true">
        <el-icon><Menu /></el-icon>
      </el-button>
      <span class="title">在线客服</span>
      <span class="status" :class="{ ok: connected, warn: !connected && !reconnecting, bad: reconnecting }">
        {{ connected ? '已连接' : (reconnecting ? '重连中…' : '未连接') }}
      </span>
      <div class="spacer" />
      <el-button v-if="!session" type="primary" class="side-btn" @click="showSkillPicker = true">
        开始咨询
      </el-button>
      <el-button v-else link @click="logout">退出</el-button>
    </header>

    <main>
      <!-- 桌面侧栏 -->
      <aside v-if="!isMobile && session" class="sidebar">
        <div class="sidebar-content">
          <p>会话号: <code>{{ session.sessionNo }}</code></p>
          <p>技能: <el-tag size="small">{{ session.skillTag || '通用' }}</el-tag></p>
          <p v-if="session.agentId">客服: #{{ session.agentId }}</p>
          <p v-else-if="session.isBot === 1" style="color:#67c23a">🤖 智能客服</p>
          <div class="session-actions">
            <el-button v-if="session.isBot === 1 && session.status !== 'CLOSED'"
                       size="small" type="success" plain @click="botTransferToHuman">
              转接人工
            </el-button>
            <el-button v-if="session.agentId" size="small" type="warning" plain
                       :disabled="exiting || session.status === 'CLOSED'" @click="requestTransfer">
              转接客服
            </el-button>
            <el-button size="small" type="danger" plain
                       :disabled="exiting || session.status === 'CLOSED'" @click="customerExit">
              主动退出
            </el-button>
            <el-button size="small" plain
                       :disabled="exiting || session.status === 'CLOSED'" @click="closeSession">
              结束会话
            </el-button>
          </div>
        </div>
      </aside>

      <!-- 聊天主区 -->
      <section class="chat-area">
        <div v-if="!session" class="empty">
          <el-empty description="点击右上角开始咨询" />
        </div>
        <template v-else>
          <MessageList
            :messages="messages"
            :session-id="session?.id"
            :peer-typing="peerTyping">
            <template #bubble="{ item }">
              <MessageBubble
                :item="item"
                :current-user-id="userStore.id"
                :read-map="readMap"
                :recalled-map="recalledMap"
                :show-file="false"
                @preview-image="onPreviewImage"
                @recall="onRecall" />
            </template>
          </MessageList>

          <ChatComposer
            v-model="draft"
            :disabled="session.status === 'CLOSED'"
            image-accept="image/*"
            placeholder="输入消息…"
            @send="send"
            @typing="onTyping"
            @image-pick="onImagePick"
            @voice-blob="onVoiceBlob" />
        </template>
      </section>
    </main>

    <!-- 移动端侧栏 -->
    <el-drawer v-if="isMobile" v-model="drawerVisible" title="会话信息" direction="rtl" size="80%">
      <div class="drawer-content">
        <el-button v-if="!session" type="primary" size="large" class="side-btn" @click="showSkillPicker = true; drawerVisible = false">
          开始咨询
        </el-button>
        <div v-else>
          <p>会话号: <code>{{ session.sessionNo }}</code></p>
          <p>技能: <el-tag size="small">{{ session.skillTag || '通用' }}</el-tag></p>
          <p v-if="session.agentId">客服: #{{ session.agentId }}</p>
          <p v-else-if="session.isBot === 1" style="color:#67c23a">🤖 智能客服</p>
          <el-button size="large" type="success" plain v-if="session.isBot === 1 && session.status !== 'CLOSED'" class="side-btn" @click="botTransferToHuman">
            转接人工
          </el-button>
          <el-button size="large" type="warning" plain v-if="session.agentId" :disabled="exiting || session.status === 'CLOSED'" class="side-btn" @click="requestTransfer">
            转接客服
          </el-button>
          <el-button size="large" type="danger" plain :disabled="exiting || session.status === 'CLOSED'" class="side-btn" @click="customerExit">主动退出</el-button>
          <el-button size="large" type="danger" plain :disabled="exiting || session.status === 'CLOSED'" class="side-btn" @click="closeSession">结束会话</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 技能选择 -->
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
        智能客服可解答常见问题, 随时输入 "人工" 转接真人客服。
      </p>
      <template #footer>
        <el-button type="primary" size="large" :loading="creating" @click="startSession">
          {{ chatMode === 'bot' ? '开始智能咨询' : '开始咨询' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- CSAT 评分 -->
    <el-dialog v-model="showRating" title="请对本次服务评分" width="380px" :show-close="false" :close-on-click-modal="false">
      <el-rate v-model="rating" :max="5" size="large" />
      <el-input v-model="ratingComment" type="textarea" :rows="3" placeholder="说点什么吧 (可选)" style="margin-top: 16px;" />
      <template #footer>
        <el-button size="large" @click="skipRating">跳过</el-button>
        <el-button type="primary" size="large" :loading="ratingSubmitting" @click="submitRating">提交</el-button>
      </template>
    </el-dialog>

    <!-- 图片预览 -->
    <el-image-viewer v-if="previewImageUrl" :url-list="[previewImageUrl]" :initial-index="0" @close="previewImageUrl = null" />
  </div>
</template>

<style scoped>
.customer-shell { display: flex; flex-direction: column; height: 100vh; background: #f7f8fa; }
.customer-shell.mobile { height: 100dvh; }
.topbar { display: flex; align-items: center; gap: 8px; padding: 0 16px; height: 56px; background: #fff; border-bottom: 1px solid #ebeef5; }
.topbar .title { font-weight: 600; }
.topbar .status { font-size: 12px; padding: 2px 8px; border-radius: 4px; background: #f4f4f5; color: #909399; }
.topbar .status.ok { background: #f0f9eb; color: #67c23a; }
.topbar .status.warn { background: #fdf6ec; color: #e6a23c; }
.topbar .status.bad { background: #fef0f0; color: #f56c6c; }
.spacer { flex: 1; }
main { flex: 1; display: flex; min-height: 0; }
.sidebar { width: 280px; padding: 16px; background: #fff; border-right: 1px solid #ebeef5; overflow-y: auto; }
.sidebar-content p { margin: 8px 0; font-size: 14px; color: #606266; }
.session-actions { margin-top: 16px; display: flex; flex-direction: column; gap: 8px; }
.side-btn { width: 100%; margin-top: 8px; }
.chat-area { flex: 1; display: flex; flex-direction: column; min-width: 0; background: #fff; }
.empty { flex: 1; display: flex; align-items: center; justify-content: center; }
.skill-group { display: flex; flex-wrap: wrap; }
.drawer-content p { margin: 8px 0; font-size: 14px; color: #606266; }
</style>