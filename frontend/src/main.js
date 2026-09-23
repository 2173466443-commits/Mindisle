import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'

import 'element-plus/dist/index.css'
// EP 官方深色令牌表（html.dark 上的一整套 --el-*）。必须在 theme.css 之前：
// theme.css 里的覆盖用的是 html.dark:root，特异度更高，谁先谁后都压得住它，
// 但 color-scheme: dark 这件事得先落地，原生滚动条/表单控件才不会在首帧闪白。
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/theme.css'

// 全站深色（需求 Q9）：这里加类，index.html 里不写 —— 构建产物只有一个入口，
// 放在 main.js 里改，等于「样式和它的开关在同一处」，不会哪天被人顺手删掉一半。
document.documentElement.classList.add('dark')

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
