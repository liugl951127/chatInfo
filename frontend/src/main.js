import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/dark-theme.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// V3.2: 全局错误处理 - 防止 "Uncaught (in promise)" 默默失败
// 之前 Customer.vue 的 draft TDZ 错误就是靠这个捕获的
app.config.errorHandler = (err, instance, info) => {
  console.error('[vue-error]', err, info)
  // 生产环境可以上报到 sentry / 自建监控
}
window.addEventListener('unhandledrejection', (evt) => {
  console.error('[unhandled-promise]', evt.reason)
})

app.mount('#app')