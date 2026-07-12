/**
 * polyfills.js - 浏览器 API 兼容垫片 (V3 跨浏览器支持).
 * ----------------------------------------------------------------------------
 * 用法: 在 main.js 顶部 import './utils/polyfills' (放在 Vue 之前)
 *
 * 策略:
 *   - 现代 API 不存在时, 兜底为 NoOp 或简单实现
 *   - 不引入大库 (core-js 增加 100KB+), 仅按需补齐
 *
 * 覆盖范围:
 *   - Array.prototype.at: ES2022 → 用 arr[idx >= 0 ? idx : arr.length + idx] 代替
 *   - Object.hasOwn: ES2022 → Object.prototype.hasOwnProperty.call
 *   - globalThis: ES2020 → typeof window !== 'undefined' ? window : this
 *   - structuredClone: 现代 → JSON 序列化兜底
 *   - ResizeObserver: 旧 Safari → 加 queueMicrotask 兜底
 *   - matchMedia: 旧 Android → 返 noop MediaQueryList
 *   - BroadcastChannel: 旧 → 用 storage event 兜底
 *   - scrollTo: 旧 Safari (无 smooth) → 直接 scrollTop
 *   - String.prototype.replaceAll: ES2021 → String.prototype.replace with /g
 */

// ========== Array.prototype.at ==========
if (!Array.prototype.at) {
  Array.prototype.at = function (idx) {
    const arr = this
    const n = idx >= 0 ? idx : arr.length + idx
    return n >= 0 && n < arr.length ? arr[n] : undefined
  }
}

// ========== Object.hasOwn ==========
if (!Object.hasOwn) {
  Object.hasOwn = function (obj, prop) {
    return Object.prototype.hasOwnProperty.call(obj, prop)
  }
}

// ========== globalThis ==========
if (typeof globalThis === 'undefined') {
  if (typeof window !== 'undefined') {
    (typeof self !== 'undefined' ? self : this).globalThis = window
  } else if (typeof self !== 'undefined') {
    (this).globalThis = self
  } else {
    (this).globalThis = this
  }
}

// ========== structuredClone 简易兜底 ==========
// 仅支持普通对象, 不支持 Date/Map/Set (用 JSON 序列化)
// 业务场景: LocalStorage / IndexedDB 替代品
if (typeof structuredClone === 'undefined') {
  globalThis.structuredClone = function (obj) {
    // 简单 JSON 兜底 (绝大多数业务数据可序列化)
    try {
      return JSON.parse(JSON.stringify(obj))
    } catch (e) {
      throw new Error('structuredClone 兜底失败: ' + e.message)
    }
  }
}

// ========== String.prototype.replaceAll ==========
if (!String.prototype.replaceAll) {
  String.prototype.replaceAll = function (search, replacement) {
    if (search instanceof RegExp) {
      // RegExp 需校验 global flag
      if (!search.global) {
        throw new TypeError('replaceAll 的 RegExp 必须带 /g flag')
      }
      return this.replace(search, replacement)
    }
    // 字符串: 转义特殊字符, 然后全局替换
    const escaped = String(search).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    return this.replace(new RegExp(escaped, 'g'), String(replacement))
  }
}

// ========== Element.prototype.scrollTo 兜底 ==========
// 旧 Safari 不支持 behavior: 'smooth', 直接跳
if (typeof Element !== 'undefined' && !Element.prototype.scrollTo) {
  Element.prototype.scrollTo = function (x, y) {
    if (typeof x === 'object' && x !== null) {
      // {top, left, behavior} 形式
      this.scrollTop = x.top || 0
      this.scrollLeft = x.left || 0
    } else {
      this.scrollTop = y || 0
      this.scrollLeft = x || 0
    }
  }
}

// ========== matchMedia 兜底 (旧 Android WebView) ==========
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = function (query) {
    return {
      matches: false,
      media: query,
      onchange: null,
      addListener: function () {},  // 旧 API
      removeListener: function () {},
      addEventListener: function () {},
      removeEventListener: function () {},
      dispatchEvent: function () { return false },
    }
  }
}

// ========== ResizeObserver 兜底 (旧 Safari < 13.1) ==========
if (typeof window !== 'undefined' && !window.ResizeObserver) {
  window.ResizeObserver = class ResizeObserver {
    constructor(cb) { this.cb = cb }
    observe() { /* noop */ }
    unobserve() { /* noop */ }
    disconnect() { /* noop */ }
  }
}

// ========== BroadcastChannel 兜底 (旧浏览器 / 微信部分版本) ==========
if (typeof window !== 'undefined' && !window.BroadcastChannel) {
  // 简易兜底: 跨 tab 通信用 storage event
  window.BroadcastChannel = class BroadcastChannel {
    constructor(name) {
      this.name = name
      this._listeners = []
      // 监听 storage 事件模拟
      this._handler = (e) => {
        if (e.key === `__bc_${name}`) {
          try {
            const data = JSON.parse(e.newValue)
            this._listeners.forEach(fn => {
              try { fn({ data }) } catch (_) {}
            })
          } catch (_) {}
        }
      }
      window.addEventListener('storage', this._handler)
    }
    postMessage(data) {
      try {
        localStorage.setItem(`__bc_${this.name}`, JSON.stringify(data))
        // 立即清除 (其他 tab 也能收到)
        setTimeout(() => localStorage.removeItem(`__bc_${this.name}`), 100)
      } catch (_) {}
    }
    addEventListener(type, fn) {
      if (type === 'message') this._listeners.push(fn)
    }
    removeEventListener(type, fn) {
      if (type === 'message') {
        this._listeners = this._listeners.filter(f => f !== fn)
      }
    }
    close() {
      window.removeEventListener('storage', this._handler)
      this._listeners = []
    }
  }
}

// ========== requestIdleCallback 兜底 (Safari < 16.4) ==========
if (typeof window !== 'undefined' && !window.requestIdleCallback) {
  window.requestIdleCallback = function (cb) {
    return setTimeout(() => cb({ didTimeout: false, timeRemaining: () => 50 }), 1)
  }
  window.cancelIdleCallback = function (id) {
    clearTimeout(id)
  }
}

// ========== queueMicrotask 兜底 (旧 Node/浏览器) ==========
if (typeof globalThis.queueMicrotask === 'undefined') {
  globalThis.queueMicrotask = function (cb) {
    return Promise.resolve().then(cb)
  }
}

// ========== crypto.randomUUID 兜底 (Safari < 15.4) ==========
if (typeof crypto === 'undefined' || !crypto.randomUUID) {
  // 用 Math.random 兜底 (非加密安全, 仅用于会话 ID)
  if (typeof crypto === 'undefined') {
    globalThis.crypto = {}
  }
  if (!crypto.randomUUID) {
    crypto.randomUUID = function () {
      return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0
        const v = c === 'x' ? r : (r & 0x3) | 0x8
        return v.toString(16)
      })
    }
  }
}

// ========== console.debug / console.trace 兜底 (IE 11 兼容) ==========
if (typeof console !== 'undefined') {
  if (typeof console.debug === 'undefined') console.debug = console.log
  if (typeof console.trace === 'undefined') console.trace = console.log
}

export default {
  // 标记 polyfills 已加载
  version: '3.1.0',
  loaded: true,
}
