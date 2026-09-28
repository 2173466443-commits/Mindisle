<template>
  <router-view />
</template>

<script setup>
import { onMounted, onUnmounted, watch } from 'vue'
import { connectWs, disconnectWs } from '@/composables/useWs'
import { useUserStore } from '@/stores/user'
import { usePmStore } from '@/stores/pm'

// 根组件保持极简：布局由各 layout / view 自己负责，
// 这样 /help（危机场景）等免登录页不必套主框架。
//
// 【但实时通道挂在这里，而不是挂在 BasicLayout 或私信页面里】三条理由，每条都比「顺手」重要：
// ① 未读角标在顶栏，而顶栏所在的主框架只在内层路由存在时才挂载。放在布局里，
//    用户停在 /login 或 /help 上就收不到任何帧，而危机提醒恰恰可能在这些页面上被需要。
// ② 私信 store 的 bindWs() 必须是**全局唯一一次**订阅：两个视图各自 subscribe 的话，
//    同一帧会被处理两遍，未读数加两次（stores/pm.js#bindWs 的注释点明了这件事）。
// ③ 连接的生命周期跟着「有没有令牌」走，而不是跟着「有没有打开私信页」走。
//    后者等于每次离开私信页断一次线，再进来又要重连＋补拉，白付一遍往返。
//
// 这里刻意不 import 任何视图层的东西：根组件的依赖越薄，越不会出现
// 「循环 import 把整个 app 挂不起来」这种一崩崩全站的事故。
const user = useUserStore()
const pm = usePmStore()
let unbind = null

function syncTransport() {
  if (user.isLogged) {
    connectWs()
    // connectWs 是幂等的（有 client 就直接返回），bindWs 只在这里配一次，
    // 所以「令牌被刷新」那次触发不会把订阅叠成两份。
    if (!unbind) unbind = pm.bindWs()
  } else {
    if (unbind) {
      unbind()
      unbind = null
    }
    disconnectWs()
  }
}

onMounted(() => {
  // user.token 在 store 初始化时就从 localStorage 读进来了，所以刷新页面这一刻
  // isLogged 已经是真值，不必等任何接口回来再连。
  syncTransport()
})

// 只认这两个值：登录、退出、以及 http 层轮换/清空令牌，都会路过这里。
// 不 watch「登录成功」事件 —— 事件路径有两条以上时必有一条会漏，这是这个项目栽过的。
watch(() => [user.isLogged, user.token], syncTransport)

onUnmounted(() => {
  if (unbind) unbind()
  disconnectWs()
})
</script>
