/**
 * useMarkdown.js - Markdown 渲染辅助 (V3.2 富交互增强).
 * ----------------------------------------------------------------------------
 * V3.2 增强: Markdown 由后端渲染 HTML, 前端只处理:
 *   1) 按钮点击 (data-type data-label data-payload)
 *   2) 表单收集 (input/select/radio/checkbox/textarea)
 *   3) 图标自动识别
 *   4) 折叠面板原生 <details>
 *
 * 不依赖任何 markdown 库 (后端已渲染).
 */
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 处理 markdown 容器内按钮的点击.
 */
export function onMdContainerClick(evt, emitter) {
  const btn = evt.target.closest && evt.target.closest('.md-btn')
  if (!btn) return
  const type = btn.dataset.type || 'action'
  const label = btn.dataset.label || btn.textContent
  const payload = btn.dataset.payload || ''
  handleAction(type, label, payload, emitter)
}

/**
 * 处理表单提交 - 收集容器内所有表单值.
 * @param evt submit event
 * @param formName 表单名 (data-form-name)
 * @returns 收集到的数据对象
 */
export function collectForm(containerEl) {
  if (!containerEl) return {}
  const data = {}
  // input
  containerEl.querySelectorAll('.md-input, .md-textarea').forEach(el => {
    const name = el.dataset.name || el.name
    if (name) data[name] = el.value
  })
  // select
  containerEl.querySelectorAll('.md-select').forEach(el => {
    const name = el.dataset.name || el.name
    if (name) data[name] = el.value
  })
  // radio
  const radioGroups = {}
  containerEl.querySelectorAll('.md-radios').forEach(group => {
    const name = group.dataset.name
    if (!name) return
    const checked = group.querySelector('input[type=radio]:checked')
    data[name] = checked ? checked.value : null
    radioGroups[name] = true
  })
  // checkbox
  const checkGroups = {}
  containerEl.querySelectorAll('.md-checkboxes').forEach(group => {
    const name = group.dataset.name
    if (!name) return
    const checked = group.querySelectorAll('input[type=checkbox]:checked')
    data[name] = Array.from(checked).map(c => c.value)
    checkGroups[name] = true
  })
  return data
}

async function handleAction(type, label, payload, emitter) {
  switch (type) {
    case 'transfer':
      if (typeof emitter === 'function') {
        emitter({ type: 'transfer', label, payload })
      }
      break
    case 'rate':
      ElMessage.success('感谢您的反馈 ⭐')
      if (typeof emitter === 'function') {
        emitter({ type: 'rate', label, payload })
      }
      break
    case 'submit':
      // 表单提交
      if (typeof emitter === 'function') {
        const container = emitter.containerEl
        const formData = collectForm(container)
        emitter({ type: 'submit', label, payload, formData })
      }
      break
    case 'quick':
    case 'faq':
    case 'action':
    default:
      if (typeof emitter === 'function') {
        emitter({ type, label, payload })
      } else {
        ElMessage.info(`操作: ${label}`)
      }
      break
  }
}

/**
 * 检测是否需要 markdown 渲染 (后端已经返 html, 这里只判别).
 */
export function isMarkdownHtml(html) {
  if (!html) return false
  return /<h\d|<p>|<ul>|<ol>|<pre class="md-code|<blockquote|<table class="md-table|class="md-btn |class="md-icon|class="md-badge|class="md-alert|class="md-card|class="md-progress|class="md-input|class="md-select|class="md-stat|class="md-collapse/.test(html)
}

export default { onMdContainerClick, collectForm, isMarkdownHtml }
