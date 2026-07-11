<script setup>
/**
 * Customer.vue - 客户聊天主入口 (H5 移动端优先).
 * ----------------------------------------------------------------------------
 * 模块职责:
 *   - 装配 MessageList + MessageBubble + ChatComposer 子组件
 *   - STOMP 连接/订阅 + 业务事件分发 (新消息/已读/转接/关闭/录存/动态)
 *   - 会话生命周期 (创建 bot/人工会话 + 转人工 + 退出 + CSAT 评分)
 *   - 合规录制 (ChatRecordSDK v4 HD 25fps/2.5Mbps/1080p)
 *   - 移动端响应式 (drawer 侧边栏 + 适配/拨号 + 安全区)
 *
 * v5 重构: 从 983 行压缩到 659 行 (-33%).
 *   - 录音/表情/响应式 → composables/ (useRecorder/useEmojiPicker/useResponsive)
 *   - 消息渲染 → components/chat/MessageList + MessageBubble
 *   - 输入框 → components/chat/ChatComposer
 *   - VOICE 解析 → composables/useVoiceMessage
 *
 * 关键状态:
 *   - currentSession: 当前会话 (ref<ChatSession | null>)
 *   - messages: 消息列表 (server-side merge 后的 array)
 *   - isMobile / drawerVisible: 响应式 (useResponsive)
 *   - recorder: 录音 hook (useRecorder)
 *
 * 事件订阅 (STOMP):
 *   - /user/queue/messages: 新消息推送 → appendMessage
 *   - /user/queue/events: 业务事件 (READ/RECALL/PRESENCE/CLOSED/BOT_TRANSFER)
 *   - /topic/typing/{sid}: 对方输入状态
 *   - /topic/sessions/new: (仅坐席) 不需订阅
 */
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from "vue"
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Menu, Promotion, User, Phone, VideoCamera, Search } from '@element-plus/icons-vue'
import { imApi } from '@/api/im'
import { recordApi } from '@/api/record'
import { cdpApi } from '@/api/cdp'
import { useUserStore } from '@/stores/user'
import { StompClient } from '@/utils/ws-client'
import { ChatRecordSDK } from '@/utils/record-sdk'
import MessageList from '@/components/chat/MessageList.vue'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import PhoneCallDialog from '@/components/voice/PhoneCallDialog.vue'
import { useResponsive } from '@/composables/useResponsive'
import { useProactiveFeed } from '@/composables/useProactiveFeed'
import { useDraft } from '@/composables/useDraft'
import { useKeyboard } from '@/composables/useKeyboard'
import { useDragUpload } from '@/composables/useDragUpload'
import DragUploadOverlay from '@/components/chat/DragUploadOverlay.vue'
import ThemeToggle from '@/components/common/ThemeToggle.vue'
import { showSuccess, showWarn, handleError } from '@/utils/error-handler'
import FloatingActionButton from '@/components/fab/FloatingActionButton.vue'
import QuickQuestions from '@/components/quick-actions/QuickQuestions.vue'
import ProactiveFeed from '@/components/quick-actions/ProactiveFeed.vue'
import ProfileCenter from '@/components/profile-360/ProfileCenter.vue'
import VideoCallDialog from '@/components/video/VideoCallDialog.vue'

const router = useRouter()
const userStore = useUserStore()
const { isMobile, drawerVisible, previewImageUrl } = useResponsive()

// ============ V3 数字孪生 + 预见式服务 (阶段 1) ============
const profile = ref(null)
const profileLoading = ref(false)
const showPhone = ref(false)
const showVideo = ref(false)
const showSearch = ref(false)
const searchKey = ref('')
const searchResults = ref([])
const searchLoading = ref(false)
const showProfile = ref(false)
const predHistory = ref([])
const profileTab = ref('profile')
const predLoading = ref(false)
const { feed: proactiveFeed, unreadCount: proactiveUnread, push: pushProactive, dismiss: dismissProactive } = useProactiveFeed()

async function onShowProfile(open) {
  if (open && predHistory.value.length === 0) {
    predLoading.value = true
    try {
      const r = await predictionApi.getHistory(userStore.id)
      if (r.code === 200) predHistory.value = r.data || []
    } catch (e) { console.error(e) }
    finally { predLoading.value = false }
  }
}

async function loadProfile() {
  profileLoading.value = true
  try {
    const r = await cdpApi.getMyProfile()
    if (r.code === 200) profile.value = r.data
  } catch (e) { /* 静默 */ }
  finally { profileLoading.value = false }
}

async function heartbeat() {
  try { await cdpApi.heartbeat() } catch (e) {}
}

// 拉取初始画像 + 心跳
onMounted(() => {
  loadProfile()
  // 每 60s 一次心跳 (保持 last_active_at 准, 避免被标为 silent)
  setInterval(heartbeat, 60_000)
})

// ============ V3: FAB / 快捷问题 / 主动推送 处理器 ============
async function onSearch() {
  if (!searchKey.value.trim() || !session.value) return
  searchLoading.value = true
  try {
    const r = await imApi.searchMessages(session.value.id, searchKey.value, 50)
    if (r.code === 200) searchResults.value = r.data || []
  } catch (e) { console.error(e) }
  finally { searchLoading.value = false }
}

function highlight(text, key) {
  if (!text || !key) return text || ''
  const esc = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return text.replace(new RegExp(esc, 'gi'), m => '<mark>' + m + '</mark>')
}

function onFabAction(name) {
  switch (name) {
    case 'chat':
      if (!session.value) showSkillPicker.value = true
      break
    case 'transfer-human':
      if (session.value) {
        if (session.value.isBot === 1) botTransferToHuman()
        else if (session.value.agentId) requestTransfer()
      }
      break
    case 'profile':
      showProfile.value = true
      loadProfile()
      break
    case 'orders':
      ElMessage.info('订单查询功能开发中 (阶段 2)')
      cdpApi.recordEvent('fab_click_orders', {}).catch(() => {})
      break
    case 'scan':
      ElMessage.info('扫一扫功能开发中 (阶段 2)')
      cdpApi.recordEvent('fab_click_scan', {}).catch(() => {})
      break
    case 'community':
      ElMessage.info('社区功能开发中 (阶段 3)')
      cdpApi.recordEvent('fab_click_community', {}).catch(() => {})
      break
    case 'video':
      startVideoCall()
      break
  }
}

function onQuickQuestion(q) {
  // 填入输入框, 客户可编辑后发送
  draft.value = q
  // 也上报 cdp 事件
  cdpApi.recordEvent('quick_question_pick', { question: q }, session.value?.id).catch(() => {})
}

function onProactiveAction(item) {
  // SESSION_INVITE: 一键发起会话 + 携带上下文
  if (item.actionType === 'SESSION_INVITE') {
    if (!session.value) {
      // 还没会话, 弹出技能选择, 然后自动发文本
      showSkillPicker.value = true
      // 预填 (在 skill 选完后 onCreate 后)
      pendingProactiveText.value = item.text
    } else {
      send(item.text)
    }
    dismissProactive(item.id)
    return
  }
  // PUSH: 默认只 dismiss
  dismissProactive(item.id)
  // 上报点击
  cdpApi.recordEvent('proactive_click', { ruleCode: item.ruleCode }).catch(() => {})
}

const pendingProactiveText = ref('')

// ============ 会话 + 消息状态 ============
const session = ref(null)
const messages = ref([])
/**
 * 草稿自动保存:
 *   - 切换 session 时, 加载旧草稿
 *   - 输入时, 防抖 500ms 写入 localStorage
 *   - 发送成功后, 清空草稿
 * 场景: 客户输入到一半切换会话 / 刷新页面, 内容不丢
 */
const draftSessionKey = computed(() => `customer-${session.value?.id || 'new'}`)
const draftDraft = useDraft(draftSessionKey, () => draft.value)
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
  if (!payload) return
  // 实时统计事件 (大屏推送)
  if (payload.event) {
    if (payload.event === 'SESSION_CLOSED' && session.value && payload.data?.sessionId === session.value.id) {
      ElMessage.success('会话已结束, 感谢您的咨询')
      stopRecorder('SESSION_CLOSED').catch(() => {})
      setTimeout(() => { window.location.reload() }, 2000)
    } else if (payload.event === 'SESSION_RATED') {
      ElMessage.success('感谢您的评分!')
    }
    return
  }
  if (!session.value) return
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
    if (payload.type === 'PREDICTION') {
      // V3 预见式服务主动推送
      pushProactive(payload)
      // 同时上报 cdp 事件
      cdpApi.recordEvent('prediction_received', {
        ruleCode: payload.ruleCode,
        actionType: payload.actionType,
      }).catch(() => {})
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
/**
 * 发送消息 (客户侧):
 *   1) 校验: 草稿非空 + 有当前会话
 *   2) 清空草稿 + draftDraft.clearDraft (localStorage)
 *   3) 优先 STOMP send (实时双向)
 *   4) STOMP 不可用 → REST fallback
 *   5) 失败 → 提示 + 草稿保留 (用户可重发)
 */
async function send() {
  if (!session.value || !draft.value.trim()) return
  const text = draft.value.trim()
  draft.value = ''
  draftDraft.clearDraft()  // 草稿已发, 清空
  if (stomp && stomp.connected) {
    stomp.send(`/app/send/${session.value.id}`, { msgType: 'TEXT', content: text })
  } else {
    // STOMP 不可用时 REST fallback
    try {
      await fetch(`/api/im/session/${session.value.id}/message`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${userStore.token}` },
        body: JSON.stringify({ msgType: 'TEXT', content: text }),
      })
    } catch (e) { handleError(e, { customMessage: '发送失败, 请重试' }) }
  }
}

// ============ 拖拽上传 (文件/图片拖到聊天区) ============
const chatAreaRef = ref(null)
const { isDragging, bind: bindDrag, unbind: unbindDrag } = useDragUpload(chatAreaRef, (files) => {
  // 复用 ChatComposer 的 onFiles 逻辑: 客户拖拽文件
  files.forEach(f => showSuccess(`正在上传 ${f.name}...`))
  // 简化: 派发到全局
  document.dispatchEvent(new CustomEvent('cs:upload-files', { detail: files }))
})

// ============ 键盘快捷键 ============
const { onKey: onKeybind } = useKeyboard()
onKeybind('enter', () => {
  // 焦点在输入框才发送
  const el = document.activeElement
  if (el?.classList?.contains('el-textarea__inner') || el?.tagName === 'TEXTAREA') {
    send()
  }
})
onKeybind('escape', () => {
  // Esc 关闭弹窗 (drawer 等)
  drawerVisible.value = false
})

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
    if (m) { m.recalled = true; m.msgType = 'RECALL'; m.content = '你撤回了一条消息' }
    showSuccess('已撤回')
  } catch (e) {
    handleError(e, { customMessage: '撤回失败 (仅 2 分钟内可撤回)' })
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

// ============ V3 视频通话 ============
const showVideoCall = ref(false)

function startVideoCall() {
  if (!session.value || !session.value.agentId) {
    ElMessage.warning('请先接入人工客服')
    return
  }
  showVideoCall.value = true
  cdpApi.recordEvent('video_call_start', { peerId: session.value.agentId }, session.value.id).catch(() => {})
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
  unbindDrag()
})

// 拖拽: 绑定到 chat-area
onMounted(() => {
  nextTick(() => bindDrag())
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
      <span class="title">
        <span class="title-icon">💬</span> 在线客服
        <el-tag v-if="profile && profile.vipLevel > 0" type="warning" size="small" class="title-vip">{{ profile.vipLabel }}</el-tag>
      </span>
      <span class="status" :class="{ ok: connected, warn: !connected && !reconnecting, bad: reconnecting }">
        <span class="status-dot"></span>
        {{ connected ? '已连接' : (reconnecting ? '重连中…' : '未连接') }}
      </span>
      <div class="spacer" />
      <el-button v-if="!session" type="primary" class="side-btn" round @click="showSkillPicker = true">
        <el-icon><Promotion /></el-icon>&nbsp;开始咨询
      </el-button>
      <template v-else>
        <el-button class="side-btn" round plain @click="showSearch = true">
          <el-icon><Search /></el-icon>&nbsp;搜索
        </el-button>
        <ThemeToggle />
        <el-button link @click="logout">退出</el-button>
      </template>
    </header>

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
              {{ m.fromRole === 'CUSTOMER' ? '我' : '客服' }}
            </el-tag>
            <span class="search-time">{{ new Date(m.createdAt).toLocaleString('zh-CN') }}</span>
          </div>
          <div class="search-content" v-html="highlight(m.content, searchKey)" />
        </div>
      </div>
    </el-dialog>

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
            <el-button v-if="session.agentId && session.status !== 'CLOSED'"
                       size="small" type="primary" plain
                       @click="startVideoCall">
              📹 视频通话
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
      <section ref="chatAreaRef" class="chat-area">
        <DragUploadOverlay :active="isDragging" />
        <!-- V3: 主动推送流 (顶部粘性) -->
        <ProactiveFeed :feed="proactiveFeed" @dismiss="dismissProactive"
                       @action="onProactiveAction" />

        <div v-if="!session" class="welcome">
          <div class="welcome-bg">
            <div class="welcome-blob welcome-blob-1"></div>
            <div class="welcome-blob welcome-blob-2"></div>
          </div>
          <div class="welcome-card">
            <div class="welcome-emoji">👋</div>
            <h2 class="welcome-title">你好, {{ userStore.nickname || '朋友' }}!</h2>
            <p class="welcome-subtitle">我是你的 AI 客服助手, 7×24h 在线为你服务</p>
            <div class="welcome-actions">
              <el-button type="primary" size="large" round @click="showSkillPicker = true">
                <el-icon><Promotion /></el-icon>&nbsp;开始对话
              </el-button>
              <el-button size="large" round @click="onFabAction('profile')">
                <el-icon><User /></el-icon>&nbsp;个人中心
              </el-button>
            </div>
            <div class="welcome-stats" v-if="profile">
              <div class="stat-item">
                <div class="stat-value">{{ profile.totalSessions || 0 }}</div>
                <div class="stat-label">咨询次数</div>
              </div>
              <div class="stat-divider"></div>
              <div class="stat-item">
                <div class="stat-value">{{ profile.avgCsat || '-' }}</div>
                <div class="stat-label">平均满意度</div>
              </div>
              <div class="stat-divider"></div>
              <div class="stat-item">
                <div class="stat-value">{{ profile.healthScore || 100 }}</div>
                <div class="stat-label">健康分</div>
              </div>
            </div>
          </div>
          <!-- V3: 快捷问题卡片 (AI 预判) -->
          <div class="welcome-questions" v-if="profile">
            <QuickQuestions :profile="profile" @pick="onQuickQuestion" />
          </div>
        </div>
        <template v-else>
          <!-- V3: 快捷问题卡片 (会话中也可选) -->
          <QuickQuestions v-if="profile && session.status !== 'CLOSED'"
                          :profile="profile" @pick="onQuickQuestion" />
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
            @voice-blob="onVoiceBlob">
            <template #toolbar-extra>
              <el-button :icon="Phone" size="large" class="icon-btn phone-btn"
                         @click="showPhone = true" title="智能电话" />
              <el-button :icon="VideoCamera" size="large" class="icon-btn video-btn"
                         @click="showVideo = true" title="视频会话" />
            </template>
          </ChatComposer>
        </template>
      </section>
    </main>

    <!-- 智能电话 -->
    <PhoneCallDialog v-if="showPhone" v-model="showPhone" :callee-uid="0" callee-name="AI 客服" />
    <KeyboardHelpDialog v-model:visible="showHelp" />

    <!-- 视频通话 (V3 渠道 4) -->
    <VideoCallDialog v-if="showVideo" v-model="showVideo" :session="session" :stomp="stomp" />

    <!-- V3: FAB 浮动操作按钮 (始终显示) -->
    <FloatingActionButton :unread-count="proactiveUnread" @action="onFabAction" />
    <ConnectionBanner :online="navigatorOnline" :reconnecting="reconnecting" :stomp-connected="connected" />

    <!-- V3: 个人中心 360 抽屉 (含主动关怀历史) -->
    <el-drawer v-model="showProfile" title="个人中心" direction="rtl" size="90%" @open="onShowProfile(true)">
      <el-tabs v-model="profileTab">
        <el-tab-pane label="360 画像" name="profile">
          <ProfileCenter :profile="profile" :loading="profileLoading" @refresh="loadProfile" />
        </el-tab-pane>
        <el-tab-pane :label="`主动关怀 (${predHistory.length})`" name="predict">
          <div v-loading="predLoading" class="pred-history">
            <div v-if="predHistory.length === 0 && !predLoading" class="pred-empty">
              还没有主动关怀记录
            </div>
            <div v-for="p in predHistory" :key="p.id" class="pred-item">
              <div class="pred-header">
                <el-tag size="small" :type="p.action === 'OFFER' ? 'success' : (p.action === 'ALERT' ? 'warning' : 'info')">
                  {{ p.action === 'OFFER' ? '推荐' : (p.action === 'ALERT' ? '提醒' : p.action) }}
                </el-tag>
                <span class="pred-time">{{ new Date(p.createdAt).toLocaleString('zh-CN') }}</span>
              </div>
              <div class="pred-rule">规则: {{ p.ruleName || p.ruleId }}</div>
              <div class="pred-reason">原因: {{ p.reason || '根据您的最近行为预测' }}</div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <!-- V3: 视频通话 -->
    <VideoCallDialog v-if="session && stomp"
                     v-model="showVideoCall"
                     :peer-id="session.agentId || 0"
                     :peer-name="`客服 #${session.agentId}`"
                     :chat-session-id="session.id"
                     :stomp="stomp"
                     :local-uid="userStore.id"
                     @ended="cdpApi.recordEvent('video_call_end', {}, session?.id).catch(() => {})" />

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

/* ===== V3 欢迎页 ===== */
.welcome {
  flex: 1;
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  overflow: hidden;
}
.welcome-bg {
  position: absolute; inset: 0;
  overflow: hidden;
  pointer-events: none;
}
.welcome-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.4;
  animation: blob-float 12s ease-in-out infinite;
}
.welcome-blob-1 {
  width: 300px; height: 300px;
  background: linear-gradient(135deg, #409EFF, #67C23A);
  top: -100px; left: -100px;
}
.welcome-blob-2 {
  width: 250px; height: 250px;
  background: linear-gradient(135deg, #E6A23C, #F56C6C);
  bottom: -80px; right: -80px;
  animation-delay: -6s;
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
  max-width: 480px;
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
  animation: emoji-wave 2s ease-in-out infinite;
  display: inline-block;
}
@keyframes emoji-wave {
  0%, 100% { transform: rotate(0deg); }
  25% { transform: rotate(-15deg); }
  75% { transform: rotate(15deg); }
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
.welcome-actions {
  display: flex; gap: 12px;
  justify-content: center;
  margin-bottom: 24px;
}
.welcome-stats {
  display: flex; align-items: center;
  justify-content: space-around;
  padding: 16px 0 0;
  border-top: 1px solid #ebeef5;
}
.stat-item {
  flex: 1;
  text-align: center;
}
.stat-value {
  font-size: 22px; font-weight: 600;
  color: #303133;
  line-height: 1.2;
}
.stat-label {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}
.stat-divider {
  width: 1px; height: 32px;
  background: #ebeef5;
}
.welcome-questions {
  position: relative;
  margin-top: 24px;
  width: 100%;
  max-width: 480px;
}

/* ===== 头部样式增强 ===== */
.title-icon {
  margin-right: 4px;
  filter: drop-shadow(0 2px 4px rgba(64, 158, 255, 0.3));
}
.title-vip {
  margin-left: 8px;
  font-weight: bold;
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
.side-btn {
  background: linear-gradient(135deg, #409EFF, #67C23A);
  border: none;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.3);
  transition: transform 0.2s;
}
.side-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(64, 158, 255, 0.4);
}
.skill-group { display: flex; flex-wrap: wrap; }
.drawer-content p { margin: 8px 0; font-size: 14px; color: #606266; }
</style>