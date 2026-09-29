import { createRouter, createWebHistory } from 'vue-router'
import { useAdminUserStore } from '@/stores/adminUser'

// 管理端路由：/login 公开，其余全部要求 ADMIN/SUPER。真正的鉴权在后端（/api/admin/** hasAnyRole），
// 这里只做界面层的导航闸门——少一条路由会让别的页面跳过去吃 404，而 404 页不会告诉你是路由没接。
//
// 阶段 6 的九个页面里，A2 运行大屏刻意不套 AdminLayout：它是挂在值班墙上的全屏视图，
// 侧边栏和面包屑在那块屏上是干扰。所以它和后台壳平级，用 <a href="/screen" target="_blank"> 打开。
const routes = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/login',
    name: 'admin-login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '管理员登录' }
  },
  {
    path: '/screen',
    name: 'admin-screen',
    component: () => import('@/views/ScreenView.vue'),
    meta: { requiresAdmin: true, title: '运行大屏' }
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      { path: 'dashboard', name: 'admin-dashboard', component: () => import('@/views/DashboardView.vue'), meta: { requiresAdmin: true, title: '运营看板' } },
      { path: 'tickets', name: 'admin-tickets', component: () => import('@/views/TicketsView.vue'), meta: { requiresAdmin: true, title: '危机工单' } },
      { path: 'audit', name: 'admin-audit', component: () => import('@/views/AuditView.vue'), meta: { requiresAdmin: true, title: '审核队列' } },
      { path: 'content', name: 'admin-content', component: () => import('@/views/ContentView.vue'), meta: { requiresAdmin: true, title: '内容管理' } },
      { path: 'users', name: 'admin-users', component: () => import('@/views/UsersView.vue'), meta: { requiresAdmin: true, title: '用户管理' } },
      { path: 'configs', name: 'admin-configs', component: () => import('@/views/ConfigsView.vue'), meta: { requiresAdmin: true, title: '参数配置' } },
      { path: 'logs', name: 'admin-logs', component: () => import('@/views/LogsView.vue'), meta: { requiresAdmin: true, title: '操作日志' } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'admin-notfound', component: () => import('@/views/NotFound.vue'), meta: { public: true } }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async (to) => {
  const admin = useAdminUserStore()
  if (to.meta.public) return true
  if (to.meta.requiresAdmin) {
    if (!admin.isLogged) return { name: 'admin-login', query: { redirect: to.fullPath } }
    // 刷新后 profile 是空的（pinia 状态不落盘），角色会退化成 GUEST：先拿令牌回读再放行。
    // 这条必须 await —— 不 await 的话第一帧顶栏就写着「未登录」，A6 的 SUPER 专属按钮也不在，
    // 而这两种「看起来没登录」的界面恰恰是要拍进论文的图。
    if (!admin.profile) await admin.ensureProfile()
    if (!admin.isLogged) return { name: 'admin-login', query: { redirect: to.fullPath } }
    // 角色不合规时不渲染后台壳：token 可能是普通用户端签发的，必须挡在门外。
    if (admin.profile && !admin.isAdmin) return { name: 'admin-login', query: { reason: 'not-admin' } }
  }
  return true
})

export default router