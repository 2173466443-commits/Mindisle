<template>
  <div class="mi-shell">
    <header class="mi-top">
      <div class="mi-brand">
        <span class="mi-logo">🏝</span>
        <span class="mi-name">心屿</span>
        <span class="mi-slogan">校园心理陪伴社区</span>
      </div>
      <nav class="mi-nav">
        <router-link to="/feed">广场</router-link>
        <router-link to="/ai">屿灵</router-link>
        <router-link to="/emotion">情绪</router-link>
        <router-link to="/me">我的</router-link>
        <router-link to="/help" class="mi-help">需要帮助？</router-link>
      </nav>
      <div class="mi-status">
        <span class="mi-ver">{{ sysLine }}</span>
        <el-tooltip :content="backendTip" placement="bottom">
          <el-tag :type="backendUp ? 'success' : 'danger'" size="small" effect="dark">
            {{ backendUp ? '后端连接正常' : '后端未连接' }}
          </el-tag>
        </el-tooltip>
        <el-badge :value="notify.unread" :hidden="!notify.unread" class="mi-badge">
          <el-button link @click="notify.clear()">🔔</el-button>
        </el-badge>
        <span v-if="user.isLogged" class="mi-who">{{ who }}</span>
        <el-button v-if="user.isLogged" link @click="doLogout">退出</el-button>
        <el-button v-else link @click="$router.push({ name: 'login' })">登录</el-button>
      </div>
    </header>
    <main class="mi-main"><router-view /></main>
    <footer class="mi-foot">
      心屿 MindIsle 仅用于心理陪伴与倾诉，不构成医学诊断；危机情况请拨打 12356 或 120 / 110。
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { systemInfo } from '@/api/system'
import { logout } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { useNotifyStore } from '@/stores/notify'

const user = useUserStore()
const notify = useNotifyStore()
const router = useRouter()
const backendUp = ref(false)
const sys = ref(null)
let timer = null

// Gate2 要求界面能反映后端连接态：每 30s 静默探测一次真实的 GET /api/system/info。
// 后端并没有 /api/system/ping，用它会拿到 90006，别再改回去。
async function check() {
  try {
    sys.value = await systemInfo()
    backendUp.value = true
  } catch (e) {
    backendUp.value = false
    sys.value = null
  }
}

const who = computed(() => {
  const p = user.profile
  return p && p.nickname ? p.nickname : '屿友'
})
const sysLine = computed(() => (sys.value ? sys.value.app + ' v' + sys.value.version : ''))
const backendTip = computed(() => {
  if (!backendUp.value) return '探测不到 http://127.0.0.1:8080，请先启动后端（mvn spring-boot:run）'
  if (sys.value && sys.value.cacheMode === 'local') return '后端正常：缓存为 Caffeine 本地降级模式（Redis 未启用）'
  return '后端正常'
})

async function doLogout() {
  try {
    await logout()
  } catch (e) {
    /* 后端不可用时也必须能退出：本地令牌一定要清掉 */
  }
  user.clear()
  notify.clear()
  router.replace({ name: 'login' })
}

onMounted(() => {
  check()
  timer = setInterval(check, 30000)
})
onUnmounted(() => timer && clearInterval(timer))
</script>

<style scoped>
.mi-shell { min-height: 100vh; background: var(--mi-bg); color: var(--mi-text); display: flex; flex-direction: column; }
.mi-top {
  display: flex; align-items: center; gap: 24px;
  padding: 14px 28px; background: var(--mi-card);
  border-bottom: 1px solid var(--mi-border); position: sticky; top: 0; z-index: 10;
}
.mi-brand { display: flex; align-items: baseline; gap: 8px; }
.mi-logo { font-size: 22px; }
.mi-name { font-size: 20px; font-weight: 700; color: var(--mi-primary); letter-spacing: 2px; }
.mi-slogan { font-size: 12px; color: var(--mi-text-dim); }
.mi-nav { display: flex; gap: 18px; flex: 1; }
.mi-nav a { color: var(--mi-text-dim); text-decoration: none; font-size: 14px; }
.mi-nav a.router-link-active, .mi-nav a:hover { color: var(--mi-primary); }
.mi-nav .mi-help { color: var(--mi-anger); }
.mi-status { display: flex; align-items: center; gap: 14px; }
.mi-ver { font-size: 12px; color: var(--mi-text-dim); }
.mi-who { font-size: 13px; color: var(--mi-mist); }
.mi-main { padding: 24px 28px; max-width: 1080px; margin: 0 auto; width: 100%; flex: 1; }
.mi-foot { padding: 18px 28px; font-size: 12px; color: var(--mi-text-dim); text-align: center; border-top: 1px solid var(--mi-border); }
</style>
