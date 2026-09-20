<template>
  <el-container class="shell">
    <el-aside width="212px" class="side">
      <div class="brand">
        <span class="logo">🏝</span>
        <div>
          <div class="name">心屿 · 管理端</div>
          <div class="sub">MindIsle Admin</div>
        </div>
      </div>
      <el-menu :default-active="active" class="menu" router>
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon><span>运营看板</span>
        </el-menu-item>
        <el-menu-item index="/audit">
          <el-icon><Tickets /></el-icon><span>审核队列</span>
        </el-menu-item>
        <el-menu-item index="/configs">
          <el-icon><Setting /></el-icon><span>参数配置</span>
        </el-menu-item>
      </el-menu>
      <div class="side-foot">
        <p>阶段 2 骨架：登录与探活是真接口，看板/队列/参数在阶段 5 实现。</p>
      </div>
    </el-aside>

    <el-container>
      <el-header class="top">
        <div class="crumb">
          <span class="page">{{ pageTitle }}</span>
          <span class="dim">/ 心屿校园心理陪伴社区</span>
        </div>
        <div class="right">
          <el-tag :type="conn.tone" size="small" effect="dark">{{ conn.text }}</el-tag>
          <el-dropdown trigger="click" @command="onCommand">
            <span class="who">
              <el-avatar :size="26" :src="avatarUrl">{{ initial }}</el-avatar>
              <span class="nick">{{ admin.displayName }}</span>
              <el-tag size="small" type="info">{{ admin.role }}</el-tag>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="reload">刷新本块数据</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="route.fullPath" @reload="loadConnection" />
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, DataAnalysis, Setting, Tickets } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { systemInfo } from '@/api/system'
import { logout } from '@/api/auth'
import { useAdminUserStore } from '@/stores/adminUser'

const route = useRoute()
const router = useRouter()
const admin = useAdminUserStore()

const sys = ref(null)
const alive = ref(false)
let timer = null

const active = computed(() => route.path)
const pageTitle = computed(() => route.meta.title || '管理端')
const initial = computed(() => (admin.displayName || '?').slice(0, 1))
const avatarUrl = computed(() => (admin.profile && admin.profile.avatarUrl) || '')
const conn = computed(() => {
  if (!alive.value) return { tone: 'danger', text: '后端未连接' }
  return { tone: 'success', text: '后端在线 · cache=' + (sys.value.cacheMode || '?') }
})

async function loadConnection() {
  try {
    sys.value = await systemInfo()
    alive.value = true
  } catch (e) {
    alive.value = false
  }
}

async function onCommand(cmd) {
  if (cmd === 'reload') {
    await loadConnection()
    ElMessage.success('已刷新后端连接状态')
    return
  }
  if (cmd === 'logout') {
    try { await logout() } catch (e) { /* 令牌可能已过期，本地清理照做 */ }
    admin.clear()
    router.replace({ name: 'admin-login' })
  }
}

onMounted(() => {
  loadConnection()
  timer = setInterval(loadConnection, 30000)
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
.side-foot { padding: 12px 16px 18px; font-size: 11px; line-height: 1.7; color: var(--mi-text-dim); }
.side-foot p { margin: 0; }
.top { height: 58px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--mi-border); background: var(--mi-bg); }
.crumb .page { font-size: 15px; font-weight: 700; }
.crumb .dim { font-size: 12px; color: var(--mi-text-dim); margin-left: 8px; }
.right { display: flex; align-items: center; gap: 12px; }
.who { display: flex; align-items: center; gap: 8px; cursor: pointer; color: var(--el-text-color-regular); font-size: 13px; outline: none; }
.main { background: var(--mi-bg); padding: 18px; overflow: auto; }
</style>
