<script setup>
/**
 * MarkdownEditor.vue - Markdown 编辑器 (V3.2 新增).
 * ----------------------------------------------------------------------------
 * 功能:
 *   - 工具栏: 粗体/斜体/删除线/代码/链接/图片/列表/引用
 *   - 富文本快捷插入: 按钮/徽章/标签/提示框/进度条/输入框等
 *   - 实时预览: 编辑时调用 aiApi.renderMd 渲染 HTML
 *   - 3 种视图: 编辑 / 预览 / 分屏
 *   - 字数统计 / 自动保存草稿 (useDraft)
 *   - Ctrl+Enter 发送
 *   - Tab 缩进
 *
 * 用法:
 *   <MarkdownEditor v-model="text" @send="onSend" placeholder="..." />
 */
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { ElMessage, ElTooltip } from 'element-plus'
import { aiApi } from '@/api/ai'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '输入消息... (Ctrl+Enter 发送)' },
  disabled: { type: Boolean, default: false },
  enablePreview: { type: Boolean, default: true },
  enableRichInsert: { type: Boolean, default: true },
  maxLength: { type: Number, default: 5000 },
})
const emit = defineEmits(['update:modelValue', 'send', 'change'])

// 视图模式: 'edit' | 'preview' | 'split'
const viewMode = ref('edit')
const textareaRef = ref(null)
const showHtml = ref(false)
const htmlCache = ref('')
const rendering = ref(false)

// 富文本工具栏
const richTools = [
  { icon: '👍', label: '表情', snippet: '[icon:👍] ' },
  { icon: '🔥', label: '热门', snippet: '[badge:warning:热门] ' },
  { icon: '✓', label: '成功', snippet: '[badge:success:完成] ' },
  { icon: '!', label: '警告', snippet: '[alert:warning:注意|内容] ' },
  { icon: '📊', label: '进度', snippet: '[progress:50]' },
  { icon: '⭐', label: '评价', snippet: '[radio:rate:评分:5=非常满意|4=满意|3=一般]' },
  { icon: '💬', label: '输入', snippet: '[input:name:请输入:默认值]' },
  { icon: '📋', label: '下拉', snippet: '[select:type:类型:A=类型A|B=类型B|C=类型C]' },
  { icon: '☐', label: '多选', snippet: '[checkbox:reason:原因:A=原因A|B=原因B]' },
  { icon: '💡', label: '提示', snippet: '[collapse:标题|内容]' },
  { icon: '💬', label: '引用', snippet: '[quote:来源|内容]' },
  { icon: '▶', label: '主按钮', snippet: '[btn:primary:按钮文字] ' },
  { icon: '✓', label: '成功按钮', snippet: '[btn:success:确认] ' },
  { icon: '⚠', label: '警告按钮', snippet: '[btn:warning:警告] ' },
  { icon: '✕', label: '危险按钮', snippet: '[btn:danger:删除] ' },
  { icon: '🔗', label: '链接按钮', snippet: '[btn:link:查看|https://example.com] ' },
]

// 基础工具栏
const basicTools = [
  { icon: 'B', label: '粗体', style: 'font-weight:bold', snippet: '**', wrap: true },
  { icon: 'I', label: '斜体', style: 'font-style:italic', snippet: '*', wrap: true },
  { icon: 'S', label: '删除线', style: 'text-decoration:line-through', snippet: '~~', wrap: true },
  { icon: '<>', label: '代码', style: 'font-family:monospace', snippet: '`', wrap: true },
  { icon: '#', label: '标题', style: 'font-weight:bold', snippet: '## ', pre: true },
  { icon: '•', label: '无序列表', snippet: '- ', pre: true },
  { icon: '1.', label: '有序列表', snippet: '1. ', pre: true },
  { icon: '>', label: '引用', snippet: '> ', pre: true },
  { icon: '🔗', label: '链接', snippet: '[text](url)', interactive: true },
  { icon: '🖼', label: '图片', snippet: '![alt](url)', interactive: true },
  { icon: '📊', label: '表格', snippet: '| 列1 | 列2 |\n| --- | --- |\n| A | B |', pre: true },
  { icon: '```', label: '代码块', snippet: '```js\n\n```', pre: true, multiline: true },
]

const showRichPanel = ref(false)

// 字数统计
const charCount = computed(() => (props.modelValue || '').length)
const lineCount = computed(() => (props.modelValue || '').split('\n').length)

// 双向绑定
function update(val) {
  emit('update:modelValue', val)
  emit('change', val)
}

// 插入文本
function insertText(snippet, options = {}) {
  const ta = textareaRef.value
  if (!ta) {
    // fallback
    update((props.modelValue || '') + snippet)
    return
  }
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const before = (props.modelValue || '').substring(0, start)
  const sel = (props.modelValue || '').substring(start, end)
  const after = (props.modelValue || '').substring(end)
  let newText, cursorStart, cursorEnd
  if (options.wrap) {
    // 包裹: 选区 + snippet
    newText = before + snippet + (sel || 'text') + snippet + after
    cursorStart = start + snippet.length
    cursorEnd = cursorStart + (sel || 'text').length
  } else if (options.pre) {
    // 前置: 在行首插入
    const lineStart = before.lastIndexOf('\n') + 1
    newText = before.substring(0, lineStart) + snippet + before.substring(lineStart) + sel + after
    cursorStart = cursorEnd = start + snippet.length
  } else {
    // 直接插入
    newText = before + snippet + sel + after
    cursorStart = cursorEnd = start + snippet.length
  }
  update(newText)
  nextTick(() => {
    ta.focus()
    ta.setSelectionRange(cursorStart, cursorEnd)
  })
}

// 链接/图片交互
const linkDialog = ref({ open: false, type: 'link' })
function openLinkDialog(type) {
  linkDialog.value = { open: true, type }
}
function confirmLink() {
  const { type, text, url } = linkDialog.value
  if (!url) {
    ElMessage.warning('请输入 URL')
    return
  }
  const snippet = type === 'image' ? `![${text || 'alt'}](${url})` : `[${text || 'text'}](${url})`
  insertText(snippet)
  linkDialog.value = { open: false, type: 'link' }
}

// 预览 - 调后端 render
let previewTimer = null
async function refreshPreview() {
  if (!props.modelValue || !props.modelValue.trim()) {
    htmlCache.value = ''
    return
  }
  rendering.value = true
  try {
    const r = await aiApi.renderMd(props.modelValue)
    htmlCache.value = r.html || r.data?.html || ''
  } catch (e) {
    // 失败用前端简单预览 (空)
    htmlCache.value = '<p style="color:#999">预览失败, 请检查后端服务</p>'
  } finally {
    rendering.value = false
  }
}

// 防抖
watch(() => props.modelValue, (val) => {
  if (previewTimer) clearTimeout(previewTimer)
  previewTimer = setTimeout(refreshPreview, 500)
})

watch(viewMode, (m) => {
  if (m === 'preview' || m === 'split') refreshPreview()
})

// 切换模式
function setView(mode) {
  viewMode.value = mode
}

// 键盘事件
function onKeydown(evt) {
  if (evt.ctrlKey && evt.key === 'Enter') {
    evt.preventDefault()
    emit('send', props.modelValue)
    return
  }
  if (evt.key === 'Tab') {
    evt.preventDefault()
    insertText('  ')
    return
  }
  // Ctrl+B / I / K
  if (evt.ctrlKey || evt.metaKey) {
    if (evt.key === 'b' || evt.key === 'B') { evt.preventDefault(); insertText('**', { wrap: true }); return }
    if (evt.key === 'i' || evt.key === 'I') { evt.preventDefault(); insertText('*', { wrap: true }); return }
    if (evt.key === 'k' || evt.key === 'K') { evt.preventDefault(); openLinkDialog('link'); return }
  }
}

// 发送
function send() {
  const text = (props.modelValue || '').trim()
  if (!text) {
    ElMessage.warning('内容不能为空')
    return
  }
  emit('send', text)
}

defineExpose({ insertText, send, refreshPreview, viewMode })
</script>

<template>
  <div class="md-editor" :class="{ 'is-disabled': disabled }">
    <!-- 工具栏 -->
    <div class="md-toolbar">
      <!-- 基础工具 -->
      <div class="md-tool-group">
        <el-tooltip v-for="t in basicTools" :key="t.label" :content="t.label" placement="top">
          <button
            type="button"
            class="md-tool-btn"
            :class="`md-tool-${t.label}`"
            :style="t.style"
            @click="t.interactive ? openLinkDialog(t.label.includes('图片') ? 'image' : 'link') : insertText(t.snippet, { wrap: t.wrap, pre: t.pre })">
            {{ t.icon }}
          </button>
        </el-tooltip>
      </div>

      <!-- 富文本工具 - 折叠面板 -->
      <el-tooltip content="富文本组件" placement="top">
        <button
          type="button"
          class="md-tool-btn md-tool-rich"
          :class="{ active: showRichPanel }"
          @click="showRichPanel = !showRichPanel">
          ⚡
        </button>
      </el-tooltip>

      <!-- 视图切换 -->
      <div class="md-tool-group md-tool-view">
        <el-tooltip content="编辑" placement="top">
          <button type="button" class="md-tool-btn" :class="{ active: viewMode === 'edit' }" @click="setView('edit')">✏</button>
        </el-tooltip>
        <el-tooltip v-if="enablePreview" content="分屏" placement="top">
          <button type="button" class="md-tool-btn" :class="{ active: viewMode === 'split' }" @click="setView('split')">⊟</button>
        </el-tooltip>
        <el-tooltip v-if="enablePreview" content="预览" placement="top">
          <button type="button" class="md-tool-btn" :class="{ active: viewMode === 'preview' }" @click="setView('preview')">👁</button>
        </el-tooltip>
      </div>

      <!-- 字数统计 -->
      <div class="md-tool-stats">
        <span :class="{ warn: charCount > maxLength * 0.9 }">{{ charCount }} / {{ maxLength }}</span>
        <span class="md-tool-lines">{{ lineCount }} 行</span>
      </div>
    </div>

    <!-- 富文本面板 (折叠) -->
    <transition name="md-slide">
      <div v-if="showRichPanel" class="md-rich-panel">
        <div v-for="(t, idx) in richTools" :key="idx" class="md-rich-item"
             :title="t.label"
             @click="insertText(t.snippet); showRichPanel = false">
          <span class="md-rich-icon">{{ t.icon }}</span>
          <span class="md-rich-label">{{ t.label }}</span>
        </div>
      </div>
    </transition>

    <!-- 编辑区 + 预览区 -->
    <div class="md-body" :class="`md-view-${viewMode}`">
      <textarea
        v-if="viewMode === 'edit' || viewMode === 'split'"
        ref="textareaRef"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        :maxlength="maxLength"
        class="md-textarea"
        @input="update($event.target.value)"
        @keydown="onKeydown"
        rows="5" />

      <div v-if="viewMode === 'preview' || viewMode === 'split'" class="md-preview">
        <div v-if="rendering" class="md-preview-loading">渲染中...</div>
        <div v-else-if="!htmlCache" class="md-preview-empty">暂无内容</div>
        <div v-else class="md-preview-content md-content" v-html="htmlCache" />
      </div>
    </div>

    <!-- 链接/图片对话框 -->
    <el-dialog v-model="linkDialog.open" :title="linkDialog.type === 'image' ? '插入图片' : '插入链接'" width="420px">
      <el-form label-width="60px">
        <el-form-item :label="linkDialog.type === 'image' ? '描述' : '文本'">
          <el-input v-model="linkDialog.text" :placeholder="linkDialog.type === 'image' ? 'alt 文本' : '链接文字'" />
        </el-form-item>
        <el-form-item label="URL">
          <el-input v-model="linkDialog.url" placeholder="https://..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="linkDialog.open = false">取消</el-button>
        <el-button type="primary" @click="confirmLink">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.md-editor {
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  background: #fff;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.md-editor.is-disabled { opacity: 0.6; pointer-events: none; }

.md-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  background: #fafafa;
  border-bottom: 1px solid #ebeef5;
  flex-wrap: wrap;
}
.md-tool-group {
  display: flex;
  gap: 2px;
  padding-right: 6px;
  border-right: 1px solid #ebeef5;
}
.md-tool-view { border-right: none; }
.md-tool-stats {
  margin-left: auto;
  font-size: 11px;
  color: #909399;
  display: flex;
  gap: 8px;
}
.md-tool-stats .warn { color: #e6a23c; font-weight: 500; }

.md-tool-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  border: 1px solid transparent;
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  color: #606266;
  transition: all 0.15s;
  font-family: inherit;
}
.md-tool-btn:hover {
  background: #fff;
  border-color: #dcdfe6;
  color: #409eff;
}
.md-tool-btn.active {
  background: #ecf5ff;
  border-color: #b3d8ff;
  color: #409eff;
}
.md-tool-rich { font-size: 14px; }

/* 富文本面板 */
.md-rich-panel {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
  gap: 4px;
  padding: 10px 12px;
  background: #f0f9ff;
  border-bottom: 1px solid #ebeef5;
  max-height: 220px;
  overflow-y: auto;
}
.md-rich-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.15s;
}
.md-rich-item:hover {
  background: #ecf5ff;
  border-color: #409eff;
  transform: translateY(-1px);
}
.md-rich-icon { font-size: 14px; }
.md-rich-label { color: #606266; }

/* 编辑区 */
.md-body {
  display: flex;
  flex: 1;
  min-height: 120px;
}
.md-view-edit .md-body { display: block; }
.md-view-preview .md-textarea { display: none; }
.md-view-split .md-body { display: grid; grid-template-columns: 1fr 1fr; }

.md-textarea {
  flex: 1;
  width: 100%;
  padding: 10px 14px;
  border: none;
  outline: none;
  resize: vertical;
  font-family: 'Monaco', 'Consolas', 'Courier New', monospace;
  font-size: 14px;
  line-height: 1.6;
  background: transparent;
  box-sizing: border-box;
  min-height: 120px;
}
.md-view-split .md-textarea {
  border-right: 1px solid #ebeef5;
}

.md-preview {
  flex: 1;
  padding: 10px 14px;
  background: #fafafa;
  overflow-y: auto;
  min-height: 120px;
  max-height: 400px;
  font-size: 14px;
}
.md-preview-empty,
.md-preview-loading {
  color: #909399;
  text-align: center;
  padding: 30px 0;
  font-size: 13px;
}
.md-preview-content {
  line-height: 1.6;
}
/* 复用 MarkdownRenderer 样式 - 用 :deep 不会跨 scope, 这里 inline */
.md-preview-content :deep(h1), .md-preview-content :deep(h2), .md-preview-content :deep(h3) {
  margin: 8px 0 4px; font-weight: 600;
}
.md-preview-content :deep(h1) { font-size: 18px; }
.md-preview-content :deep(h2) { font-size: 16px; }
.md-preview-content :deep(h3) { font-size: 15px; }
.md-preview-content :deep(p) { margin: 4px 0; }
.md-preview-content :deep(.md-btn) {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 12px;
  font-size: 12px;
  margin: 2px 4px;
  color: #fff;
  background: #409eff;
}
.md-preview-content :deep(.md-badge) {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 11px;
  margin: 0 2px;
  background: #ecf5ff;
  color: #409eff;
}
.md-preview-content :deep(.md-alert) {
  padding: 6px 10px;
  border-radius: 4px;
  margin: 4px 0;
  background: #f4f4f5;
  border-left: 3px solid #909399;
}
.md-preview-content :deep(.md-progress) {
  background: #ebeef5;
  border-radius: 8px;
  height: 16px;
  position: relative;
  margin: 4px 0;
}
.md-preview-content :deep(.md-progress-bar) {
  height: 100%;
  border-radius: 8px;
  background: #409eff;
}
.md-preview-content :deep(.md-input) {
  display: inline-block;
  padding: 2px 6px;
  border: 1px solid #dcdfe6;
  border-radius: 3px;
  font-size: 12px;
}

/* 滑入动画 */
.md-slide-enter-active, .md-slide-leave-active {
  transition: all 0.2s;
  max-height: 220px;
}
.md-slide-enter-from, .md-slide-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
}
</style>
