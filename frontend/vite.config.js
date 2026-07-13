import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import path from 'node:path'

/**
 * Vite 构建配置.
 * ----------------------------------------------------------------------------
 * build.target: 浏览器兼容目标.
 *   - ES2018 (默认): 现代浏览器 (Chrome 63+/Firefox 58+/Safari 11.1+/Edge 79+)
 *     支持: 异步/等待/展开运算符/可选链/空值合并/异步迭代
 *     不支持: 私有字段 #/Object.hasOwn/Array.at/structuredClone
 *   - 目标: 兼容 95%+ 浏览器, 但避免用过于新的 API
 *
 * 浏览器支持矩阵:
 *   - Chrome 90+ (2021)      ✓
 *   - Firefox 88+ (2021)     ✓
 *   - Safari 14+ (2020)      ✓
 *   - Edge 90+ (2021)        ✓
 *   - 微信内置浏览器 (X5/Blink)  ✓
 *   - 旧版 Edge 18-          ✗ (需降级到 es2015)
 *
 * 代码规范 (供开发者参考):
 *   - 用 const/let, 避免 var
 *   - 用箭头函数, 但需要 this 时用普通函数
 *   - 模板字符串: 全部支持
 *   - Promise/async/await: 全部支持
 *   - 解构: 全部支持
 *   - 展开运算符: 全部支持
 *   - 可选链 ?.: 全部支持 (ES2020)
 *   - 空值合并 ??: 全部支持 (ES2020)
 *
 * 不允许的 (兼容性差):
 *   - 私有字段 #field: 仅 ES2022+
 *   - Array.at(): 仅 ES2022+
 *   - Object.hasOwn(): 仅 ES2022+
 *   - structuredClone(): 仅现代
 *   - globalThis: 仅 ES2020+
 *   - Top-level await: 仅 ES2022+ (Vite 默认开启, 谨慎使用)
 */
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  // 浏览器目标: 兼容 ES2018 (Chrome 63+, Firefox 58+, Safari 11.1+)
  // 排除过老的 IE 11, 但支持现代浏览器 + 微信内置
  build: {
    target: 'es2018',
    cssTarget: 'chrome90',
    // 拆包: 第三方库单独 chunk
    rollupOptions: {
      output: {
        manualChunks: {
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          'element-plus': ['element-plus', '@element-plus/icons-vue'],
          'stomp': ['@stomp/stompjs'],
        }
      }
    },
    // sourcemap 仅 dev
    sourcemap: false,
    // 压缩
    minify: 'esbuild',
  },
  // esbuild 配置 (作为 transform fallback)
  esbuild: {
    target: 'es2018',
    // 支持现代语法但保持兼容性
    supported: {
    }
  },
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      // V3.2 联调: 指向 cs-gateway (9000) - 统一入口
      '/auth': { target: 'http://localhost:9000', changeOrigin: true },
      '/api':  { target: 'http://localhost:9000', changeOrigin: true, ws: true },
      '/ws':   { target: 'http://localhost:9000', changeOrigin: true, ws: true }
    }
  }
})
