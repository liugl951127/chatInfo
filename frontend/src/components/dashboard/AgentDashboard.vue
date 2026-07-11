<script setup>
/**
 * AgentDashboard.vue - 坐席统计仪表盘.
 * ----------------------------------------------------------------------------
 * v6 新增: 坐席侧的统计 dashboard, 用于坐席:
 *   - 看自己今日工作量
 *   - 看响应时长 / 满意度
 *   - 看趋势
 *
 * 阶段 1 用静态 mock 数据, 阶段 2 调后端 API.
 */
import { ref, onMounted } from 'vue'
import { ChatDotRound, Timer, Star, Trophy, TrendCharts, DataAnalysis } from '@element-plus/icons-vue'

const stats = ref([
  { icon: ChatDotRound, label: '今日会话', value: '24', trend: '+12%', color: '#409EFF' },
  { icon: Timer, label: '平均响应', value: '18s', trend: '-3s', color: '#67C23A' },
  { icon: Star, label: '客户满意度', value: '4.8/5', trend: '+0.2', color: '#E6A23C' },
  { icon: Trophy, label: '本月接单', value: '326', trend: '+8%', color: '#F56C6C' },
])

/** 7 天趋势 (mock) */
const trend = ref([
  { day: '周一', count: 32 },
  { day: '周二', count: 28 },
  { day: '周三', count: 35 },
  { day: '周四', count: 41 },
  { day: '周五', count: 38 },
  { day: '周六', count: 22 },
  { day: '周日', count: 18 },
])

const maxCount = Math.max(...trend.value.map(t => t.count), 1)

/** 能力评分 (基于本地 AI 推荐命中率等) */
const skills = ref([
  { name: '退款处理', score: 92, level: 'expert' },
  { name: '订单查询', score: 88, level: 'expert' },
  { name: '投诉处理', score: 76, level: 'advanced' },
  { name: '建议反馈', score: 64, level: 'intermediate' },
])

onMounted(() => {
  // 阶段 2: 调 /api/success/agent-stats
})
</script>

<template>
  <div class="agent-dashboard">
    <div class="dash-header">
      <h3><el-icon><DataAnalysis /></el-icon> 数据看板</h3>
      <span class="dash-sub">今日表现</span>
    </div>

    <!-- 4 个统计卡 -->
    <div class="stats-grid">
      <div v-for="(s, i) in stats" :key="i" class="stat-card" :style="{ '--c': s.color }">
        <div class="stat-icon"><el-icon><component :is="s.icon" /></el-icon></div>
        <div class="stat-body">
          <div class="stat-label">{{ s.label }}</div>
          <div class="stat-value">{{ s.value }}</div>
          <div class="stat-trend" :class="{ up: s.trend.startsWith('+'), down: s.trend.startsWith('-') }">
            {{ s.trend }} vs 昨日
          </div>
        </div>
      </div>
    </div>

    <!-- 趋势图 + 能力评分 -->
    <div class="charts-row">
      <!-- 7 天柱状图 -->
      <div class="chart-card">
        <div class="chart-title"><el-icon><TrendCharts /></el-icon> 7 天会话量</div>
        <div class="bar-chart">
          <div v-for="t in trend" :key="t.day" class="bar-col">
            <div class="bar-value">{{ t.count }}</div>
            <div class="bar" :style="{ height: (t.count / maxCount * 100) + '%' }"></div>
            <div class="bar-label">{{ t.day }}</div>
          </div>
        </div>
      </div>

      <!-- 能力评分 -->
      <div class="chart-card">
        <div class="chart-title">能力雷达</div>
        <div class="skills-list">
          <div v-for="sk in skills" :key="sk.name" class="skill-item">
            <div class="skill-header">
              <span class="skill-name">{{ sk.name }}</span>
              <el-tag size="small" :type="sk.level === 'expert' ? 'success' : (sk.level === 'advanced' ? 'warning' : 'info')">
                {{ sk.level === 'expert' ? '专家' : (sk.level === 'advanced' ? '熟练' : '进阶') }}
              </el-tag>
            </div>
            <div class="skill-bar">
              <div class="skill-fill" :style="{ width: sk.score + '%' }"></div>
            </div>
            <div class="skill-score">{{ sk.score }} / 100</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-dashboard {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px;
}
.dash-header {
  display: flex; align-items: baseline; gap: 12px;
  margin-bottom: 16px;
}
.dash-header h3 {
  margin: 0;
  display: flex; align-items: center; gap: 6px;
  font-size: 18px;
}
.dash-sub {
  font-size: 12px;
  color: #909399;
}

/* 4 统计卡 */
.stats-grid {
  display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
@media (max-width: 768px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
}
.stat-card {
  display: flex; align-items: center; gap: 12px;
  padding: 16px;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #ebeef5;
  border-left: 4px solid var(--c);
  transition: all 0.2s;
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}
.stat-icon {
  width: 48px; height: 48px;
  border-radius: 12px;
  background: var(--c);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 24px;
  flex-shrink: 0;
}
.stat-label {
  font-size: 12px; color: #909399;
}
.stat-value {
  font-size: 22px; font-weight: 700;
  color: #303133;
  line-height: 1.2;
  font-feature-settings: "tnum";
}
.stat-trend {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}
.stat-trend.up   { color: #F56C6C; }
.stat-trend.down { color: #67C23A; }

/* 图表行 */
.charts-row {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 768px) {
  .charts-row { grid-template-columns: 1fr; }
}
.chart-card {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #ebeef5;
  padding: 16px;
}
.chart-title {
  display: flex; align-items: center; gap: 6px;
  font-size: 14px; font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
}

/* 柱状图 */
.bar-chart {
  display: flex; align-items: flex-end; justify-content: space-around;
  height: 160px;
  padding: 8px 0;
}
.bar-col {
  flex: 1;
  display: flex; flex-direction: column;
  align-items: center;
  height: 100%;
  position: relative;
}
.bar-value {
  font-size: 11px; font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
}
.bar {
  width: 60%;
  min-height: 4px;
  background: linear-gradient(180deg, #409EFF, #67C23A);
  border-radius: 4px 4px 0 0;
  transition: height 0.5s ease;
  position: relative;
}
.bar:hover {
  background: linear-gradient(180deg, #67C23A, #409EFF);
  transform: scaleY(1.05);
}
.bar-label {
  font-size: 11px; color: #909399;
  margin-top: 4px;
}

/* 能力评分 */
.skills-list {
  display: flex; flex-direction: column;
  gap: 12px;
}
.skill-header {
  display: flex; justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
.skill-name { font-size: 13px; }
.skill-bar {
  height: 6px;
  background: #f0f2f5;
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 2px;
}
.skill-fill {
  height: 100%;
  background: linear-gradient(90deg, #409EFF, #67C23A);
  border-radius: 3px;
  transition: width 0.6s ease;
}
.skill-score {
  font-size: 11px; color: #909399;
  text-align: right;
}
</style>