import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'

import 'element-plus/dist/index.css'
import './styles/theme.css'

// 管理端与用户端是两套独立构建：不同端口（5174 / 5173）、不同 localStorage 键，
// 避免同一个浏览器里两种身份互相覆盖 token。
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
