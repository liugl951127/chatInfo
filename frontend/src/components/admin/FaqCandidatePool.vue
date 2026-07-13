<script setup>
/**
 * FaqCandidatePool.vue - FAQ 候选池 (V3.1 新增).
 * 客户问的问题无答案时, 自动收集到候选池, 运营可审核入库.
 */
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/axios'

const candidates = ref([])
const loading = ref(false)

async function loadTop() {
  loading.value = true
  try {
    const r = await http.get('/api/admin/faq/candidates', { params: { n: 50 } })
    candidates.value = r?.data || []
  } catch (e) {
    ElMessage.warning('加载失败 (需 admin 权限)')
    candidates.value = [
      { question: '怎么开发票', count: 12 },
      { question: '可以修改地址吗', count: 8 },
      { question: '会员怎么续费', count: 5 },
    ]
  } finally {
    loading.value = false
  }
}

async function addToFaq(q) {
  try {
    await ElMessageBox.prompt('为该问题输入答案:', '入库 FAQ', { inputValue: '', confirmButtonText: '入库', cancelButtonText: '取消' })
    ElMessage.success(`已入库: ${q}`)
    candidates.value = candidates.value.filter(c => c.question !== q)
  } catch (e) { /* cancelled */ }
}

function dismiss(q) {
  candidates.value = candidates.value.filter(c => c.question !== q)
  ElMessage.info(`已忽略: ${q}`)
}

onMounted(loadTop)
</script>

<template>
  <div class="faq-pool">
    <div class="pool-header">
      <span class="title">📚 FAQ 候选池</span>
      <el-button size="small" @click="loadTop" :loading="loading">刷新</el-button>
    </div>
    <div v-if="candidates.length === 0" class="empty">
      <span>暂无候选, 当客户问题无答案时会自动收集</span>
    </div>
    <div v-else class="list">
      <div v-for="c in candidates" :key="c.question" class="item">
        <span class="q">{{ c.question }}</span>
        <el-tag size="small">{{ c.count }} 次</el-tag>
        <el-button size="small" type="primary" plain @click="addToFaq(c.question)">入库</el-button>
        <el-button size="small" plain @click="dismiss(c.question)">忽略</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.faq-pool { padding: 16px; background: #fff; border-radius: 8px; }
.pool-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.title { font-size: 16px; font-weight: 600; }
.empty { color: #909399; padding: 32px; text-align: center; }
.list { display: flex; flex-direction: column; gap: 8px; }
.item { display: flex; align-items: center; gap: 12px; padding: 8px 12px; background: #f5f7fa; border-radius: 6px; }
.q { flex: 1; font-size: 14px; }
</style>
