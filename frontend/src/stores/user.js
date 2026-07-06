import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const STORAGE_KEY = 'cs_user'

function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}

export const useUserStore = defineStore('user', () => {
  const cached = loadFromStorage()
  const token = ref(cached?.token || '')
  const id = ref(cached?.id || null)
  const username = ref(cached?.username || '')
  const nickname = ref(cached?.nickname || '')
  const role = ref(cached?.role || '')

  const isLogin = computed(() => !!token.value)

  function setLogin(payload) {
    token.value = payload.token
    id.value = payload.id
    username.value = payload.username
    nickname.value = payload.nickname
    role.value = payload.role
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  }

  function logout() {
    token.value = ''
    id.value = null
    username.value = ''
    nickname.value = ''
    role.value = ''
    localStorage.removeItem(STORAGE_KEY)
  }

  return { token, id, username, nickname, role, isLogin, setLogin, logout }
})