import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 心屿用户端 —— Vite 8 配置（制作步骤文档 §5.8 第 3 条）
export default defineConfig({
  plugins: [
    vue(),
    Components({ resolvers: [ElementPlusResolver()], dts: false })
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      // SSE 关缓冲（制作步骤文档 §5.8 第 3 条）：
      // AI 陪伴对话走 POST /api/ai/chat/stream，开发代理必须逐帧透传，
      // 不得把 text/event-stream 压缩或等全部写完再返回，否则前端看不到流式效果。
      '/api/ai/chat/stream': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyRes', (proxyRes) => {
            delete proxyRes.headers['content-encoding']
            proxyRes.headers['cache-control'] = 'no-cache, no-transform'
            proxyRes.headers['x-accel-buffering'] = 'no'
          })
        }
      },
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
