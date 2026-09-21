import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

// 路由 meta：requiresAuth / requiresConsent / requiresAdmin（§5.8 第 5 条）
const routes = [
  { path: '/', redirect: '/feed' },
  { path: '/login', name: 'login', component: () => import('@/views/auth/Login.vue'), meta: { public: true } },
  { path: '/register', name: 'register', component: () => import('@/views/auth/Register.vue'), meta: { public: true } },
  // 危机求助页：任何时候免登录可达，这是 FR10/危机转介的硬要求
  { path: '/help', name: 'help', component: () => import('@/views/help/Help.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    children: [
      { path: 'feed', name: 'feed', component: () => import('@/views/feed/FeedView.vue'), meta: { requiresAuth: true } },
      // U5 发布页与 U4 详情页（任务 T3.13）：详情页要计数，所以不做 keep-alive，每次进入都是真访问。
      { path: 'publish', name: 'publish', component: () => import('@/views/post/PublishView.vue'), meta: { requiresAuth: true } },
      { path: 'post/:id', name: 'post-detail', component: () => import('@/views/post/PostDetailView.vue'), meta: { requiresAuth: true } },
      { path: 'me', name: 'me', component: () => import('@/views/user/ProfileView.vue'), meta: { requiresAuth: true, requiresConsent: true } },
      { path: 'ai', name: 'ai-chat', component: () => import('@/views/ai/ChatView.vue'), meta: { requiresAuth: true, requiresConsent: true } },
      { path: 'emotion', name: 'emotion', component: () => import('@/views/emotion/EmotionView.vue'), meta: { requiresAuth: true, requiresConsent: true } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'notfound', component: () => import('@/views/NotFound.vue'), meta: { public: true } }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const user = useUserStore()
  if (to.meta.public) return true
  if (to.meta.requiresAuth && !user.isLogged) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requiresAdmin && !user.isAdmin) return { name: 'feed' }
  if (to.meta.requiresConsent && user.consent.loaded && !user.consent.privacy) {
    return { name: 'feed', query: { needConsent: '1' } }
  }
  return true
})

export default router
