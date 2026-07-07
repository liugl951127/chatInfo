<template>
  <div class="login-page">
    <el-card class="login-card" shadow="hover">
      <template #header>
        <div class="header">
          <span class="title">在线客服</span>
          <el-radio-group v-model="mode" size="small">
            <el-radio-button value="login">登录</el-radio-button>
            <el-radio-button value="register">注册</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <el-form :model="form" label-width="0" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" prefix-icon="User"
            size="large" :input-style="{ height: '44px' }" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" placeholder="密码" type="password" show-password
            prefix-icon="Lock" size="large" :input-style="{ height: '44px' }" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'">
          <el-input v-model="form.nickname" placeholder="昵称" prefix-icon="Avatar"
            size="large" :input-style="{ height: '44px' }" />
        </el-form-item>
        <el-button type="primary" class="submit-btn" :loading="loading" size="large"
          @click="submit">
          {{ mode === 'login' ? '登 录' : '注 册' }}
        </el-button>

        <div class="tips">
          <p>演示账号:</p>
          <p>客户: <code>customer1</code> / <code>123456</code></p>
          <p>坐席: <code>agent1</code> / <code>123456</code></p>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '' })

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
    ElMessage.success('登录成功')
    router.replace(data.role === 'AGENT' ? '/agent' : '/customer')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 380px;
  max-width: 100%;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.title {
  font-size: 18px;
  font-weight: bold;
}
.submit-btn {
  width: 100%;
}
:deep(.submit-btn) {
  height: 44px;
}
.tips {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  background: #f5f7fa;
  padding: 10px 12px;
  border-radius: 6px;
}
.tips code {
  color: #409eff;
  background: #fff;
  padding: 0 4px;
  border-radius: 3px;
}

@media (max-width: 480px) {
  .login-card {
    width: 100%;
  }
  .title {
    font-size: 16px;
  }
}
</style>