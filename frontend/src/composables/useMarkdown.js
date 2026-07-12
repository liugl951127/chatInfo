/**
 * useMarkdown.js - Markdown 渲染辅助 (V3.1 后端渲染).
 * ----------------------------------------------------------------------------
 * V3.1 关键变更: Markdown 由后端生成 HTML, 前端只:
 *   1) 判别是否需要 markdown 渲染 (用 \`isHtml\` 启发)
 *   2) 处理按钮点击 (data-type data-label data-payload)
 *   3) XSS 防护: 仅信任后端 HTML, 不在前端解析
 *
 * 不再依赖 markdown-it (减重 ~50KB).
 */
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 处理 markdown 容器内按钮的点击事件.
 * @param evt DOM Event
 * @param emitter Vue emit 事件 (action)
 */
export function onMdContainerClick(evt, emitter) {
  const btn = evt.target.closest && evt.target.closest('.md-btn')
  if (!btn) return
  const type = btn.dataset.type || 'action'
  const label = btn.dataset.label || btn.textContent
  const payload = btn.dataset.payload || ''
  handleAction(type, label, payload, emitter)
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
    case 'quick':
    case 'faq':
      if (typeof emitter === 'function') {
        emitter({ type, label, payload })
      }
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
      if (/^https?:\/\//i.test(payload)) {
        window.open(payload, '_blank', 'noopener')
      } else {
        ElMessage.warning('仅支持 http(s) 链接')
      }
      break
    case 'action':
    default:
      if (typeof emitter === 'function') {
        emitter({ type, label, payload })
      } else {
        ElMessage.info(`操作: ${label}`)
      }
  }
}

/**
 * 检测是否需要 markdown 渲染 (后端已经返 html, 这里只判别).
 */
export function isMarkdownHtml(html) {
  if (!html) return false
  return /<h\d|<p>|<ul>|<ol>|<pre class="md-code|<blockquote|<table class="md-table|class="md-btn /.test(html)
}

export default { onMdContainerClick, isMarkdownHtml }
