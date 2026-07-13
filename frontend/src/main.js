import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/dark-theme.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus)
// 路由守卫需在 pinia 注册后调用
setupRouterGuards(pinia)

// V3.2: 全局错误处理 - 把 Vue 错误堆栈完整暴露
app.config.errorHandler = (err, instance, info) => {
  console.error('[vue-error]', err, info)
  // 把错误写到 window 上 + localStorage, 方便排查
  try {
    const trace = {
      message: String(err && err.message || err),
      stack: String(err && err.stack || ''),
      info,
      // 把 instance 的 vnode 标签/组件名暴露
      comp: instance && (instance.type?.name || instance.type?.__name || instance.$.type?.name || '?'),
      time: new Date().toISOString(),
    }
    window.__lastVueError = trace
    localStorage.setItem('__last_vue_error__', JSON.stringify(trace))
    // 在屏幕上显示
    showErrorOverlay(trace)
  } catch (e) {}
}
window.addEventListener('unhandledrejection', (evt) => {
  console.error('[unhandled-promise]', evt.reason)
  try {
    const trace = {
      message: 'unhandledrejection: ' + String(evt.reason?.message || evt.reason),
      stack: String(evt.reason?.stack || ''),
      time: new Date().toISOString(),
    }
    window.__lastPromiseError = trace
    localStorage.setItem('__last_promise_error__', JSON.stringify(trace))
    showErrorOverlay(trace)
  } catch (e) {}
})

function showErrorOverlay(trace) {
  let el = document.getElementById('__error_overlay__')
  if (!el) {
    el = document.createElement('div')
    el.id = '__error_overlay__'
    el.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:999999;background:#f56c6c;color:#fff;font:12px/1.4 monospace;padding:8px 12px;max-height:240px;overflow:auto;white-space:pre-wrap;box-shadow:0 2px 8px rgba(0,0,0,.2);'
    document.body.appendChild(el)
  }
  el.textContent = `[${trace.time}] ${trace.comp || ''} ${trace.info || ''} :: ${trace.message}\n${trace.stack?.split('\n').slice(0, 5).join('\n') || ''}`
}

app.mount('#app')
