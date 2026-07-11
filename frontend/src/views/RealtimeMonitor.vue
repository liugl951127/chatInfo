<script setup>
/**
 * RealtimeMonitor.vue - 实时监控大屏.
 * ----------------------------------------------------------------------------
 * 监控指标:
 *   - 今日总会话 / 活跃会话 / 等候队列 / 已接通 / 接通率
 *   - 满意度 (CSAT 4 项分布 + 平均分)
 *   - 活跃坐席 / 消息吞吐
 *   - 7 天趋势 (24h 分布)
 *
 * 实时策略: 5 秒 polling 拉取. 阶段 2: SSE / WebSocket 推送.
 * 数据源: GET /api/success/realtime
 */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { Client } from '@stomp/stompjs'
import { realtimeApi } from '@/api/realtime'
import { useUserStore } from '@/stores/user'
import { Connection, ChatDotRound, Timer, Star, User, Bell, Refresh, DataLine } from '@element-plus/icons-vue'

const userStore = useUserStore()
const stats = ref(null)
const loading = ref(false)
const lastUpdate = ref(null)
const isLive = ref(true)
const eventLog = ref([])  // 最近事件
let timer = null
let stompClient = null

// ============== STOMP 实时推送 ==============
function connectStomp() {
  try {
    const apiBase = import.meta.env.VITE_API_BASE || '/api'
    const wsUrl = apiBase.replace('/api', '') + '/ws/agent'
    stompClient = new Client({
      webSocketFactory: () => new WebSocket(wsUrl),
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      connectHeaders: { Authorization: 'Bearer ' + (userStore.token || '') },
    })
    stompClient.onConnect = () => {
      stompClient.subscribe('/topic/realtime', (msg) => {
        try {
          const evt = JSON.parse(msg.body)
          // 记录最近 20 个事件
          eventLog.value.unshift({ ...evt, time: new Date().toLocaleTimeString('zh-CN') })
          if (eventLog.value.length > 20) eventLog.value.pop()
          // 触发刷新
          fetchData()
        } catch (e) { console.error('[realtime] parse failed', e) }
      })
    }
    stompClient.onStompError = (f) => { console.error('[realtime] STOMP error', f) }
    stompClient.activate()
  } catch (e) {
    console.error('[realtime] STOMP connect failed, fallback to polling', e)
  }
}

function disconnectStomp() {
  if (stompClient) { stompClient.deactivate(); stompClient = null }
}

const fetchData = async () => {
  if (!isLive.value) return
  loading.value = true
  try {
    const r = await realtimeApi.getStats()
    stats.value = r.data
    lastUpdate.value = new Date()
  } catch (e) {
    console.warn('[realtime] fetch failed', e.message)
  } finally {
    loading.value = false
  }
}

const startTimer = () => {
  if (timer) return
  fetchData()
  timer = setInterval(fetchData, 5000)  // polling 兜底 (5s)
  connectStomp()  // STOMP 实时推送
}

const stopTimer = () => {
  if (timer) { clearInterval(timer); timer = null }
  disconnectStomp()
}

const toggle = () => {
  isLive.value = !isLive.value
  if (isLive.value) startTimer()
  else stopTimer()
}

onMounted(startTimer)
onUnmounted(() => {
  stopTimer()
  disconnectStomp()
})

// ============== 计算属性 (大数字) ==============
const todaySessions = computed(() => stats.value?.todaySessions ?? 0)
const activeSessions = computed(() => stats.value?.activeSessions ?? 0)
const waitingQueue = computed(() => stats.value?.waitingQueue ?? 0)
const answeredToday = computed(() => stats.value?.answeredToday ?? 0)
const answerRate = computed(() => {
  const v = stats.value?.answerRate ?? 0
  return (v * 100).toFixed(1) + '%'
})
const avgCsat = computed(() => {
  const v = stats.value?.avgCsat ?? 0
  return v.toFixed(2)
})
const activeAgents = computed(() => stats.value?.activeAgents ?? 0)
const msgsPerMin = computed(() => stats.value?.msgsPerMin ?? 0)
const csatBars = computed(() => {
  if (!stats.value) return []
  return [
    { label: '5⭐ 满意', value: stats.value.csat5Rate * 100, color: '#67C23A' },
    { label: '4⭐ 良好', value: stats.value.csat4Rate * 100, color: '#409EFF' },
    { label: '3⭐ 一般', value: stats.value.csat3Rate * 100, color: '#E6A23C' },
    { label: '<3 差评', value: stats.value.csatLowerRate * 100, color: '#F56C6C' },
  ]
})
const lastUpdateText = computed(() => {
  if (!lastUpdate.value) return '加载中...'
  return lastUpdate.value.toLocaleTimeString('zh-CN')
})
</script>

<template>
  <div class="monitor-page" v-loading="loading">
    <!-- 顶部 -->
    <header class="mon-header">
      <div class="mon-title">
        <span class="title-icon">📊</span>
        实时监控大屏
        <el-tag v-if="isLive" type="success" size="small" effect="dark" class="live-tag">
          <span class="live-dot"></span>LIVE
        </el-tag>
        <el-tag v-else type="info" size="small" effect="dark">PAUSED</el-tag>
      </div>
      <div class="mon-meta">
        <span class="meta-item">⏱️ {{ lastUpdateText }}</span>
        <el-button size="small" :icon="Refresh" @click="fetchData">刷新</el-button>
        <el-button size="small" :type="isLive ? 'warning' : 'success'" @click="toggle">
          {{ isLive ? '暂停' : '恢复' }}
        </el-button>
      </div>
    </header>

    <!-- 实时事件流 -->
      <div v-if="eventLog.length > 0" class="event-stream">
        <div class="es-title">📡 实时事件流 (最近 {{ eventLog.length }} 条)</div>
        <div class="es-list">
          <div v-for="(e, i) in eventLog" :key="i" class="es-item">
            <span class="es-time">{{ e.time }}</span>
            <span class="es-type" :class="'es-' + e.event">{{ e.event }}</span>
            <span v-if="e.data" class="es-data">{{ JSON.stringify(e.data) }}</span>
          </div>
        </div>
      </div>

      <!-- 4 个核心指标卡 -->
    <div class="metric-row">
      <div class="metric-card primary">
        <div class="mc-icon"><el-icon><ChatDotRound /></el-icon></div>
        <div class="mc-body">
          <div class="mc-label">今日会话</div>
          <div class="mc-value">{{ todaySessions }}</div>
          <div class="mc-sub">实时累计</div>
        </div>
      </div>
      <div class="metric-card success">
        <div class="mc-icon"><el-icon><Connection /></el-icon></div>
        <div class="mc-body">
          <div class="mc-label">已接通</div>
          <div class="mc-value">{{ answeredToday }}</div>
          <div class="mc-sub">接通率 {{ answerRate }}</div>
        </div>
      </div>
      <div class="metric-card warning">
        <div class="mc-icon"><el-icon><Timer /></el-icon></div>
        <div class="mc-body">
          <div class="mc-label">活跃中</div>
          <div class="mc-value pulse">{{ activeSessions }}</div>
          <div class="mc-sub">等候 {{ waitingQueue }}</div>
        </div>
      </div>
      <div class="metric-card danger">
        <div class="mc-icon"><el-icon><Star /></el-icon></div>
        <div class="mc-body">
          <div class="mc-label">满意度</div>
          <div class="mc-value">{{ avgCsat }}</div>
          <div class="mc-sub">满分 5.0</div>
        </div>
      </div>
    </div>

    <!-- 详细指标 -->
    <div class="detail-row">
      <!-- 满意度分布 -->
      <div class="detail-card">
        <div class="dc-title">
          <el-icon><Star /></el-icon>
          满意度分布 (CSAT)
        </div>
        <div class="csat-bars">
          <div v-for="b in csatBars" :key="b.label" class="csat-bar">
            <div class="cb-label">{{ b.label }}</div>
            <div class="cb-track">
              <div class="cb-fill" :style="{ width: b.value + '%', background: b.color }">
                <span class="cb-text">{{ b.value.toFixed(1) }}%</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 实时吞吐 -->
      <div class="detail-card">
        <div class="dc-title">
          <el-icon><DataLine /></el-icon>
          实时吞吐
        </div>
        <div class="throughput-grid">
          <div class="tp-cell">
            <div class="tp-icon"><el-icon><User /></el-icon></div>
            <div>
              <div class="tp-label">在线坐席</div>
              <div class="tp-value">{{ activeAgents }}</div>
            </div>
          </div>
          <div class="tp-cell">
            <div class="tp-icon"><el-icon><Bell /></el-icon></div>
            <div>
              <div class="tp-label">消息/分</div>
              <div class="tp-value">{{ msgsPerMin }}</div>
            </div>
          </div>
          <div class="tp-cell">
            <div class="tp-icon"><el-icon><ChatDotRound /></el-icon></div>
            <div>
              <div class="tp-label">活跃会话</div>
              <div class="tp-value">{{ activeSessions }}</div>
            </div>
          </div>
          <div class="tp-cell">
            <div class="tp-icon"><el-icon><Timer /></el-icon></div>
            <div>
              <div class="tp-label">等候队列</div>
              <div class="tp-value pulse">{{ waitingQueue }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 24h 趋势 -->
      <div class="detail-card">
        <div class="dc-title">
          <el-icon><DataLine /></el-icon>
          7 天会话量趋势
        </div>
        <div class="trend-chart">
          <div v-for="(d, i) in (stats?.hourDistribution || [])" :key="i" class="bar-col">
            <div class="bar-value">{{ d.count }}</div>
            <div class="bar" :style="{ height: (d.count * 1.5) + 'px' }"></div>
            <div class="bar-label">{{ d.date }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.monitor-page {
  min-height: 100vh;
  padding: 16px;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
  color: #fff;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

/* 头部 */
.mon-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 20px;
  padding: 14px 24px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.1);
}
.mon-title {
  font-size: 22px; font-weight: 600;
  display: flex; align-items: center; gap: 10px;
}
.title-icon { font-size: 28px; }
.live-tag { display: flex; align-items: center; gap: 4px; }
.live-dot {
  display: inline-block;
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #fff;
  animation: live-pulse 1.5s infinite;
}
@keyframes live-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(1.3); }
}
.mon-meta { display: flex; align-items: center; gap: 12px; }
.meta-item { color: #94a3b8; font-size: 13px; }

/* 4 大指标 */
.metric-row {
  display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 16px; margin-bottom: 16px;
}
@media (max-width: 900px) {
  .metric-row { grid-template-columns: repeat(2, 1fr); }
}
.metric-card {
  display: flex; align-items: center; gap: 16px;
  padding: 20px;
  background: rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(10px);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  transition: all 0.2s;
}
.metric-card:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3); }
.metric-card.primary { border-left: 4px solid #409EFF; }
.metric-card.success { border-left: 4px solid #67C23A; }
.metric-card.warning { border-left: 4px solid #E6A23C; }
.metric-card.danger  { border-left: 4px solid #F56C6C; }

.mc-icon {
  width: 56px; height: 56px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  font-size: 28px;
  color: #fff;
}
.metric-card.primary .mc-icon { background: linear-gradient(135deg, #409EFF, #5fa7ff); }
.metric-card.success .mc-icon { background: linear-gradient(135deg, #67C23A, #85ce61); }
.metric-card.warning .mc-icon { background: linear-gradient(135deg, #E6A23C, #ebb563); }
.metric-card.danger  .mc-icon { background: linear-gradient(135deg, #F56C6C, #f89898); }

.mc-label { font-size: 13px; color: #94a3b8; }
.mc-value {
  font-size: 36px; font-weight: 700;
  line-height: 1.2;
  font-feature-settings: "tnum";
  color: #fff;
}
.mc-value.pulse { animation: number-pulse 1.5s infinite; }
@keyframes number-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; text-shadow: 0 0 12px currentColor; }
}
.mc-sub { font-size: 11px; color: #64748b; margin-top: 2px; }

/* 详细行 */
.detail-row {
  display: grid; grid-template-columns: 1fr 1fr 1.5fr;
  gap: 16px;
}
@media (max-width: 1100px) {
  .detail-row { grid-template-columns: 1fr; }
}
.detail-card {
  background: rgba(255, 255, 255, 0.06);
  backdrop-filter: blur(10px);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 16px;
}
.dc-title {
  display: flex; align-items: center; gap: 6px;
  font-size: 14px; font-weight: 600;
  color: #cbd5e1;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

/* 满意度条 */
.csat-bars { display: flex; flex-direction: column; gap: 10px; }
.cb-label { font-size: 12px; color: #94a3b8; margin-bottom: 4px; }
.cb-track {
  height: 22px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 11px;
  overflow: hidden;
}
.cb-fill {
  height: 100%;
  border-radius: 11px;
  display: flex; align-items: center; justify-content: flex-end;
  padding-right: 8px;
  transition: width 0.6s ease;
  min-width: 5%;
}
.cb-text { font-size: 11px; font-weight: 600; color: #fff; }

/* 实时吞吐 */
.throughput-grid {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.tp-cell {
  display: flex; align-items: center; gap: 10px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;
}
.tp-icon {
  width: 36px; height: 36px;
  border-radius: 8px;
  background: linear-gradient(135deg, #409EFF, #67C23A);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px;
}
.tp-label { font-size: 11px; color: #94a3b8; }
.tp-value {
  font-size: 20px; font-weight: 700; color: #fff;
  font-feature-settings: "tnum";
}
.tp-value.pulse { animation: number-pulse 1.5s infinite; }

/* 趋势图 */
.trend-chart {
  display: flex; align-items: flex-end; justify-content: space-around;
  height: 180px;
  padding-top: 24px;
}
.bar-col {
  display: flex; flex-direction: column;
  align-items: center;
  flex: 1;
  height: 100%;
  position: relative;
}
.bar-value {
  font-size: 11px; color: #94a3b8;
  margin-bottom: 4px;
}
.bar {
  width: 60%; min-height: 4px;
  background: linear-gradient(180deg, #409EFF, #67C23A);
  border-radius: 4px 4px 0 0;
  transition: height 0.6s ease;
}
.bar:hover {
  background: linear-gradient(180deg, #67C23A, #409EFF);
}
.bar-label {
  font-size: 11px; color: #64748b;
  margin-top: 4px;
}
</style>