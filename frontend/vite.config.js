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
  // ------------------------------------------------------------------------
  // 预构建清单必须写全（2026-09-24 真浏览器取证之后加的，少这一节就会随机白屏）。
  // 现象：点 /ai 时 Network 里出现 504 Outdated Optimize Dep，报的是
  //   /node_modules/.vite/deps/element-plus_es_components_{tooltip,tag,loading}_style_css.js?v=<旧哈希>
  // 紧接着 pageerror: Failed to fetch dynamically imported module: .../views/ai/ChatView.vue,
  // 用户侧的观感就是「点了 AI 没反应」—— 这就是「AI 对话没做好」的真实来源之一。
  // 根因：这些依赖只在懒加载路由被打开的那一刻才被发现（unplugin-vue-components 是在 transform
  // 期注入 style/css 的，Vite 启动期的扫描器看不到），于是 Vite 中途重跑一次预构建、换掉 deps 哈希，
  // 此时已在飞行中的旧 URL 全部 504，整块动态 import 失败。
  // 处理：把「首屏爬不到、点开才会到」的依赖全部列进 include，让它们启动时就预构建完。
  // 下面这份清单是从 src/**（*.vue/*.js）里的 el-* 标签与显式 import 反推出来的；
  // 以后新页面用到新组件，往这里补一行，别指望 Vite 自己在运行期发现。
  // echarts 走按需入口（core + charts/components/renderers），词云用 src/vendor 副本，都不引全量包，
  // 所以清单里没有出现裸 'echarts'。
  optimizeDeps: {
    include: [
      'vue', 'vue-router', 'pinia', 'axios',
      'element-plus/es',
      'element-plus/es/components/base/style/css',
      'element-plus/es/components/alert/style/css',
      'element-plus/es/components/avatar/style/css',
      'element-plus/es/components/badge/style/css',
      'element-plus/es/components/button/style/css',
      'element-plus/es/components/checkbox/style/css',
      'element-plus/es/components/date-picker/style/css',
      'element-plus/es/components/descriptions/style/css',
      'element-plus/es/components/dialog/style/css',
      'element-plus/es/components/empty/style/css',
      'element-plus/es/components/form/style/css',
      'element-plus/es/components/form-item/style/css',
      'element-plus/es/components/icon/style/css',
      'element-plus/es/components/image/style/css',
      'element-plus/es/components/input/style/css',
      'element-plus/es/components/loading/style/css',
      'element-plus/es/components/popover/style/css',
      'element-plus/es/components/radio/style/css',
      'element-plus/es/components/radio-button/style/css',
      'element-plus/es/components/radio-group/style/css',
      'element-plus/es/components/scrollbar/style/css',
      'element-plus/es/components/select/style/css',
      'element-plus/es/components/skeleton/style/css',
      'element-plus/es/components/slider/style/css',
      'element-plus/es/components/switch/style/css',
      'element-plus/es/components/table/style/css',
      'element-plus/es/components/table-column/style/css',
      'element-plus/es/components/tag/style/css',
      'element-plus/es/components/tooltip/style/css',
      'element-plus/es/components/upload/style/css',
      'element-plus/es/components/message/style/css',
      'element-plus/es/components/message-box/style/css',
      '@element-plus/icons-vue',
      'echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers',
      'sockjs-client', '@stomp/stompjs'
    ]
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
