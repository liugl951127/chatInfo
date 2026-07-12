<script setup>
/**
 * MarkdownRenderer.vue - Markdown 渲染器 (V3.2 富交互版).
 * ----------------------------------------------------------------------------
 * V3.2 增强:
 *   - 后端 MarkdownService 生成 HTML
 *   - 前端只 v-html + 事件代理
 *   - CSS 完整支持 15+ 富文本元素: 按钮/图标/徽章/标签/提示框/卡片/
 *     进度条/输入框/下拉/单选/复选/文本域/数据统计/折叠/引用/分割线
 */
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { onMdContainerClick, isMarkdownHtml, collectForm } from '@/composables/useMarkdown'

const props = defineProps({
  content: { type: String, required: true },     // 后端返的 HTML
  markdown: { type: String, default: '' },        // 原始 markdown (可选, 用于复制)
})
const emit = defineEmits(['action', 'submit'])

const containerRef = ref(null)

// 点击事件代理 (按钮)
function onClick(evt) {
  onMdContainerClick(evt, payload => {
    // 提交按钮特殊处理: 收集表单
    if (payload.type === 'submit') {
      const data = collectForm(containerRef.value)
      emit('submit', { ...payload, formData: data })
    } else {
      emit('action', payload)
    }
  })
}
</script>

<template>
  <div ref="containerRef" class="md-content" :class="{ 'is-md': isMarkdownHtml(content) }" v-html="content" @click="onClick" />
</template>

<style scoped>
.md-content { line-height: 1.6; }

/* ============ 基础元素 ============ */
.md-content :deep(h1) { font-size: 22px; font-weight: 700; margin: 12px 0 8px; }
.md-content :deep(h2) { font-size: 20px; font-weight: 700; margin: 10px 0 6px; }
.md-content :deep(h3) { font-size: 18px; font-weight: 600; margin: 8px 0 4px; }
.md-content :deep(h4) { font-size: 16px; font-weight: 600; margin: 6px 0 4px; }
.md-content :deep(p) { margin: 6px 0; }
.md-content :deep(ul), .md-content :deep(ol) { margin: 6px 0; padding-left: 24px; }
.md-content :deep(li) { margin: 2px 0; }
.md-content :deep(a) { color: #409EFF; text-decoration: none; }
.md-content :deep(a:hover) { text-decoration: underline; }
.md-content :deep(code) { background: #f5f7fa; padding: 1px 4px; border-radius: 3px; font-size: 12px; font-family: 'Monaco', 'Consolas', monospace; }
.md-content :deep(strong) { font-weight: 700; }
.md-content :deep(em) { font-style: italic; }
.md-content :deep(hr) { border: none; border-top: 1px solid #ebeef5; margin: 12px 0; }
.md-content :deep(img) { max-width: 100%; border-radius: 4px; margin: 4px 0; }

/* ============ 代码块 ============ */
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
.md-content :deep(.md-code code) { background: transparent; color: inherit; padding: 0; }

/* ============ 引用 ============ */
.md-content :deep(blockquote) {
  border-left: 4px solid #dcdfe6;
  padding: 4px 12px;
  margin: 6px 0;
  color: #606266;
  background: #fafafa;
}

/* ============ 表格 ============ */
.md-content :deep(.md-table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
  width: 100%;
}
.md-content :deep(.md-table th), .md-content :deep(.md-table td) {
  border: 1px solid #ebeef5;
  padding: 6px 12px;
  text-align: left;
}
.md-content :deep(.md-table th) { background: #fafafa; font-weight: 600; }

/* ============ 按钮 (8 种 type) ============ */
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
.md-content :deep(.md-btn:hover) { transform: translateY(-1px); box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
.md-content :deep(.md-btn-primary) { background: #409eff; color: #fff; }
.md-content :deep(.md-btn-success) { background: #67c23a; color: #fff; }
.md-content :deep(.md-btn-warning) { background: #e6a23c; color: #fff; }
.md-content :deep(.md-btn-danger) { background: #f56c6c; color: #fff; }
.md-content :deep(.md-btn-info) { background: #909399; color: #fff; }
.md-content :deep(.md-btn-default) { background: #fff; color: #606266; border: 1px solid #dcdfe6; }
.md-content :deep(.md-btn-text) { background: transparent; color: #409eff; }
.md-content :deep(.md-btn-link) { background: transparent; color: #409eff; text-decoration: underline; }
.md-content :deep(.md-btn-icon) { background: #f0f9ff; color: #409eff; border: 1px solid #b3d8ff; }
/* V3 兼容 (旧 button 语法) */
.md-content :deep(.md-btn-transfer) { background: #f56c6c; color: #fff; }
.md-content :deep(.md-btn-rate) { background: #e6a23c; color: #fff; }
.md-content :deep(.md-btn-quick) { background: #67c23a; color: #fff; }
.md-content :deep(.md-btn-faq) { background: #409eff; color: #fff; }
.md-content :deep(.md-btn-copy) { background: #909399; color: #fff; }
.md-content :deep(.md-btn-action) { background: #f0f9ff; color: #409eff; border: 1px solid #b3d8ff; }

/* ============ 图标 ============ */
.md-content :deep(.md-icon) {
  display: inline-block;
  font-size: 16px;
  vertical-align: middle;
  margin: 0 2px;
}

/* ============ 徽章 / 标签 ============ */
.md-content :deep(.md-badge) {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
  margin: 0 4px;
  vertical-align: middle;
}
.md-content :deep(.md-badge-success) { background: #f0f9ff; color: #67c23a; border: 1px solid #b3e19d; }
.md-content :deep(.md-badge-warning) { background: #fdf6ec; color: #e6a23c; border: 1px solid #f5dab1; }
.md-content :deep(.md-badge-info) { background: #f4f4f5; color: #909399; border: 1px solid #d3d4d6; }
.md-content :deep(.md-badge-danger) { background: #fef0f0; color: #f56c6c; border: 1px solid #fbc4c4; }
.md-content :deep(.md-badge-primary) { background: #ecf5ff; color: #409eff; border: 1px solid #b3d8ff; }

.md-content :deep(.md-tag) {
  display: inline-block;
  padding: 2px 8px;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 4px;
  font-size: 12px;
  margin: 0 4px;
}

/* ============ 提示框 (4 种) ============ */
.md-content :deep(.md-alert) {
  padding: 10px 14px;
  border-radius: 6px;
  margin: 8px 0;
  border-left: 4px solid;
}
.md-content :deep(.md-alert-title) { font-weight: 600; margin-bottom: 4px; }
.md-content :deep(.md-alert-content) { font-size: 13px; }
.md-content :deep(.md-alert-info) { background: #f4f4f5; border-color: #909399; color: #606266; }
.md-content :deep(.md-alert-info .md-alert-title) { color: #909399; }
.md-content :deep(.md-alert-success) { background: #f0f9ff; border-color: #67c23a; color: #5daf34; }
.md-content :deep(.md-alert-success .md-alert-title) { color: #67c23a; }
.md-content :deep(.md-alert-warning) { background: #fdf6ec; border-color: #e6a23c; color: #b88230; }
.md-content :deep(.md-alert-warning .md-alert-title) { color: #e6a23c; }
.md-content :deep(.md-alert-danger) { background: #fef0f0; border-color: #f56c6c; color: #c45656; }
.md-content :deep(.md-alert-danger .md-alert-title) { color: #f56c6c; }

/* ============ 卡片 ============ */
.md-content :deep(.md-card) {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 16px;
  margin: 8px 0;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.md-content :deep(.md-card-title) { font-size: 16px; font-weight: 600; margin-bottom: 8px; color: #303133; }
.md-content :deep(.md-card-content) { font-size: 14px; color: #606266; }
.md-content :deep(.md-card-footer) { margin-top: 8px; padding-top: 8px; border-top: 1px solid #ebeef5; font-size: 12px; color: #909399; }

/* ============ 进度条 ============ */
.md-content :deep(.md-progress) {
  position: relative;
  background: #ebeef5;
  border-radius: 10px;
  height: 20px;
  margin: 8px 0;
  overflow: hidden;
}
.md-content :deep(.md-progress-bar) {
  position: absolute;
  top: 0;
  left: 0;
  height: 100%;
  border-radius: 10px;
  transition: width 0.3s;
}
.md-content :deep(.md-progress-success) { background: linear-gradient(90deg, #67c23a, #95d475); }
.md-content :deep(.md-progress-primary) { background: linear-gradient(90deg, #409eff, #79bbff); }
.md-content :deep(.md-progress-warning) { background: linear-gradient(90deg, #e6a23c, #eebe77); }
.md-content :deep(.md-progress-danger) { background: linear-gradient(90deg, #f56c6c, #f89898); }
.md-content :deep(.md-progress-text) {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 12px;
  line-height: 20px;
  color: #fff;
  font-weight: 600;
  text-shadow: 0 0 2px rgba(0,0,0,0.3);
}

/* ============ 表单 ============ */
.md-content :deep(.md-input),
.md-content :deep(.md-textarea) {
  display: block;
  width: 100%;
  padding: 6px 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 13px;
  font-family: inherit;
  margin: 4px 0;
  box-sizing: border-box;
  background: #fff;
}
.md-content :deep(.md-input:focus),
.md-content :deep(.md-textarea:focus) { outline: none; border-color: #409eff; }
.md-content :deep(.md-textarea) { min-height: 60px; resize: vertical; }

.md-content :deep(.md-field) {
  display: block;
  margin: 6px 0;
}
.md-content :deep(.md-field-label),
.md-content :deep(.md-field-group .md-field-label) {
  display: block;
  font-size: 12px;
  color: #606266;
  margin-bottom: 4px;
  font-weight: 500;
}
.md-content :deep(.md-select) {
  display: block;
  width: 100%;
  padding: 6px 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 13px;
  background: #fff;
  box-sizing: border-box;
}

.md-content :deep(.md-radio),
.md-content :deep(.md-checkbox) {
  display: inline-block;
  margin-right: 12px;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}
.md-content :deep(.md-radio input),
.md-content :deep(.md-checkbox input) { margin-right: 4px; vertical-align: middle; }

.md-content :deep(.md-radios),
.md-content :deep(.md-checkboxes) {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

/* ============ 数据统计 ============ */
.md-content :deep(.md-stat) {
  display: inline-block;
  padding: 10px 16px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  margin: 4px 6px 4px 0;
  background: #fafafa;
  vertical-align: top;
}
.md-content :deep(.md-stat-label) { font-size: 12px; color: #909399; margin-bottom: 4px; }
.md-content :deep(.md-stat-value) { font-size: 20px; font-weight: 700; color: #303133; }
.md-content :deep(.md-stat-trend) { font-size: 12px; margin-left: 6px; font-weight: 500; }
.md-content :deep(.md-stat-up) { color: #67c23a; }
.md-content :deep(.md-stat-down) { color: #f56c6c; }
.md-content :deep(.md-stat-flat) { color: #909399; }

/* ============ 折叠面板 ============ */
.md-content :deep(.md-collapse) {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  margin: 6px 0;
  background: #fafafa;
}
.md-content :deep(.md-collapse summary) {
  padding: 8px 14px;
  cursor: pointer;
  font-weight: 500;
  user-select: none;
  outline: none;
}
.md-content :deep(.md-collapse summary:hover) { background: #f0f9ff; }
.md-content :deep(.md-collapse-content) {
  padding: 0 14px 10px;
  font-size: 13px;
  color: #606266;
}

/* ============ 引用 (V3.2 增强) ============ */
.md-content :deep(.md-quote) {
  border-left: 4px solid #409eff;
  background: #f0f9ff;
  padding: 10px 16px;
  margin: 8px 0;
  border-radius: 0 6px 6px 0;
}
.md-content :deep(.md-quote-text) { font-style: italic; }
.md-content :deep(.md-quote-source) {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  font-style: normal;
}

/* ============ 分割线 ============ */
.md-content :deep(.md-divider) {
  border: none;
  height: 2px;
  background: linear-gradient(90deg, transparent, #409eff, transparent);
  margin: 16px 0;
}
</style>
