import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 心屿管理端 —— 独立 Vite 工程（手册 §5.7 T2.12：与用户端分端口、分构建产物）
//
// 这里刻意【没有】 unplugin-vue-components + ElementPlusResolver，虽然依赖还装在 package.json 里：
// main.js 已经 app.use(ElementPlus) 全量注册并 import 了 element-plus/dist/index.css，
// resolver 再往每个 .vue 里注入一份 element-plus/es/components/x/style/css，
// 等于同一套组件存在两个实例来源。阶段 4 那次「点开新页面随机 504 Outdated Optimize Dep 白屏」
// 就是这个双轨造成的：预置依赖图边新增组件边被改写，浏览器拿到半新的 chunk。
// 全量注册的代价是产物大一些，换来的是「新增一个页面不会改依赖图」——管理端优先要后者。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  // 依赖预打包清单写死：这样 vite 启动时一次算完，跑起来不会再因为「发现新依赖」而重启优化器。
  optimizeDeps: {
    include: [
      'vue', 'vue-router', 'pinia', 'axios', 'element-plus',
      'echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'
    ]
  },
  build: {
    chunkSizeWarningLimit: 1200
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})