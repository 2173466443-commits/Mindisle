<!--
  顶栏（2026-09-30 改版：小红书式「白底 + 居中搜索框 + 右侧动作区」两段式头部）
  结构分两层，是照着成熟内容社区的做法来的，不是为了好看而好看：
    · 第一层是「品牌 / 搜索 / 我的东西」——搜索做成整条胶囊，因为小红书把最强的视觉权重
      给了搜索框，用户在内容站的第一反应就是「我要找什么」而不是「我在哪个页面」。
    · 第二层是频道导航（原 .mi-nav 的 router-link 一个不动），放在第二层是因为
      10 个入口挤在第一层会把搜索框压成一条缝。
  🔴 取证契约（frontend/probe/routecrawl.mjs L132 / domprobe / pmgate）钉的是
  nav.mi-nav a 的文字、.mi-nav-badge、.mi-badge、.mi-bell、.mi-notify-* 这套类名，
  本次改版只挪位置和改样式，一个名字都没删。
-->
<template>
  <div class="mi-shell">
    <header class="mi-top">
      <div class="mi-top-row">
        <router-link to="/feed" class="mi-brand">
          <span class="mi-logo">🏝</span>
          <span class="mi-name">心屿</span>
        </router-link>

        <!-- 站内搜索（任务 T3.9）：搜索入口从小红书借来的是形态——一整条居中胶囊，
             但刻意做成 button 跳页、不是顶栏内嵌输入框。理由没变（原注释）：顶栏每个页面都挂着，
             内嵌框要么全站常驻一个搜索状态，要么在每个页面各自实现一遍跳转。先要一条能用的路。 -->
        <button class="mi-search" type="button" @click="$router.push('/search')">
          <span class="mi-search-icon">🔍</span>
          <span class="mi-search-ph">搜索心屿的内容、话题和屿友</span>
        </button>

        <div class="mi-actions">
          <el-button class="mi-publish" type="primary" size="small"
                     @click="$router.push('/publish')">发布</el-button>
          <div class="mi-status">
            <el-tooltip :content="backendTip" placement="bottom">
              <el-tag :type="backendUp ? 'success' : 'danger'" size="small" effect="plain" class="mi-badge-backend">
                {{ backendUp ? '后端正常' : '后端未连接' }}
              </el-tag>
            </el-tooltip>
            <span class="mi-ver">{{ sysLine }}</span>
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
                  <!-- U13 通知中心整页的入口。刻意放在 head 里、且文案不是「全部已读」：
                       frontend/probe/domprobe.mjs 的 11 号线用 .mi-notify-head button 的文本数组做判据
                       （点完一键已读要断言「全部已读」这个字符串从数组里消失），多一个别的文案不影响它。 -->
                  <el-button link class="mi-notify-all" @click="goNotifyCenter">通知中心 ›</el-button>
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
        </div>
      </div>

      <nav class="mi-nav">
        <router-link to="/feed">广场</router-link>
        <router-link to="/search">搜索</router-link>
        <router-link to="/publish">发布</router-link>
        <router-link to="/ai">屿屿</router-link>
        <el-badge :value="pm.unreadTotal" :max="99" :hidden="!pm.unreadTotal" class="mi-nav-badge">
          <router-link to="/chat">私信</router-link>
        </el-badge>
        <router-link to="/emotion">情绪</router-link>
        <router-link to="/me">我的</router-link>
        <router-link to="/privacy">隐私</router-link>
        <router-link to="/help" class="mi-help">需要帮助？</router-link>
      </nav>
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

function goNotifyCenter() {
  if (bell.value && typeof bell.value.hide === 'function') bell.value.hide()
  router.push({ name: 'notifications' }).catch(function () {})
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

/* ---- 第一层：白底、贴住视口顶边 ---- */
.mi-top {
  position: sticky; top: 0; z-index: 20;
  background: var(--mi-card);
  border-bottom: 1px solid var(--mi-border);
  box-shadow: 0 1px 0 rgba(0, 0, 0, 0.02);
}
.mi-top-row {
  display: flex; align-items: center; gap: 20px;
  max-width: 1600px; margin: 0 auto; padding: 12px 24px 8px;
}
.mi-brand { display: flex; align-items: center; gap: 6px; flex: none; }
.mi-logo { font-size: 22px; line-height: 1; }
.mi-name { font-size: 20px; font-weight: 800; color: var(--mi-primary); letter-spacing: 1px; }

/* 搜索胶囊：占据中间弹性空间，视觉上像输入框，行为上是跳页按钮（理由见模板注释）。 */
.mi-search {
  flex: 1 1 auto; min-width: 0; max-width: 480px;
  display: flex; align-items: center; gap: 8px;
  height: 38px; padding: 0 16px;
  border: 1px solid transparent; border-radius: 999px;
  background: var(--mi-fill); color: var(--mi-text-dim);
  font: inherit; font-size: 13px; cursor: pointer; text-align: left;
  transition: background .15s, border-color .15s;
}
.mi-search:hover { background: var(--mi-card); border-color: var(--mi-primary-line); }
.mi-search-icon { flex: none; font-size: 14px; }
.mi-search-ph { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.mi-actions { display: flex; align-items: center; gap: 14px; flex: none; margin-left: auto; }
.mi-publish { font-weight: 600; padding-left: 18px; padding-right: 18px; }
.mi-status { display: flex; align-items: center; gap: 12px; }
.mi-ver { font-size: 12px; color: var(--mi-text-dim); white-space: nowrap; }
.mi-bell { font-size: 18px; line-height: 1; }
.mi-notify-all { font-size: 12px; color: var(--mi-text-dim); }
.mi-notify-all:hover { color: var(--mi-primary); }
.mi-who { font-size: 13px; color: var(--mi-text-2); max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---- 第二层：频道导航（文字链 + 选中一条红色下划线，就是小红书的导航长相） ---- */
.mi-nav {
  display: flex; align-items: center; gap: 24px; flex-wrap: wrap;
  max-width: 1600px; margin: 0 auto; padding: 0 24px 8px;
}
.mi-nav a {
  position: relative; color: var(--mi-text-2); text-decoration: none;
  font-size: 15px; line-height: 26px; padding-bottom: 2px;
}
.mi-nav a:hover { color: var(--mi-text); }
.mi-nav a.router-link-active { color: var(--mi-primary); font-weight: 600; }
/* 下划线只给选中的那一项，且不铺满整行 —— 小红书是「词下面一小段粗红杠」。 */
.mi-nav a.router-link-active::after {
  content: ''; position: absolute; left: 0; right: 0; bottom: -2px; margin: 0 auto;
  width: 20px; height: 3px; border-radius: 2px; background: var(--mi-primary);
}
.mi-nav .mi-help { color: var(--mi-danger-text); margin-left: auto; }
/* el-badge 的默认角标是按「包一个按钮」定的位置，包在 15px 的导航文字外面会压到下一项，这里收紧一格。 */
.mi-nav-badge :deep(.el-badge__content) { font-size: 10px; height: 15px; line-height: 15px; padding: 0 4px; }
.mi-nav-badge :deep(.el-badge__content.is-fixed) { top: 4px; right: 4px; }

/* ---- 通知弹层（只改配色，DOM 与类名一个没动） ---- */
.mi-notify { display: flex; flex-direction: column; gap: 6px; }
.mi-notify-head { display: flex; align-items: center; gap: 8px; padding-bottom: 6px; border-bottom: 1px solid var(--mi-border); }
.mi-notify-title { font-size: 14px; font-weight: 600; color: var(--mi-text); }
.mi-notify-count { font-size: 12px; color: var(--mi-text-dim); flex: 1; }
.mi-notify-blank { padding: 18px 4px; font-size: 13px; color: var(--mi-text-dim); text-align: center; }
.mi-notify-list { list-style: none; margin: 0; padding: 0; }
.mi-notify-item { display: flex; gap: 10px; padding: 10px 6px; border-bottom: 1px solid var(--mi-hairline); cursor: pointer; border-radius: 8px; }
.mi-notify-item:hover { background: var(--mi-hover); }
.mi-notify-item.is-unread .mi-notify-line { font-weight: 600; }
.mi-notify-item.is-unread { border-left: 3px solid var(--mi-primary); padding-left: 3px; }
.mi-notify-icon { font-size: 16px; line-height: 1.4; }
.mi-notify-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.mi-notify-line { font-size: 13px; color: var(--mi-text); }
.mi-notify-sub { font-size: 12px; color: var(--mi-text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mi-notify-meta { font-size: 11px; color: var(--mi-text-dim); }
.mi-notify-more { width: 100%; margin-top: 6px; }

/* ---- 主体与页脚 ---- */
.mi-main { box-sizing: border-box; padding: 16px 24px 28px; max-width: 1600px; margin: 0 auto; width: 100%; flex: 1; }
.mi-foot { padding: 18px 28px; font-size: 12px; color: var(--mi-text-dim); text-align: center; border-top: 1px solid var(--mi-border); background: var(--mi-card); }

/* 窄屏（<960px）：搜索框和版本号先让路，导航保持可点。 */
@media (max-width: 960px) {
  .mi-ver, .mi-who { display: none; }
  .mi-search { max-width: 260px; }
  .mi-nav { gap: 16px; }
}
/* 手机屏（<640px）：头部一行放不下「品牌+搜索+发布+通知」，照小红书的做法让次要项先消失——
   后端状态标签和搜索占位文案收掉，只留品牌、图标态搜索胶囊、发布、铃铛。
   只用 display:none，DOM 与类名一个没删（routecrawl/domprobe 的 querySelector 照样数得到）。 */
@media (max-width: 640px) {
  .mi-top-row { gap: 10px; padding: 10px 12px 6px; }
  .mi-badge-backend, .mi-search-ph { display: none; }
  .mi-search { flex: 1 1 auto; max-width: none; padding: 0 12px; justify-content: center; }
  .mi-actions { gap: 8px; }
  .mi-nav { padding: 0 12px 8px; gap: 14px; }
  .mi-main { padding: 12px 12px 24px; }
}
</style>
