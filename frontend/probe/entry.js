// DOM 级取证入口（不属于产品代码，不参与 npm run build 的产物）。
//
// 为什么另开一个入口而不是直接用 index.html + main.js：探针要在挂载之后拿到 router 实例
// 才能「像用户一样」跳去 /me/posts 与 /user/23，而 main.js 挂载完就把 router 关在模块作用域里。
// 这里只做同样的事（pinia + router + ElementPlus + App.vue），外加两行对外暴露，
// 目的是让「组件真实渲染、真实发 HTTP 请求」这件事能被机器断言，而不是靠人眼看一眼。
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from '../src/App.vue'
import router from '../src/router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')

window.__probeRouter = router
window.__probeMounted = true
