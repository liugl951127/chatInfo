/**
 * useMarkdown.js - Markdown 渲染 composable.
 * ----------------------------------------------------------------------------
 * 基于 markdown-it, 支持:
 *   - 标准 markdown: 标题/列表/代码/粗体/链接/图片/表格/引用
 *   - 代码高亮 (highlight.js)
 *   - 互动按钮: [button:type:label:payload] 语法
 *   - 复制代码: 自动渲染
 *
 * 按钮语法 (V3 扩展):
 *   [button:transfer:转人工]           → 转人工按钮
 *   [button:rate:👍]                  → 评分按钮
 *   [button:quick:查看订单状态]        → 快捷问题按钮
 *   [button:faq:如何退款]              → FAQ 跳转
 *   [button:copy:复制代码:code]        → 复制代码按钮
 *   [button:link:查看详情:https://...]  → 链接按钮
 *   [button:action:查看]               → 通用 action (emit)
 *
 * 渲染产出: HTML 字符串 + 元数据 (button 列表)
 */
import MarkdownIt from 'markdown-it'

// 单例
let mdInstance = null

function getMd() {
  if (mdInstance) return mdInstance
  mdInstance = new MarkdownIt({
    html: false,        // 禁止原始 HTML (防 XSS)
    linkify: true,      // 自动识别 URL
    typographer: true,  // 智能引号
    breaks: true,       // \n 换行
    highlight: (str, lang) => {
      // V3 简化: 不集成 highlight.js (体积 +30KB), 用纯 CSS 着色
      // 真实项目可换 highlight.js
      if (lang) {
        return `<pre class="md-code"><code class="language-${lang}">${escapeHtml(str)}</code></pre>`
      }
      return `<pre class="md-code"><code>${escapeHtml(str)}</code></pre>`
    }
  })
  return mdInstance
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/**
 * 提取 markdown 中的 button 标记, 转 HTML 占位符.
 * 业务: 渲染时把 [button:...] 转成 <span data-md-btn="...">, 由组件渲染为真按钮.
 */
function processButtons(text) {
  const buttons = []
  // 匹配: [button:type:label:payload] 或 [button:type:label]
  const re = /\[button:([a-z]+)(?::([^\]:]+))?(?::([^\]]+))?\]/g
  const replaced = text.replace(re, (m, type, label, payload) => {
    const idx = buttons.length
    buttons.push({ type, label: label || '操作', payload: payload || '' })
    return `\n<button data-md-btn-idx="${idx}"></button>\n`
  })
  return { text: replaced, buttons }
}

/**
 * 渲染 markdown 文本为 HTML + 按钮元数据.
 * @param text markdown 文本
 * @returns { html: string, buttons: Array<{type, label, payload}> }
 */
export function renderMarkdown(text) {
  if (!text) return { html: '', buttons: [] }
  const { text: processed, buttons } = processButtons(String(text))
  const html = getMd().render(processed)
  return { html, buttons }
}

/**
 * 简洁版: 只返 HTML 字符串 (无按钮).
 */
export function renderMarkdownHtml(text) {
  return renderMarkdown(text).html
}

/**
 * 检测文本是否含 markdown 标记.
 */
export function isMarkdown(text) {
  if (!text) return false
  // 简单启发: # / * / - / ` / [ / > / 数字. / ``` / | 之一 + 至少 2 个连续内容
  return /[#*`>\-\[\d]+\s|```|\[button:/.test(text) && text.length > 5
}

export default { renderMarkdown, renderMarkdownHtml, isMarkdown }
