<template>
  <el-container class="shell">
    <el-aside width="216px" class="side">
      <div class="brand">
        <span class="logo">🏝</span>
        <div>
          <div class="name">心屿 · 管理端</div>
          <div class="sub">MindIsle Admin</div>
        </div>
      </div>

      <!-- 阶段 6 的七个页面全部是真接口。角标是「待办」，不是「总数」：
           数字来源与 A1 工作台完全同一组接口，两处对不上就是 bug，所以这里不另算一套。 -->
      <el-menu :default-active="active" class="menu" router>
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <template #title><span>运营看板</span></template>
        </el-menu-item>
        <el-menu-item index="/tickets">
          <el-icon><FirstAidKit /></el-icon>
          <template #title><span>危机工单</span><el-badge v-if="bad.ticket" :value="bad.ticket" :max="999" class="tag" /></template>
        </el-menu-item>
        <el-menu-item index="/audit">
          <el-icon><Tickets /></el-icon>
          <template #title><span>审核队列</span><el-badge v-if="bad.audit" :value="bad.audit" :max="999" class="tag" /></template>
        </el-menu-item>
        <el-menu-item index="/content">
          <el-icon><Document /></el-icon>
          <template #title><span>内容管理</span><el-badge v-if="bad.report || bad.appeal" :value="bad.report + bad.appeal" :max="999" class="tag" /></template>
        </el-menu-item>
        <el-menu-item index="/users">
          <el-icon><User /></el-icon>
          <template #title><span>用户管理</span></template>
        </el-menu-item>
        <el-menu-item index="/configs">
          <el-icon><Setting /></el-icon>
          <template #title><span>参数配置</span></template>
        </el-menu-item>
        <el-menu-item index="/logs">
          <el-icon><Histogram /></el-icon>
          <template #title><span>操作日志</span></template>
        </el-menu-item>
      </el-menu>

      <div class="side-foot">
        <p>待办构成：工单 {{ bad.ticket || 0 }} · 审核 {{ bad.audit || 0 }} · 举报 {{ bad.report || 0 }} · 申诉 {{ bad.appeal || 0 }}</p>
        <p v-if="badErr" class="err">待办数读取失败：{{ badErr }}</p>
        <p class="dim">
          举报与申诉合起来是「内容管理」那个角标，进去是两个 Tab——把它们当成一类会误判，
          因为举报要办结帖子、申诉要裁定帖子，走的接口和状态机完全不同。
        </p>
      </div>
    </el-aside>

    <el-container>
      <el-header class="top">
        <div class="crumb">
          <span class="page">{{ pageTitle }}</span>
          <span class="dim">/ 心屿校园心理陪伴社区</span>
        </div>
        <div class="right">
          <!-- A2 大屏不套这个壳：它是值班墙上的全屏视图，侧边栏在那块屏上是干扰。 -->
          <a class="screen" href="/screen" target="_blank" rel="noopener">🏝 运行大屏</a>
          <el-tag :type="conn.tone" size="small" effect="dark">{{ conn.text }}</el-tag>
          <el-dropdown trigger="click" @command="onCommand">
            <span class="who">
              <el-avatar :size="26" :src="avatarUrl">{{ initial }}</el-avatar>
              <span class="nick">{{ admin.displayName }}</span>
              <el-tag size="small" :type="admin.rolePending ? 'warning' : 'info'">{{ roleChip }}</el-tag>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="reload">刷新连接状态与待办角标</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="route.fullPath" @reload="refreshAll" />
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, DataAnalysis, Document, FirstAidKit, Histogram, Setting, Tickets, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { systemInfo } from '@/api/system'
import { logout } from '@/api/auth'
import { dashboardStats, reportPendingCount, appealPendingCount } from '@/api/admin'
import { errText } from '@/utils/format'
import { useAdminUserStore } from '@/stores/adminUser'

const route = useRoute()
const router = useRouter()
const admin = useAdminUserStore()

const sys = ref(null)
const alive = ref(false)
const bad = reactive({ ticket: 0, audit: 0, report: 0, appeal: 0 })
const badErr = ref('')
let timer = null

const active = computed(() => route.path)
const pageTitle = computed(() => route.meta.title || '管理端')
const initial = computed(() => (admin.displayName || '?').slice(0, 1))
const avatarUrl = computed(() => (admin.profile && admin.profile.avatar) || '')
// 身份还没读回时顶栏写「身份确认中」而不是 GUEST：GUEST 会让值班的人以为自己被踢下线，
// 进而重登、甚至怀疑账号被封——那是把一个临时错误升级成了误判。
const roleChip = computed(() => (admin.rolePending ? '身份确认中' : admin.role))
const conn = computed(() => {
  if (!alive.value) return { tone: 'danger', text: '后端未连接' }
  return { tone: 'success', text: '后端在线 · cache=' + (sys.value.cacheMode || '?') }
})

async function loadConnection () {
  try {
    sys.value = await systemInfo()
    alive.value = true
  } catch (e) {
    alive.value = false
  }
}

/**
 * 待办角标。三个接口各自独立失败：任何一个挂了都不能把别的数字清零，
 * 否则「工单积压 0」这句假话会让人放下心理负担——这比不显示数字更糟。
 */
async function loadBadges () {
  const errs = []
  try {
    const s = await dashboardStats()
    bad.audit = Number(s.auditPendingCnt) || 0
    bad.ticket = Number(s.ticketPendingCnt) || 0
  } catch (e) {
    errs.push('stats: ' + errText(e))
  }
  try {
    bad.report = Number(await reportPendingCount()) || 0
  } catch (e) {
    errs.push('reports: ' + errText(e))
  }
  try {
    bad.appeal = Number(await appealPendingCount()) || 0
  } catch (e) {
    errs.push('appeals: ' + errText(e))
  }
  badErr.value = errs.join(' / ')
}

async function refreshAll () {
  // 令牌还在但身份没读回（多半是 10010 限流或后端刚好没起来）：跟着 30s 心跳再回读一次。
  // 这里传 0 是刻意的——不在这条链上再叠一次退避等待，30s 心跳本身就是重试节奏。
  const tasks = [loadConnection(), loadBadges()]
  if (admin.isLogged && !admin.profile) tasks.push(admin.ensureProfile(0))
  await Promise.all(tasks)
}

async function onCommand (cmd) {
  if (cmd === 'reload') {
    await refreshAll()
    ElMessage.success(alive.value ? '后端在线，待办角标已刷新' : '后端仍未连接')
    return
  }
  if (cmd === 'logout') {
    try { await logout() } catch (e) { /* 令牌可能已过期，本地清理照做 */ }
    admin.clear()
    router.replace({ name: 'admin-login' })
  }
}

onMounted(() => {
  refreshAll()
  // 角标跟着探活一起刷：值班的人盯着后台看队列有没有涨，30 秒这个节奏够了，
  // 再密就是在给 /dashboard/stats 这种聚合查询加无谓的负载。
  timer = setInterval(refreshAll, 30000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.shell { height: 100vh; }
.side { background: #121c33; border-right: 1px solid var(--mi-border); display: flex; flex-direction: column; }
.brand { display: flex; align-items: center; gap: 10px; padding: 18px 16px; border-bottom: 1px solid var(--mi-border); }
.logo { font-size: 24px; }
.name { font-size: 15px; font-weight: 700; color: var(--mi-primary); letter-spacing: 1px; }
.sub { font-size: 11px; color: var(--mi-text-dim); letter-spacing: 1px; }
.menu { border-right: none; background: transparent; flex: 1; }
.menu :deep(.el-menu-item) { height: 46px; color: var(--el-text-color-regular); }
.menu :deep(.el-menu-item.is-active) { color: var(--mi-primary); background: rgba(240, 135, 107, 0.1); }
.menu :deep(.el-menu-item [role='menuitem']) { width: 100%; }
.tag { margin-left: 10px; }
.tag :deep(.el-badge__content) { background: #f0876b; border: none; }
.side-foot { padding: 12px 16px 18px; font-size: 11px; line-height: 1.7; color: var(--mi-text-dim); }
.side-foot p { margin: 0 0 6px; }
.side-foot .err { color: var(--el-color-danger); }
.top { height: 58px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--mi-border); background: var(--mi-bg); }
.crumb .page { font-size: 15px; font-weight: 700; }
.crumb .dim { font-size: 12px; color: var(--mi-text-dim); margin-left: 8px; }
.right { display: flex; align-items: center; gap: 12px; }
.screen { font-size: 12px; color: var(--mi-primary); text-decoration: none; border: 1px solid var(--mi-primary); border-radius: 14px; padding: 3px 10px; }
.screen:hover { background: rgba(240, 135, 107, 0.1); }
.who { display: flex; align-items: center; gap: 8px; cursor: pointer; color: var(--el-text-color-regular); font-size: 13px; outline: none; }
.main { background: var(--mi-bg); padding: 18px; overflow: auto; }
</style>