/**
 * error-handler.js - 统一错误处理工具.
 * ----------------------------------------------------------------------------
 * 集中处理 API 错误, 网络错误, 业务错误.
 * 替代散落的 ElMessage.error().
 *
 * 错误码语义 (后端 ApiResponse):
 *   - 400: 参数错误 → 友好提示 + 不重试
 *   - 401: 未登录 → 跳转 /login
 *   - 403: 无权访问 → 提示联系管理员
 *   - 404: 不存在 → 提示
 *   - 409: 冲突 (CAS) → 提示已被抢, 刷新
 *   - 429: 限流 → 提示稍后重试, 显示 retry-after
 *   - 500+: 系统错误 → 通用提示 + 记录
 *   - 网络: 网络错误 → 提示检查网络
 */
import { ElMessage, ElMessageBox } from 'element-plus'

const CODE_MESSAGES = {
  400: '请求参数有误',
  401: '登录已过期, 请重新登录',
  403: '无权访问此资源',
  404: '资源不存在',
  409: '资源冲突, 请刷新后重试',
  429: '请求过于频繁, 请稍后再试',
  500: '服务器内部错误',
  502: '服务暂时不可用',
  503: '服务维护中',
  504: '服务响应超时',
}

export function getErrorMessage(err) {
  if (!err) return '未知错误'
  // 后端 ApiResponse 业务错误
  if (err.code && err.code !== 200 && err.message) {
    return err.message
  }
  // HTTP 错误
  if (err.response?.status) {
    const status = err.response.status
    return CODE_MESSAGES[status] || `HTTP ${status}`
  }
  // 网络错误
  if (err.code === 'ECONNABORTED' || err.message?.includes('timeout')) {
    return '请求超时, 请重试'
  }
  if (err.message === 'Network Error' || !navigator.onLine) {
    return '网络连接失败, 请检查网络'
  }
  return err.message || '操作失败'
}

/**
 * 处理错误 + 显示提示.
 * @returns boolean 是否已显示提示
 */
export function handleError(err, options = {}) {
  const { showType = 'message', duration = 3000, customMessage } = options
  const msg = customMessage || getErrorMessage(err)
  const isAuth = err?.code === 401 || err?.response?.status === 401

  if (isAuth && !options.silent) {
    // 401 自动跳转登录
    ElMessageBox.confirm('登录已过期, 是否重新登录?', '会话过期', {
      confirmButtonText: '重新登录',
      cancelButtonText: '取消',
      type: 'warning',
    }).then(() => {
      window.location.href = '/login'
    }).catch(() => {})
    return true
  }

  if (options.silent) return false
  if (showType === 'box') {
    ElMessageBox.alert(msg, '操作失败', { type: 'error' })
  } else {
    ElMessage({ type: 'error', message: msg, duration, showClose: true })
  }
  return true
}

/**
 * 成功提示 (统一).
 */
export function showSuccess(msg, duration = 2500) {
  ElMessage({ type: 'success', message: msg, duration })
}

/**
 * 警告提示.
 */
export function showWarn(msg, duration = 3000) {
  ElMessage({ type: 'warning', message: msg, duration, showClose: true })
}

/**
 * 信息提示.
 */
export function showInfo(msg, duration = 3000) {
  ElMessage({ type: 'info', message: msg, duration })
}