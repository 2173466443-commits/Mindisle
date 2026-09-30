import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'

import 'element-plus/dist/index.css'
// EP 官方深色令牌表（html.dark 上的一整套 --el-*）。
// 2026-09-30 改版之后它只在「把主题开关拨回深色」时才生效，浅色默认态下是惰性的；
// 留着的理由是回退只需要动下面那一行，而不是再补一次 import。
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/theme.css'

// 全站主题开关（2026-09-30 改版）：默认浅色「笔记墙」，样式全在 styles/theme.css 的 :root 里。
// 上一版的需求 Q9 深色（午夜蓝 + 暖珊瑚）没有删：它在 theme.css 末尾的 html.dark:root 块里，
// 把下面这一行取消注释就整体退回深色，EP 那份 dark 令牌表也还留着，两处不用一起改。
// 开关只放在这一处，理由和以前一样：样式和它的开关在同一支文件里，不会被顺手删掉一半。
// document.documentElement.classList.add('dark')

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
