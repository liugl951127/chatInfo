import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: () => import('@/views/Login.vue'), meta: { guest: true } },
  { path: '/customer', component: () => import('@/views/Customer.vue'), meta: { auth: true, role: 'CUSTOMER' } },
  { path: '/agent', component: () => import('@/views/Agent.vue'), meta: { auth: true, role: 'AGENT' } },
  { path: '/replay/:sessionId', component: () => import('@/views/Replay.vue'), meta: { auth: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const user = useUserStore()
  if (to.meta.auth && !user.token) return next('/login')
  if (to.meta.role && user.role !== to.meta.role) return next('/login')
  next()
})

export default router