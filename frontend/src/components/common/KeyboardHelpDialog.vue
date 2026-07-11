<script setup>
/**
 * KeyboardHelpDialog.vue - 快捷键帮助弹窗.
 * Cmd+/ 唤起, 列出所有可用快捷键.
 */
import { ref, watch } from 'vue'

const props = defineProps({ visible: { type: Boolean, default: false } })
const emit = defineEmits(['update:visible'])
const dialogVisible = ref(props.visible)
watch(() => props.visible, v => dialogVisible.value = v)
watch(dialogVisible, v => emit('update:visible', v))

function onKey(e) {
  if ((e.metaKey || e.ctrlKey) && e.key === '/') {
    e.preventDefault()
    dialogVisible.value = !dialogVisible.value
  }
}


const shortcuts = [
  { key: 'Enter', desc: '发送消息 (输入框内)' },
  { key: 'Shift + Enter', desc: '换行' },
  { key: 'Esc', desc: '关闭弹窗 / 抽屉' },
  { key: 'Cmd/Ctrl + K', desc: '打开消息搜索' },
  { key: 'Cmd/Ctrl + /', desc: '显示/隐藏快捷键' },
  { key: 'Alt + C', desc: '切换技能 (客户)' },
  { key: 'Alt + H', desc: '申请转人工 (客户)' },
]
</script>

<template>
  <el-dialog v-model="dialogVisible" title="⌨️ 快捷键" width="500px" :show-close="true">
    <div class="kb-list">
      <div v-for="s in shortcuts" :key="s.key" class="kb-row">
        <kbd>{{ s.key }}</kbd>
        <span>{{ s.desc }}</span>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.kb-list { display: flex; flex-direction: column; gap: 12px; }
.kb-row { display: flex; align-items: center; gap: 12px; font-size: 14px; color: #303133; }
kbd {
  background: #f5f7fa;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 4px 10px;
  font-family: monospace;
  font-size: 12px;
  color: #303133;
  min-width: 110px;
  text-align: center;
  box-shadow: 0 1px 0 rgba(0,0,0,0.05);
}
</style>