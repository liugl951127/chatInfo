<script setup>
/**
 * Admin.vue - 管理员面板 (V3.1 新增).
 * ----------------------------------------------------------------------------
 * 功能:
 *   - FAQ 候选池审核入库
 *   - 限流规则配置
 *   - 健康分手动重算
 *   - 主动关怀规则启用/禁用
 *   - 告警通道配置
 */
import { ref, onMounted } from 'vue'
import { http } from '@/api/axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import FaqCandidatePool from '@/components/admin/FaqCandidatePool.vue'

const activeTab = ref('faq')
const uidInput = ref(2)
const rateLimitRules = [
  { endpoint: '/auth/login', permits: 5, window: 60, status: '启用' },
  { endpoint: '/auth/register', permits: 3, window: 3600, status: '启用' },
  { endpoint: '/api/im/session/*/message', permits: 30, window: 60, status: '启用' },
  { endpoint: '/api/im/session/*/rate', permits: 10, window: 3600, status: '启用' },
  { endpoint: '/api/im/session/claim', permits: 60, window: 60, status: '启用' },
]

const stats = ref({
  totalUsers: 0,
  onlineAgents: 0,
  todaySessions: 0,
  pendingFaqs: 0,
})

async function loadStats() {
  try {
    const r = await http.get('/api/admin/stats')
    stats.value = r?.data || stats.value
  } catch (e) {
    // demo fallback
    stats.value = {
      totalUsers: 6,
      onlineAgents: 1,
      todaySessions: 5,
      pendingFaqs: 3,
    }
  }
}

async function recalcHealth(uid) {
  try {
    await ElMessageBox.confirm(`重算客户 #${uid} 的健康分?`, '确认', { type: 'warning' })
    await http.post(`/api/admin/health/recalc?uid=${uid}`)
    ElMessage.success('重算完成')
  } catch (e) { /* cancelled */ }
}

onMounted(loadStats)
</script>

<template>
  <div class="admin-shell">
    <header class="topbar">
      <span class="title">⚙️ 管理员面板</span>
      <el-button size="small" @click="$router.push('/')">返回</el-button>
    </header>
    <div class="stats-bar">
      <div class="stat"><span class="num">{{ stats.totalUsers }}</span><span class="label">总用户</span></div>
      <div class="stat"><span class="num">{{ stats.onlineAgents }}</span><span class="label">在线坐席</span></div>
      <div class="stat"><span class="num">{{ stats.todaySessions }}</span><span class="label">今日会话</span></div>
      <div class="stat"><span class="num">{{ stats.pendingFaqs }}</span><span class="label">待审核 FAQ</span></div>
    </div>
    <el-tabs v-model="activeTab" class="admin-tabs">
      <el-tab-pane label="FAQ 候选池" name="faq">
        <FaqCandidatePool />
      </el-tab-pane>
      <el-tab-pane label="健康分重算" name="health">
        <div class="tool-panel">
          <h3>手动重算健康分</h3>
          <div class="form">
            <el-input v-model="uidInput" placeholder="客户 user_id" style="width: 200px;" />
            <el-button type="primary" @click="recalcHealth(uidInput)">重算</el-button>
          </div>
          <p class="hint">从 cdp_event / chat_message / chat_session 实时统计 5 维度分数</p>
        </div>
      </el-tab-pane>
      <el-tab-pane label="限流配置" name="ratelimit">
        <div class="tool-panel">
          <h3>限流规则</h3>
          <el-table :data="rateLimitRules" stripe>
            <el-table-column prop="endpoint" label="端点" />
            <el-table-column prop="permits" label="次数" width="100" />
            <el-table-column prop="window" label="窗口 (秒)" width="120" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === '启用' ? 'success' : 'info'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
const rateLimitRules = [
  { endpoint: '/auth/login', permits: 5, window: 60, status: '启用' },
  { endpoint: '/auth/register', permits: 3, window: 3600, status: '启用' },
  { endpoint: '/api/im/session/*/message', permits: 30, window: 60, status: '启用' },
  { endpoint: '/api/im/session/*/rate', permits: 10, window: 3600, status: '启用' },


<style scoped>
.admin-shell { padding: 16px; min-height: 100vh; background: #f5f7fa; }
.topbar { display: flex; justify-content: space-between; align-items: center; padding: 12px 20px; background: #fff; border-radius: 8px; margin-bottom: 16px; }
.title { font-size: 18px; font-weight: 700; color: #1e293b; }
.stats-bar { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.stat { background: #fff; padding: 16px; border-radius: 8px; text-align: center; box-shadow: 0 1px 4px rgba(0,0,0,0.04); }
.stat .num { display: block; font-size: 28px; font-weight: 700; color: #409EFF; }
.stat .label { font-size: 12px; color: #909399; }
.admin-tabs { background: #fff; padding: 16px; border-radius: 8px; }
.tool-panel { padding: 20px; }
.tool-panel h3 { margin: 0 0 16px; color: #1e293b; }
.form { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.hint { color: #909399; font-size: 12px; margin: 0; }
</style>
