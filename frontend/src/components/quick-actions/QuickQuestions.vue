<script setup>
/**
 * QuickQuestions.vue - AI 预判快捷问题卡片.
 * ----------------------------------------------------------------------------
 * v6 客户体验: 客户进入会话前, AI 预判 3 个常见问题, 一键发送.
 *
 * Props:
 *   - profile: 客户画像 (用于个性化推荐)
 *
 * Emits:
 *   - pick(question: string)  用户点击某条问题
 */
import { computed } from 'vue'

const props = defineProps({
  profile: { type: Object, default: () => ({}) },
})

defineEmits(['pick'])

/**
 * 根据客户画像生成 3 条预判问题.
 * 阶段 1: 基于标签的简单匹配.
 * 阶段 2: 接 M3 chat 生成更精准的预判.
 */
const questions = computed(() => {
  const tags = props.profile?.tags || {}
  const q = []

  // 高价值客户
  if (tags.high_value) {
    q.push('我的 VIP 优惠有哪些?')
  }
  // 沉默客户
  if (tags.silent_30d || tags.dormant_90d) {
    q.push('最近有什么新功能?')
  }
  // 退货倾向
  if (tags.return_heavy) {
    q.push('我的退货进度')
  }
  // 支付失败
  if (tags.payment_failed) {
    q.push('支付遇到问题怎么办?')
  }
  // 不满意
  if (tags.dissatisfied) {
    q.push('我想反馈问题')
  }

  // 通用兜底
  if (q.length < 3) q.push('查询订单物流')
  if (q.length < 3) q.push('申请退款')
  if (q.length < 3) q.push('联系人工客服')

  return q.slice(0, 3)
})
</script>

<template>
  <div class="quick-questions">
    <div class="qq-title">
      <span class="qq-icon">💡</span>
      <span>猜你想问</span>
    </div>
    <div class="qq-list">
      <div v-for="(q, i) in questions" :key="i"
           class="qq-chip" :style="{ animationDelay: `${i * 0.1}s` }"
           @click="$emit('pick', q)">
        {{ q }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.quick-questions {
  padding: 12px 16px;
  background: linear-gradient(180deg, #f5f7fa, #fff);
  border-bottom: 1px solid #ebeef5;
}
.qq-title {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; color: #909399;
  margin-bottom: 8px;
}
.qq-icon { font-size: 16px; }
.qq-list {
  display: flex; gap: 8px; flex-wrap: wrap;
}
.qq-chip {
  padding: 6px 14px;
  background: #fff;
  border: 1px solid #409EFF;
  color: #409EFF;
  border-radius: 18px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
  animation: qq-fade-in 0.4s backwards;
}
.qq-chip:hover {
  background: #409EFF;
  color: #fff;
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.3);
}
.qq-chip:active {
  transform: scale(0.95);
}
@keyframes qq-fade-in {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>