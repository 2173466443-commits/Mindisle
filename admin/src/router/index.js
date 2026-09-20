import { createRouter, createWebHistory } from 'vue-router'
import { useAdminUserStore } from '@/stores/adminUser'

// 管理端路由：/login 公开，其余全部挂在 AdminLayout 下且要求 ADMIN/SUPER。
// 真正的鉴权在后端（/api/admin/** hasAnyRole），这里只做界面层的导航闸门。
const routes = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/login',
    name: 'admin-login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '管理员登录' }
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      { path: 'dashboard', name: 'admin-dashboard', component: () => import('@/views/DashboardView.vue'), meta: { requiresAdmin: true, title: '运营看板' } },
      { path: 'audit', name: 'admin-audit', component: () => import('@/views/AuditView.vue'), meta: { requiresAdmin: true, title: '审核队列' } },
      { path: 'configs', name: 'admin-configs', component: () => import('@/views/ConfigsView.vue'), meta: { requiresAdmin: true, title: '参数配置' } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'admin-notfound', component: () => import('@/views/NotFound.vue'), meta: { public: true } }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const admin = useAdminUserStore()
  if (to.meta.public) return true
  if (to.meta.requiresAdmin) {
    if (!admin.isLogged) return { name: 'admin-login', query: { redirect: to.fullPath } }
    // 角色不合规时不渲染后台壳：token 可能是普通用户端签发的，必须挡在门外。
    if (admin.profile && !admin.isAdmin) return { name: 'admin-login', query: { reason: 'not-admin' } }
  }
  return true
})

export default router
