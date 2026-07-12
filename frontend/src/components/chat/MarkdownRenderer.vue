<script setup>
/**
 * MarkdownRenderer.vue - Markdown 渲染器 + 嵌套互动按钮.
 * ----------------------------------------------------------------------------
 * 功能:
 *   - 渲染 markdown 为 HTML (markdown-it)
 *   - 在 markdown 中识别 [button:type:label:payload] 语法
 *   - 自动把按钮占位符替换为可交互按钮
 *   - emit action 事件 (transfer / rate / quick / faq / link / copy)
 *
 * 用法:
 *   <MarkdownRenderer :content="msg.content" @action="onAction" />
 *
 * 按钮类型 (V3 业务):
 *   - transfer: 转人工
 *   - rate:     评分 (👍/👎/⭐1-5)
 *   - quick:    快捷问题 (插入输入框)
 *   - faq:      FAQ 跳转
 *   - copy:     复制内容
 *   - link:     外部链接
 *   - action:   通用操作 (payload 为 label)
 */
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { renderMarkdown, isMarkdown } from '@/composables/useMarkdown'
import { ElMessage, ElMessageBox } from 'element-plus'

const props = defineProps({
  content: { type: String, required: true },
  enableButtons: { type: Boolean, default: true },
})
const emit = defineEmits(['action'])

// 渲染结果 (HTML + 按钮列表)
const parsed = computed(() => {
  if (!isMarkdown(props.content)) {
    // 纯文本: 转义后直接显示
    return {
      html: `<p class="md-text">${escapeHtml(props.content)}</p>`,
      buttons: [],
      isMd: false,
    }
  }
  return { ...renderMarkdown(props.content), isMd: true }
})

function escapeHtml(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// 渲染后, 把 <button data-md-btn-idx> 替换为真按钮
const containerRef = ref(null)

function renderButtons() {
  const root = containerRef.value
  if (!root) return
  const placeholders = root.querySelectorAll('[data-md-btn-idx]')
  placeholders.forEach((el) => {
    const idx = parseInt(el.getAttribute('data-md-btn-idx'), 10)
    const btn = parsed.value.buttons[idx]
    if (!btn) return
    el.replaceWith(createButton(btn))
  })
}

function createButton(btn) {
  const el = document.createElement('button')
  el.className = 'md-btn md-btn-' + btn.type
  el.textContent = btn.label
  el.onclick = () => handleAction(btn)
  return el
}

async function handleAction(btn) {
  const { type, label, payload } = btn
  switch (type) {
    case 'transfer':
      emit('action', { type: 'transfer', label, payload })
      break
    case 'rate':
      emit('action', { type: 'rate', label, payload })
      break
    case 'quick':
      // 快捷问题: 插入输入框 + 自动发送
      emit('action', { type: 'quick', label, payload })
      break
    case 'faq':
      emit('action', { type: 'faq', label, payload })
      break
    case 'copy':
      try {
        await navigator.clipboard.writeText(payload)
        ElMessage.success('已复制')
      } catch (e) {
        ElMessage.warning('复制失败 (浏览器不支持)')
      }
      break
    case 'link':
      // 防止 XSS, 校验 url
      if (/^https?:\/\//i.test(payload)) {
        window.open(payload, '_blank', 'noopener')
      } else {
        ElMessage.warning('仅支持 http(s) 链接')
      }
      break
    case 'action':
      emit('action', { type: 'action', label, payload })
      break
    default:
      emit('action', { type, label, payload })
  }
}

watch(() => props.content, async () => {
  await nextTick()
  renderButtons()
})
onMounted(async () => {
  await nextTick()
  renderButtons()
})
</script>

<template>
  <div ref="containerRef" class="md-content" :class="{ 'is-md': parsed.isMd }" v-html="parsed.html" />
</template>

<style scoped>
.md-content { line-height: 1.6; }
.md-content :deep(h1) { font-size: 22px; font-weight: 700; margin: 12px 0 8px; }
.md-content :deep(h2) { font-size: 20px; font-weight: 700; margin: 10px 0 6px; }
.md-content :deep(h3) { font-size: 18px; font-weight: 600; margin: 8px 0 4px; }
.md-content :deep(h4) { font-size: 16px; font-weight: 600; margin: 6px 0 4px; }
.md-content :deep(p) { margin: 6px 0; }
.md-content :deep(ul),
.md-content :deep(ol) { margin: 6px 0; padding-left: 24px; }
.md-content :deep(li) { margin: 2px 0; }
.md-content :deep(a) { color: #409EFF; text-decoration: none; }
.md-content :deep(a:hover) { text-decoration: underline; }
.md-content :deep(blockquote) {
  border-left: 4px solid #dcdfe6;
  padding: 4px 12px;
  margin: 6px 0;
  color: #606266;
  background: #fafafa;
}
.md-content :deep(.md-code) {
  background: #2d3748;
  color: #e2e8f0;
  padding: 12px 16px;
  border-radius: 6px;
  overflow-x: auto;
  font-family: 'Monaco', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  margin: 8px 0;
}
.md-content :deep(.md-code code) {
  background: transparent;
  color: inherit;
  padding: 0;
}
.md-content :deep(.md-text) { margin: 0; }
.md-content :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
}
.md-content :deep(table th),
.md-content :deep(table td) {
  border: 1px solid #ebeef5;
  padding: 6px 12px;
  text-align: left;
}
.md-content :deep(table th) {
  background: #fafafa;
  font-weight: 600;
}
.md-content :deep(img) {
  max-width: 100%;
  border-radius: 4px;
  margin: 4px 0;
}

/* 互动按钮 */
.md-content :deep(.md-btn) {
  display: inline-block;
  margin: 4px 6px 4px 0;
  padding: 6px 14px;
  border: none;
  border-radius: 14px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  font-family: inherit;
  vertical-align: middle;
  line-height: 1.4;
}
.md-content :deep(.md-btn:hover) {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}
.md-content :deep(.md-btn-transfer) { background: #f56c6c; color: #fff; }
.md-content :deep(.md-btn-rate) { background: #e6a23c; color: #fff; }
.md-content :deep(.md-btn-quick) { background: #67c23a; color: #fff; }
.md-content :deep(.md-btn-faq) { background: #409eff; color: #fff; }
.md-content :deep(.md-btn-copy) { background: #909399; color: #fff; }
.md-content :deep(.md-btn-link) { background: #9c27b0; color: #fff; }
.md-content :deep(.md-btn-action) { background: #f0f9ff; color: #409eff; border: 1px solid #b3d8ff; }
</style>
