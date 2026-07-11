<script setup>
/**
 * SmartReplySuggestions.vue - 智能回复推荐 (坐席端).
 * ----------------------------------------------------------------------------
 * 根据客户最新一条消息, 用 Java 自研 AI 给出 3 条候选回复.
 * 优先调后端 /api/ai/faq, 不可达时降级为本地关键词匹配.
 *
 * 数据流: 客户消息 → watch → 调 aiApi.faq() → 显示候选
 *         坐席点击 → emit('pick') → Agent.vue 填入输入框
 */
import { ref, watch } from 'vue'
import { aiApi } from '@/api/ai'

const props = defineProps({
  /** 客户最新消息 */
  lastUserText: { type: String, default: '' },
})

const emit = defineEmits(['pick'])

/** 本地兜底 FAQ (后端不可达时使用) */
const FAQ_LOCAL = [
  { kw: ['退款', '退钱', '退货'], a: '请提供订单号, 我帮您发起退款 (1-3 工作日原路返回).' },
  { kw: ['物流', '快递', '发货', '到哪'], a: '物流查询: 我帮您查一下, 请提供订单号.' },
  { kw: ['支付', '付款', '扣款'], a: '支付失败常见原因: 1) 限额 2) 网络 3) 重复扣款. 需要我帮您查吗?' },
  { kw: ['价格', '多少钱', '优惠', '折扣'], a: '普通会员 9.5 折, 银卡 9 折, 金卡 8.5 折, 钻石 8 折. 节日有额外优惠.' },
  { kw: ['登录', '登不上', '密码'], a: '登录问题: 1) 密码错误点忘记密码 2) 账号锁定 30 分钟 3) 收不到验证码查垃圾箱.' },
  { kw: ['投诉', '举报'], a: '非常抱歉给您带来不便, 我马上为您升级到主管处理.' },
  { kw: ['人工', '真人'], a: '好的, 我现在就是人工客服, 请问您具体什么问题?' },
  { kw: ['你好', '在吗', 'hello', 'hi'], a: '您好! 请问您具体遇到什么问题?' },
  { kw: ['谢谢', '感谢'], a: '不客气! 还需要其他帮助吗?' },
  { kw: ['再见', 'bye'], a: '感谢您的咨询, 祝您生活愉快!' },
]

const suggestions = ref([])
const loading = ref(false)
const source = ref('local')  // 'remote' / 'local'

watch(() => props.lastUserText, async (t) => {
  if (!t || t.length < 2) { suggestions.value = []; return }
  loading.value = true
  try {
    // 优先调后端 AI
    const r = await aiApi.faq(t, 3)
    if (r && Array.isArray(r.hits) && r.hits.length > 0) {
      suggestions.value = r.hits.map(h => h.answer || h.text).filter(Boolean)
      source.value = 'remote'
    } else {
      suggestions.value = computeLocal(t)
      source.value = 'local'
    }
  } catch (e) {
    // 后端不可达, 用本地
    suggestions.value = computeLocal(t)
    source.value = 'local'
  } finally {
    loading.value = false
  }
}, { immediate: true })

/** 本地关键词匹配 (后端降级) */
function computeLocal(text) {
  const lower = text.toLowerCase()
  const scored = []
  for (const item of FAQ_LOCAL) {
    let hits = 0
    for (const kw of item.kw) {
      if (lower.includes(kw.toLowerCase())) hits++
    }
    if (hits > 0) scored.push({ answer: item.a, score: hits })
  }
  scored.sort((a, b) => b.score - a.score)
  const seen = new Set()
  const out = []
  for (const s of scored) {
    if (!seen.has(s.answer)) {
      seen.add(s.answer)
      out.push(s.answer)
    }
    if (out.length >= 3) break
  }
  if (out.length === 0) {
    out.push('请问您具体遇到什么问题?')
    out.push('我帮您查一下, 请稍等.')
    out.push('正在为您核实, 请稍后.')
  }
  return out
}
</script>

<template>
  <div v-if="suggestions.length > 0" class="smart-replies">
    <div class="sr-title">
      <span class="sr-icon">💡</span>
      <span>AI 推荐回复</span>
      <span class="sr-source">{{ source === 'remote' ? '☁️ 后端AI' : '📦 本地' }}</span>
    </div>
    <div class="sr-list">
      <div v-for="(s, i) in suggestions" :key="i"
           class="sr-chip" :style="{ animationDelay: `${i * 0.08}s` }"
           @click="emit('pick', s)">
        {{ s }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.smart-replies {
  padding: 6px 12px;
  background: #f0f9ff;
  border-bottom: 1px solid #e1f5fe;
}
.sr-title {
  display: flex; align-items: center; gap: 4px;
  font-size: 11px; color: #1976d2;
  margin-bottom: 4px;
}
.sr-icon { font-size: 14px; }
.sr-source {
  margin-left: auto;
  font-size: 10px;
  color: #909399;
  background: rgba(255, 255, 255, 0.6);
  padding: 1px 6px;
  border-radius: 8px;
}
.sr-list {
  display: flex; gap: 6px; flex-wrap: wrap;
}
.sr-chip {
  padding: 4px 10px;
  background: #fff;
  border: 1px solid #4fc3f7;
  color: #1976d2;
  border-radius: 12px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  animation: sr-in 0.3s backwards;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sr-chip:hover {
  background: #4fc3f7;
  color: #fff;
  transform: translateY(-1px);
}
@keyframes sr-in {
  from { opacity: 0; transform: translateX(-10px); }
  to { opacity: 1; transform: translateX(0); }
}
</style>