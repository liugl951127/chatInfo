/**
 * useKeyboard.js - 键盘快捷键 composable.
 * ----------------------------------------------------------------------------
 * 全局快捷键 (ChatComposer 输入框 / 全局):
 *   - Enter:        发送消息
 *   - Shift+Enter:  换行 (默认)
 *   - Cmd/Ctrl+K:   消息搜索弹窗
 *   - Cmd/Ctrl+/:   显示快捷键帮助
 *   - Esc:          关闭当前弹窗
 *   - Alt+C:        切换技能 (Customer)
 *   - Alt+H:        转人工 (Customer)
 *
 * 用法:
 *   const { onKey } = useKeyboard()
 *   onKey('enter', () => send())
 *   onKey('search', () => showSearch = true)
 */
import { onMounted, onUnmounted } from 'vue'

const handlers = new Map()

function isMac() {
  return typeof navigator !== 'undefined' && /Mac/.test(navigator.platform)
}

function parseKey(e) {
  const cmd = isMac() ? e.metaKey : e.ctrlKey
  if (cmd && e.key === 'k') return 'search'
  if (cmd && e.key === '/') return 'help'
  if (cmd && e.key === 'Enter') return 'submit'
  if (e.altKey && e.key === 'c') return 'switch-skill'
  if (e.altKey && e.key === 'h') return 'transfer-human'
  if (e.key === 'Escape') return 'escape'
  if (e.key === 'Enter' && !e.shiftKey) return 'enter'
  if (e.key === 'Enter' && e.shiftKey) return 'shift-enter'
  return null
}

function onKeyDown(e) {
  const k = parseKey(e)
  if (!k) return
  const list = handlers.get(k) || []
  // 阻止默认 (但 escape 不阻止, 让浏览器原生处理)
  if (k !== 'escape' && k !== 'shift-enter') {
    e.preventDefault()
  }
  list.forEach(fn => { try { fn(e) } catch (err) { console.error(err) } })
}

let bound = false
function ensureBound() {
  if (bound) return
  bound = true
  document.addEventListener('keydown', onKeyDown)
}

export function useKeyboard() {
  onMounted(ensureBound)

  function onKey(event, callback) {
    if (!handlers.has(event)) handlers.set(event, [])
    handlers.get(event).push(callback)
    return () => offKey(event, callback)
  }

  function offKey(event, callback) {
    const list = handlers.get(event)
    if (!list) return
    const i = list.indexOf(callback)
    if (i >= 0) list.splice(i, 1)
  }

  onUnmounted(() => {
    // 注意: 不解绑全局, 多组件共享
  })

  return { onKey, offKey }
}