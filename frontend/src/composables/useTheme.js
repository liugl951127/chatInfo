/**
 * useTheme.js - 主题切换 composable.
 * ----------------------------------------------------------------------------
 * 业务: 客户/坐席可切换 浅色 / 深色 / 自动 (跟随系统).
 *
 * 实现:
 *   - data-theme 属性控制 (CSS 变量)
 *   - localStorage 记忆
 *   - prefers-color-scheme 检测
 *
 * 主题:
 *   - light:  默认浅色
 *   - dark:   深色 (高对比度, 适合夜间坐席)
 *   - auto:   跟随系统
 */
import { ref, watch, onMounted } from 'vue'

const THEME_KEY = 'cs_theme'
const ALLOWED = ['light', 'dark', 'auto']

export function useTheme() {
  const theme = ref(localStorage.getItem(THEME_KEY) || 'light')

  function apply(t) {
    if (!ALLOWED.includes(t)) t = 'light'
    if (t === 'auto') {
      const sysDark = window.matchMedia?.('(prefers-color-scheme: dark)')?.matches
      document.documentElement.setAttribute('data-theme', sysDark ? 'dark' : 'light')
    } else {
      document.documentElement.setAttribute('data-theme', t)
    }
  }

  function setTheme(t) {
    theme.value = t
    localStorage.setItem(THEME_KEY, t)
    apply(t)
  }

  // 系统主题变化 (auto 模式)
  function onSystemChange() {
    if (theme.value === 'auto') apply('auto')
  }

  onMounted(() => {
    apply(theme.value)
    window.matchMedia?.('(prefers-color-scheme: dark)')?.addEventListener('change', onSystemChange)
  })

  watch(theme, apply)

  return { theme, setTheme }
}