<script setup>
/**
 * Login.vue - 现代化登录页.
 * ----------------------------------------------------------------------------
 * v6 风格: 渐变背景 + 毛玻璃卡片 + blob 动效 + 角色快速选择.
 */
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Avatar, ChatDotRound, Service } from '@element-plus/icons-vue'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '' })

/** 角色快速填充 (演示用) */
const quickFill = [
  { label: '客户',  icon: ChatDotRound, color: '#409EFF', user: 'customer1', pass: '123456' },
  { label: '客服',  icon: Service,      color: '#67C23A', user: 'agent1',    pass: '123456' },
]

function pickDemo(d) {
  form.username = d.user
  form.password = d.pass
}

async function submit() {
  if (!form.username || !form.password) {
    return ElMessage.warning('请填写用户名和密码')
  }
  if (mode.value === 'register' && !form.nickname) {
    return ElMessage.warning('请填写昵称')
  }
  loading.value = true
  try {
    const data = mode.value === 'login'
      ? await authApi.login({ username: form.username, password: form.password })
      : await authApi.register({ username: form.username, password: form.password, nickname: form.nickname })
    userStore.setLogin(data)
    ElMessage.success('登录成功, ' + (data.nickname || data.username))
    router.replace(data.role === 'AGENT' ? '/agent' : '/customer')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- 背景动效 -->
    <div class="bg-blobs">
      <div class="blob blob-1"></div>
      <div class="blob blob-2"></div>
      <div class="blob blob-3"></div>
    </div>

    <!-- 左侧品牌 -->
    <div class="brand-panel">
      <div class="brand-content">
        <div class="brand-logo">💬</div>
        <h1 class="brand-title">智能客服平台</h1>
        <p class="brand-subtitle">4 阶段 AI 驱动 · 预见式服务 · 数字孪生 360</p>
        <div class="brand-features">
          <div class="feature-item">
            <span class="feature-icon">🤖</span>
            <div>
              <div class="feature-title">自研 AI</div>
              <div class="feature-desc">Java 原生, 0-1ms 响应</div>
            </div>
          </div>
          <div class="feature-item">
            <span class="feature-icon">📹</span>
            <div>
              <div class="feature-title">视频会话</div>
              <div class="feature-desc">WebRTC 1v1 + 屏幕共享</div>
            </div>
          </div>
          <div class="feature-item">
            <span class="feature-icon">📞</span>
            <div>
              <div class="feature-title">智能电话</div>
              <div class="feature-desc">AI 接听 + 情感分析</div>
            </div>
          </div>
          <div class="feature-item">
            <span class="feature-icon">🔮</span>
            <div>
              <div class="feature-title">预见式关怀</div>
              <div class="feature-desc">主动推送 + 防串线</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 右侧登录卡片 -->
    <div class="login-panel">
      <div class="login-card">
        <div class="login-header">
          <h2>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
          <p>{{ mode === 'login' ? '登录后开启智能服务' : '几秒钟即可注册' }}</p>
        </div>

        <el-radio-group v-model="mode" class="mode-switch">
          <el-radio-button value="login">登录</el-radio-button>
          <el-radio-button value="register">注册</el-radio-button>
        </el-radio-group>

        <el-form @submit.prevent="submit">
          <el-form-item>
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User"
                      size="large" autocomplete="username" />
          </el-form-item>
          <el-form-item>
            <el-input v-model="form.password" placeholder="密码" type="password" show-password
                      :prefix-icon="Lock" size="large" autocomplete="current-password" />
          </el-form-item>
          <el-form-item v-if="mode === 'register'">
            <el-input v-model="form.nickname" placeholder="昵称" :prefix-icon="Avatar" size="large" />
          </el-form-item>
          <el-button type="primary" class="submit-btn" :loading="loading" size="large" round @click="submit">
            {{ mode === 'login' ? '登 录' : '注 册' }}
          </el-button>
        </el-form>

        <!-- 演示账号快速填充 -->
        <div class="demo-section">
          <div class="demo-title">🎯 演示账号 (一键填充)</div>
          <div class="demo-list">
            <div v-for="d in quickFill" :key="d.user" class="demo-item" :style="{ '--c': d.color }"
                 @click="pickDemo(d)">
              <el-icon class="demo-icon"><component :is="d.icon" /></el-icon>
              <div class="demo-meta">
                <div class="demo-label">{{ d.label }}</div>
                <div class="demo-user">{{ d.user }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 50%, #f093fb 100%);
  overflow: hidden;
}

/* 背景 blob */
.bg-blobs {
  position: absolute; inset: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 0;
}
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.5;
  animation: blob-float 16s ease-in-out infinite;
}
.blob-1 {
  width: 400px; height: 400px;
  background: linear-gradient(135deg, #409EFF, #67C23A);
  top: -150px; left: -100px;
}
.blob-2 {
  width: 350px; height: 350px;
  background: linear-gradient(135deg, #F56C6C, #E6A23C);
  bottom: -120px; right: -100px;
  animation-delay: -5s;
}
.blob-3 {
  width: 250px; height: 250px;
  background: linear-gradient(135deg, #E6A23C, #409EFF);
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  animation-delay: -10s;
}
@keyframes blob-float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(30px, -30px) scale(1.15); }
}

/* 左侧品牌面板 (仅桌面显示) */
.brand-panel {
  position: relative; z-index: 1;
  flex: 1;
  display: flex; align-items: center; justify-content: center;
  padding: 40px;
  color: #fff;
}
.brand-content {
  max-width: 480px;
  animation: brand-in 0.8s ease-out;
}
@keyframes brand-in {
  from { opacity: 0; transform: translateX(-30px); }
  to { opacity: 1; transform: translateX(0); }
}
.brand-logo {
  font-size: 80px;
  margin-bottom: 16px;
  filter: drop-shadow(0 8px 20px rgba(255, 255, 255, 0.3));
  animation: logo-pulse 3s ease-in-out infinite;
}
@keyframes logo-pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.08); }
}
.brand-title {
  margin: 0 0 8px;
  font-size: 42px; font-weight: 700;
  letter-spacing: 1px;
  text-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}
.brand-subtitle {
  margin: 0 0 40px;
  font-size: 16px; opacity: 0.85;
}
.brand-features {
  display: grid; grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}
.feature-item {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px;
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 12px;
  transition: all 0.2s;
}
.feature-item:hover {
  background: rgba(255, 255, 255, 0.25);
  transform: translateY(-2px);
}
.feature-icon { font-size: 28px; }
.feature-title { font-size: 14px; font-weight: 600; margin-bottom: 2px; }
.feature-desc { font-size: 11px; opacity: 0.8; }

/* 右侧登录面板 */
.login-panel {
  position: relative; z-index: 1;
  flex: 0 0 460px;
  display: flex; align-items: center; justify-content: center;
  padding: 40px 20px;
}
.login-card {
  width: 100%;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(20px);
  border-radius: 24px;
  padding: 36px 32px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
  animation: card-in 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(20px) scale(0.95); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.login-header {
  text-align: center;
  margin-bottom: 24px;
}
.login-header h2 {
  margin: 0 0 6px;
  font-size: 26px; font-weight: 700;
  background: linear-gradient(135deg, #303133, #409EFF);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.login-header p {
  margin: 0;
  font-size: 13px; color: #909399;
}
.mode-switch {
  width: 100%;
  margin-bottom: 20px;
}
:deep(.mode-switch .el-radio-button__inner) {
  width: 50%;
}
:deep(.el-form-item) {
  margin-bottom: 16px;
}
:deep(.el-input__wrapper) {
  border-radius: 12px;
  padding: 4px 12px;
  background: #f5f7fa;
}
:deep(.el-input__inner) {
  height: 40px;
  font-size: 14px;
}
.submit-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  background: linear-gradient(135deg, #409EFF, #67C23A);
  border: none;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.3);
  transition: transform 0.2s;
  margin-top: 8px;
}
.submit-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(64, 158, 255, 0.4);
}

/* 演示账号 */
.demo-section {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px dashed #ebeef5;
}
.demo-title {
  font-size: 12px; color: #909399;
  margin-bottom: 10px;
  text-align: center;
}
.demo-list {
  display: grid; grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.demo-item {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px;
  background: #fafbfc;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
}
.demo-item:hover {
  border-color: var(--c);
  background: #fff;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}
.demo-icon {
  font-size: 24px; color: var(--c);
}
.demo-label {
  font-size: 12px; color: #606266; font-weight: 600;
}
.demo-user {
  font-size: 11px; color: #909399;
  font-family: 'Courier New', monospace;
}

/* 移动端 */
@media (max-width: 768px) {
  .brand-panel { display: none; }
  .login-panel { flex: 1; }
}
</style>