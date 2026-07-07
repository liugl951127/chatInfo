<template>
  <router-view />
</template>

<script setup>
// 全局布局 (空, 各页面自带布局)
</script>

<style>
:root {
  --safe-top: env(safe-area-inset-top, 0px);
  --safe-bottom: env(safe-area-inset-bottom, 0px);
  /* Android Chrome / 旧 iOS fallback */
  --safe-top-legacy: constant(safe-area-inset-top, 0px);
  --safe-bottom-legacy: constant(safe-area-inset-bottom, 0px);
}

html,
body,
#app {
  height: 100%;
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  background: #f5f7fa;
  -webkit-tap-highlight-color: transparent;
  -webkit-touch-callout: none;
  overscroll-behavior-y: none;
}

/* 移动端默认字号放大 (避免 iOS 自动缩放导致的输入框跳动) */
@media (max-width: 768px) {
  html {
    font-size: 16px;
  }
  body {
    font-size: 14px;
  }
}

@media (min-width: 769px) {
  html {
    font-size: 14px;
  }
}

/* iOS 触底反弹滚动 */
.scroll-smooth {
  -webkit-overflow-scrolling: touch;
}

/* Element Plus 在小屏的边距微调 */
@media (max-width: 480px) {
  .el-message {
    min-width: 280px !important;
    max-width: 90vw !important;
  }
  .el-dialog {
    width: 92vw !important;
  }
  .el-rate {
    font-size: 28px !important;
  }
}
</style>