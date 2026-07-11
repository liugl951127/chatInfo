<script setup>
/**
 * DragUploadOverlay.vue - 拖拽上传遮罩.
 * 全屏高亮 + 提示, 配合 useDragUpload 使用.
 */
defineProps({
  active: { type: Boolean, default: false },
})
</script>

<template>
  <Transition name="drag-fade">
    <div v-if="active" class="drag-overlay">
      <div class="drag-box">
        <div class="drag-icon">📎</div>
        <div class="drag-title">松开上传文件</div>
        <div class="drag-sub">支持图片 / 文档 / 压缩包</div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.drag-overlay {
  position: absolute; inset: 0;
  background: rgba(64, 158, 255, 0.15);
  backdrop-filter: blur(4px);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000;
  pointer-events: none;
}
.drag-box {
  background: #fff;
  border: 3px dashed #409EFF;
  border-radius: 20px;
  padding: 40px 60px;
  text-align: center;
  box-shadow: 0 8px 32px rgba(64, 158, 255, 0.3);
}
.drag-icon { font-size: 64px; }
.drag-title { font-size: 20px; font-weight: 600; color: #303133; margin: 12px 0 6px; }
.drag-sub { font-size: 13px; color: #909399; }
.drag-fade-enter-active, .drag-fade-leave-active { transition: all 0.2s; }
.drag-fade-enter-from, .drag-fade-leave-to { opacity: 0; }
.drag-fade-enter-from .drag-box, .drag-fade-leave-to .drag-box {
  transform: scale(0.9);
}
</style>