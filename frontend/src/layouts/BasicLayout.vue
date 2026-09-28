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
        <!-- 站内搜索（任务 T3.9）：搜索要一个常驻入口，放在「广场」旁边是因为它俩都是「找内容」的起点；
             不做成顶栏内嵌输入框是刻意的 —— 顶栏每个页面都挂着，一个内嵌框要么全站常驻一个搜索状态，
             要么在每个页面各自实现一遍跳转。先要一条能用的路。 -->
        <router-link to="/search">搜索</router-link>
        <router-link to="/publish">发布</router-link>
        <router-link to="/ai">屿屿</router-link>
        <!-- 私信（任务 T5.5 · 界面 U9）：入口要常驻，理由和铃铛是同一句 —— 「别人找你说话」这件事
             不该藏在「我的」页面第三层按钮后面。角标直接读 store 的 unreadTotal，不在这颗角标上另开请求：
             它跟着下面那个 30s 心跳一起刷，而 WS 活着的时候 store 自己就会被帧推着更新。 -->
        <el-badge :value="pm.unreadTotal" :max="99" :hidden="!pm.unreadTotal" class="mi-nav-badge">
          <router-link to="/chat">私信</router-link>
        </el-badge>
        <router-link to="/emotion">情绪</router-link>
        <router-link to="/me">我的</router-link>
        <!-- 隐私中心（任务 T4.21）放进入口，是因为「导出自己的数据 / 注销账号」这类权利，
         藏在「我的」页面第三层按钮上就等于没提供。它是账号级退出与数据携带权的唯一通道，值得一个常驻位。 -->
        <router-link to="/privacy">隐私</router-link>
        <router-link to="/help" class="mi-help">需要帮助？</router-link>
      </nav>
      <div class="mi-status">
        <span class="mi-ver">{{ sysLine }}</span>
        <el-tooltip :content="backendTip" placement="bottom">
          <el-tag :type="backendUp ? 'success' : 'danger'" size="small" effect="dark">
            {{ backendUp ? '后端连接正常' : '后端未连接' }}
          </el-tag>
        </el-tooltip>
        <!-- 铃铛（任务 T3.11-b · FR9.1 红点 + FR9.2 列表与一键已读）。
             点开才拉列表：顶栏每个页面都挂着，未登录态和游客态不该各发一次无谓请求；
             未读数字则跟着 30s 的心跳一起轻量刷新（refreshUnread 只取 unreadCount）。
             未登录时整块不渲染，而不是渲染一个点开必 401 的空壳。 -->
        <el-popover v-if="user.isLogged" ref="bell" placement="bottom-end" :width="352"
          trigger="click" popper-class="mi-notify-popper" @show="onBellShow">
          <template #reference>
            <el-badge :value="notify.unread" :max="99" :hidden="!notify.unread" class="mi-badge">
              <el-button link class="mi-bell" title="通知">🔔</el-button>
            </el-badge>
          </template>
          <div class="mi-notify">
            <div class="mi-notify-head">
              <span class="mi-notify-title">通知</span>
              <span class="mi-notify-count">{{ notify.unread ? notify.unread + ' 条未读' : '暂无未读' }}</span>
              <el-button v-if="notify.unread > 0" link type="primary" :loading="marking" @click="markAll">
                全部已读
              </el-button>
            </div>
            <el-scrollbar max-height="336px">
              <div v-if="notify.loading && !notify.items.length" class="mi-notify-blank">正在读取…</div>
              <div v-else-if="notify.error" class="mi-notify-blank">{{ notify.error }}</div>
              <div v-else-if="!notify.items.length" class="mi-notify-blank">还没有人找你。去广场发一条，或先给别人的帖子点个赞。</div>
              <ul v-else class="mi-notify-list">
                <li v-for="it in notify.items" :key="it.id" :class="{ 'is-unread': !it.read }"
                  class="mi-notify-item" @click="openItem(it)">
                  <span class="mi-notify-icon">{{ notifyIcon(it.type) }}</span>
                  <span class="mi-notify-body">
                    <span class="mi-notify-line">{{ it.title }}</span>
                    <span v-if="it.content" class="mi-notify-sub">{{ it.content }}</span>
                    <span class="mi-notify-meta">{{ it.typeLabel }} · {{ fromNow(it.createdAt) }}</span>
                  </span>
                </li>
              </ul>
              <el-button v-if="notify.hasMore" link class="mi-notify-more" :loading="notify.loading"
                @click="notify.loadMore().catch(function () {})">看更早的通知</el-button>
            </el-scrollbar>
          </div>
        </el-popover>
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
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { systemInfo } from '@/api/system'
import { logout } from '@/api/auth'
import { notifyIcon, notifyRoute } from '@/api/notify'
import { usePmStore } from '@/stores/pm'
import { fromNow } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import { useNotifyStore } from '@/stores/notify'

const user = useUserStore()
const notify = useNotifyStore()
const pm = usePmStore()
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

const bell = ref(null)
const marking = ref(false)

// 点开才读列表。失败不额外弹一条：store.error 已经存着那句话，列表区就地显示它，
// 而全局红条会在「令牌刚过期」那一刻和登录跳转叠成两条提示。
function onBellShow() {
  notify.load(true).catch(function () {})
}

async function markAll() {
  marking.value = true
  try {
    await notify.markAll()
  } catch (e) {
    /* 10001/10002 的话 http 层已经说过一次了，这里不再复述 */
  } finally {
    marking.value = false
  }
}

function openItem(item) {
  if (item && !item.read && item.id) {
    // 点不掉一条通知不该挡住跳转：跳转是用户要的那件事，已读只是顺手。
    notify.markRead([item.id]).catch(function () {})
  }
  if (bell.value && typeof bell.value.hide === 'function') bell.value.hide()
  if (!item) return
  const to = notifyRoute(item)
  if (to) {
    router.push(to).catch(function () {})
    return
  }
  // 没有 ref 的通知（crisis/system）跳不了具体对象。危机关怀那一条的第一读者
  // 是「需要马上找到入口」的人，所以给它求助页；其余的原地不动，不做无意义的跳转。
  if (item.type === 'crisis') router.push({ name: 'help' }).catch(function () {})
}

async function doLogout() {
  try {
    await logout()
  } catch (e) {
    /* 后端不可用时也必须能退出：本地令牌一定要清掉 */
  }
  user.clear()
  notify.clear()
  // 私信的内容比通知更敏感：不清的话「退出再登录」会先闪一下上一个人的聊天记录（详见 stores/pm.js#clear）。
  pm.clear()
  router.replace({ name: 'login' })
}

onMounted(() => {
  check()
  if (user.isLogged) notify.refreshUnread()
  timer = setInterval(function () {
    check()
    // 复用同一个 30s 心跳去刷红点：再开一个 interval 就是给同一件事两份漂移的时钟。
    // 私信角标走的是 store 里同一个 refreshUnread —— 它在 WS 断开时才该被定时器叫醒，
    // 而 WS 活着时 store 由 bindWs 的帧自己推动更新，这里多调一次也只是幂等地重读同一个数。
    if (user.isLogged) {
      notify.refreshUnread()
      pm.refreshUnread()
    }
  }, 30000)
})

// 登录态一变，通知状态必须跟着归零：不这么做的后果是「退出再登录，红点还挂着上一个人的未读」。
watch(() => user.isLogged, (logged) => {
  if (logged) {
    notify.refreshUnread()
    pm.refreshUnread()
  } else {
    notify.clear()
    pm.clear()
  }
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
/* el-badge 的默认角标是按「包一个按钮」定的位置，包在 14px 的导航文字外面会压到下一项，这里收紧一格。 */
.mi-nav-badge :deep(.el-badge__content) { font-size: 10px; height: 15px; line-height: 15px; padding: 0 4px; }
.mi-nav-badge :deep(.el-badge__content.is-fixed) { top: 4px; right: 4px; }
.mi-status { display: flex; align-items: center; gap: 14px; }
.mi-bell { font-size: 18px; line-height: 1; }
.mi-notify { display: flex; flex-direction: column; gap: 6px; }
.mi-notify-head { display: flex; align-items: center; gap: 8px; padding-bottom: 6px; border-bottom: 1px solid var(--mi-border); }
.mi-notify-title { font-size: 14px; font-weight: 600; color: var(--mi-text); }
.mi-notify-count { font-size: 12px; color: var(--mi-text-dim); flex: 1; }
.mi-notify-blank { padding: 18px 4px; font-size: 13px; color: var(--mi-text-dim); text-align: center; }
.mi-notify-list { list-style: none; margin: 0; padding: 0; }
.mi-notify-item { display: flex; gap: 10px; padding: 10px 6px; border-bottom: 1px dashed var(--mi-border); cursor: pointer; }
.mi-notify-item:hover { background: var(--mi-mist-bg, rgba(64, 158, 255, 0.06)); }
.mi-notify-item.is-unread .mi-notify-line { font-weight: 600; }
.mi-notify-item.is-unread { border-left: 3px solid var(--mi-primary); padding-left: 3px; }
.mi-notify-icon { font-size: 16px; line-height: 1.4; }
.mi-notify-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.mi-notify-line { font-size: 13px; color: var(--mi-text); }
.mi-notify-sub { font-size: 12px; color: var(--mi-text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mi-notify-meta { font-size: 11px; color: var(--mi-text-dim); }
.mi-notify-more { width: 100%; margin-top: 6px; }

.mi-ver { font-size: 12px; color: var(--mi-text-dim); }
.mi-who { font-size: 13px; color: var(--mi-mist); }
.mi-main { padding: 24px 28px; max-width: 1080px; margin: 0 auto; width: 100%; flex: 1; }
.mi-foot { padding: 18px 28px; font-size: 12px; color: var(--mi-text-dim); text-align: center; border-top: 1px solid var(--mi-border); }
</style>
