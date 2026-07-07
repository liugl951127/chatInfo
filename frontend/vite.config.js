import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import path from 'node:path'

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
  server: {
    port: 5173,
    host: '0.0.0.0',
    proxy: {
      // 网关在 9000, 全部走后端入口
      '/auth': { target: 'http://localhost:9000', changeOrigin: true },
      '/api':  { target: 'http://localhost:9000', changeOrigin: true, ws: true },
      '/ws':   { target: 'http://localhost:9000', changeOrigin: true, ws: true }
    }
  }
})