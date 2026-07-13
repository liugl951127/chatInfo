<script setup>
/**
 * ProfileCenter.vue - 数字孪生 360 个人中心.
 * ----------------------------------------------------------------------------
 * v6 客户体验: 重做个人中心, 展示画像数据.
 *
 * Props:
 *   - profile: 360 画像 (含 vip/health/tags/stats)
 *   - loading: 是否加载中
 */
import { computed } from 'vue'

const props = defineProps({
  profile: { type: Object, default: () => ({ avatarUrl: '', nickname: '', vipLevel: 0, vipLabel: '', userId: 0, healthScore: 100, tags: {} }) },
  loading: { type: Boolean, default: false },
})

defineEmits(['refresh'])

// 健康分颜色
const healthColor = computed(() => {
  const s = props.profile?.healthScore ?? 100
  if (s >= 80) return '#67C23A'   // 绿
  if (s >= 60) return '#409EFF'   // 蓝
  if (s >= 40) return '#E6A23C'   // 黄
  return '#F56C6C'                 // 红
})

// 标签转 chip 列表
const tagChips = computed(() => {
  const tags = props.profile?.tags || {}
  return Object.entries(tags).map(([k, v]) => ({
    key: k, value: v,
    label: k.replace(/_/g, ' '),
  }))
})
</script>

<template>
  <div class="profile-center" v-loading="loading">
    <!-- 头部: 头像 + 昵称 + VIP -->
    <div class="pc-header">
      <el-avatar :size="72" :src="profile?.avatarUrl" class="pc-avatar">
        {{ profile?.nickname?.charAt(0) || '?' }}
      </el-avatar>
      <div class="pc-info">
        <div class="pc-name-row">
          <span class="pc-name">{{ profile?.nickname || '客户' }}</span>
          <el-tag v-if="profile?.vipLevel > 0" type="warning" size="small" class="pc-vip">
            {{ profile?.vipLabel }}
          </el-tag>
        </div>
        <div class="pc-id">ID: {{ profile?.userId }}</div>
      </div>
    </div>

    <!-- 健康分仪表 -->
    <div class="pc-health" :style="{ borderColor: healthColor }">
      <div class="pc-health-label">客户健康分</div>
      <div class="pc-health-score" :style="{ color: healthColor }">
        {{ profile?.healthScore ?? 100 }}
        <span class="pc-health-max">/ 100</span>
      </div>
      <div class="pc-health-tier">{{ profile?.healthLabel || '健康' }}</div>
      <el-progress :percentage="profile?.healthScore ?? 100" :stroke-width="6"
                   :color="healthColor" :show-text="false" />
    </div>

    <!-- 4 个数据卡片 -->
    <div class="pc-stats">
      <div class="pc-stat">
        <div class="pc-stat-value">{{ profile?.totalOrders ?? 0 }}</div>
        <div class="pc-stat-label">历史订单</div>
      </div>
      <div class="pc-stat">
        <div class="pc-stat-value">¥{{ profile?.totalAmount ?? 0 }}</div>
        <div class="pc-stat-label">累计消费</div>
      </div>
      <div class="pc-stat">
        <div class="pc-stat-value">{{ profile?.avgCsat ?? '-' }}</div>
        <div class="pc-stat-label">平均满意度</div>
      </div>
      <div class="pc-stat">
        <div class="pc-stat-value">{{ profile?.totalSessions ?? 0 }}</div>
        <div class="pc-stat-label">咨询次数</div>
      </div>
    </div>

    <!-- 风险标签 -->
    <div class="pc-risk" v-if="profile?.churnRisk > 0">
      <el-alert :title="`客户状态: ${profile?.churnLabel}`" type="warning" :closable="false" show-icon />
    </div>

    <!-- 标签云 -->
    <div class="pc-tags">
      <div class="pc-section-title">我的标签 ({{ tagChips.length }})</div>
      <div class="pc-tag-list">
        <el-tag v-for="t in tagChips" :key="t.key" size="small" effect="plain" round class="pc-tag">
          {{ t.label }}
        </el-tag>
        <div v-if="tagChips.length === 0" class="pc-tag-empty">暂无标签</div>
      </div>
    </div>

    <!-- 刷新 -->
    <div class="pc-refresh" @click="$emit('refresh')">
      <el-icon><Refresh /></el-icon> 刷新画像
    </div>
  </div>
</template>

<style scoped>
.profile-center {
  padding: 20px 16px;
  max-width: 480px;
  margin: 0 auto;
}
.pc-header {
  display: flex; gap: 16px; align-items: center;
  padding-bottom: 16px;
  border-bottom: 1px solid #ebeef5;
}
.pc-avatar {
  background: linear-gradient(135deg, #409EFF, #67C23A);
  color: #fff;
  font-size: 28px;
  font-weight: bold;
}
.pc-info { flex: 1; }
.pc-name-row {
  display: flex; align-items: center; gap: 8px;
  margin-bottom: 4px;
}
.pc-name {
  font-size: 18px; font-weight: 600; color: #303133;
}
.pc-vip { font-weight: bold; }
.pc-id { font-size: 12px; color: #909399; }

.pc-health {
  margin: 16px 0;
  padding: 16px;
  border: 2px solid;
  border-radius: 12px;
  text-align: center;
  background: rgba(64, 158, 255, 0.05);
}
.pc-health-label { font-size: 13px; color: #909399; }
.pc-health-score {
  font-size: 36px; font-weight: bold; line-height: 1.2;
  margin: 4px 0;
}
.pc-health-max { font-size: 14px; color: #909399; }
.pc-health-tier {
  font-size: 12px; color: #606266; margin-bottom: 8px;
}

.pc-stats {
  display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 8px; margin: 16px 0;
}
.pc-stat {
  text-align: center;
  padding: 12px 4px;
  background: #f5f7fa;
  border-radius: 8px;
}
.pc-stat-value {
  font-size: 18px; font-weight: 600; color: #303133;
  white-space: nowrap; overflow: hidden;
}
.pc-stat-label { font-size: 11px; color: #909399; margin-top: 2px; }

.pc-risk { margin: 12px 0; }
.pc-section-title {
  font-size: 14px; font-weight: 600; color: #303133;
  margin-bottom: 8px;
}
.pc-tag-list {
  display: flex; flex-wrap: wrap; gap: 6px;
}
.pc-tag { font-size: 11px; }
.pc-tag-empty {
  color: #c0c4cc; font-size: 12px;
  padding: 8px 0;
}
.pc-refresh {
  text-align: center;
  padding: 12px;
  color: #909399; font-size: 13px;
  cursor: pointer;
  margin-top: 16px;
}
.pc-refresh:hover { color: #409EFF; }
</style>