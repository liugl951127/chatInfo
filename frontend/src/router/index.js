import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: () => import('@/views/Login.vue'), meta: { guest: true } },
  { path: '/customer', component: () => import('@/views/Customer.vue'), meta: { auth: true, role: 'CUSTOMER' } },
  { path: '/agent', component: () => import('@/views/Agent.vue'), meta: { auth: true, role: 'AGENT' } },
  { path: '/community', component: () => import('@/views/Community.vue'), meta: { auth: true } },
  { path: '/replay/:sessionId', component: () => import('@/views/Replay.vue'), meta: { auth: true } },
  { path: '/monitor', component: () => import('@/views/RealtimeMonitor.vue'), meta: { auth: true, role: 'AGENT' } },
  { path: '/admin', component: () => import('@/views/Admin.vue'), meta: { auth: true, role: 'AGENT' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 路由守卫 (由 main.js 调用, 确保 Pinia 已注册).
 * @param {import('pinia').Pinia} pinia Pinia 实例
 */
export function setupRouterGuards(pinia) {
  router.beforeEach((to, from, next) => {
    // 动态 import useUserStore 避免 router.js 顶层 import 时 pinia 未就绪
    import('@/stores/user').then(({ useUserStore }) => {
      const user = useUserStore(pinia)
      if (to.meta.auth && !user.token) return next('/login')
      if (to.meta.role && user.role !== to.meta.role) return next('/login')
      next()
    }).catch(() => next())
  })
}

export default router