<script setup>
/**
 * ThemeToggle.vue - 主题切换按钮.
 * 浅色 / 暗色 / 自动 三态.
 */
import { useTheme } from '@/composables/useTheme'
const { theme, setTheme } = useTheme()

const options = [
  { value: 'light', icon: '☀️', label: '浅色' },
  { value: 'dark', icon: '🌙', label: '暗色' },
  { value: 'auto', icon: '⚙️', label: '自动' },
]
</script>

<template>
  <el-dropdown trigger="click" @command="(v) => setTheme(v)">
    <el-button link class="theme-btn" :title="`当前主题: ${theme}`">
      <span v-if="theme === 'light'">☀️</span>
      <span v-else-if="theme === 'dark'">🌙</span>
      <span v-else>⚙️</span>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="o in options" :key="o.value" :command="o.value" :disabled="theme === o.value">
          <span class="theme-item">{{ o.icon }} {{ o.label }}</span>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.theme-btn { font-size: 16px; padding: 4px 8px; }
.theme-item { font-size: 13px; }
</style>