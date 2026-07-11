/**
 * loading.js - 全局加载状态 store.
 * ----------------------------------------------------------------------------
 * 业务: 长任务 (上传 / 转码 / 录像合并) 显示全屏遮罩 loading.
 *
 * 用法:
 *   const loading = useLoadingStore()
 *   loading.start('上传中...')
 *   await fetch(...)
 *   loading.stop()
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useLoadingStore = defineStore('loading', () => {
  const visible = ref(false)
  const text = ref('加载中...')

  function start(t = '加载中...') {
    text.value = t
    visible.value = true
  }

  function stop() {
    visible.value = false
  }

  return { visible, text, start, stop }
})