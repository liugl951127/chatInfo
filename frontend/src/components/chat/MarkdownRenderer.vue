<script setup>
/**
 * MarkdownRenderer.vue - Markdown 渲染器 (V3.1 后端渲染版).
 * ----------------------------------------------------------------------------
 * V3.1 关键变更: 后端 MarkdownService 生成 HTML, 前端只 v-html.
 *   - 移除 markdown-it 依赖 (减重 ~50KB)
 *   - 移除客户端解析 (XSS 防护更彻底)
 *   - 按钮点击统一通过 onMdContainerClick 处理
 *
 * 协议:
 *   后端 MarkdownService 把 [button:type:label:payload] 转成:
 *     <button class="md-btn md-btn-{type}" data-type="..." data-label="..." data-payload="...">label</button>
 *
 * 客户端:
 *   - v-html 渲染 HTML
 *   - @click 捕获点击, 通过 composable 派发到父组件
 */
import { onMdContainerClick, isMarkdownHtml } from '@/composables/useMarkdown'

const props = defineProps({
  content: { type: String, required: true },      // 后端返的 HTML
  markdown: { type: String, default: '' },         // 原始 markdown (可选, 用于复制)
})
const emit = defineEmits(['action'])

// 点击事件代理
function onClick(evt) {
  onMdContainerClick(evt, payload => emit('action', payload))
}
</script>

<template>
  <div class="md-content" :class="{ 'is-md': isMarkdownHtml(content) }" v-html="content" @click="onClick" />
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
  white-space: pre;
}
.md-content :deep(.md-code code) {
  background: transparent;
  color: inherit;
  padding: 0;
}
.md-content :deep(.md-table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
  width: 100%;
}
.md-content :deep(.md-table th),
.md-content :deep(.md-table td) {
  border: 1px solid #ebeef5;
  padding: 6px 12px;
  text-align: left;
}
.md-content :deep(.md-table th) {
  background: #fafafa;
  font-weight: 600;
}
.md-content :deep(img) {
  max-width: 100%;
  border-radius: 4px;
  margin: 4px 0;
}
.md-content :deep(hr) {
  border: none;
  border-top: 1px solid #ebeef5;
  margin: 12px 0;
}
.md-content :deep(code) {
  background: #f5f7fa;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
  font-family: 'Monaco', 'Consolas', monospace;
}
.md-content :deep(strong) { font-weight: 700; }
.md-content :deep(em) { font-style: italic; }

/* 互动按钮 (V3.1 7 种) */
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
