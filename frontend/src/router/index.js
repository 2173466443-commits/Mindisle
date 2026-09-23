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
      // U12 我的帖子 / U11 屿友主页（任务 T3.13 第二批）。主页用数字 id 而不是 username：
      // 后端接口的入参就是数字 id，前端再造一层「用户名寻址」得先有一个按用户名查 id 的接口，而那正是还没有的那个。
      { path: 'me/posts', name: 'my-posts', component: () => import('@/views/user/MyPostsView.vue'), meta: { requiresAuth: true } },
      { path: 'user/:id', name: 'user-home', component: () => import('@/views/user/UserHomeView.vue'), meta: { requiresAuth: true } },
      { path: 'ai', name: 'ai-chat', component: () => import('@/views/ai/ChatView.vue'), meta: { requiresAuth: true, requiresConsent: true } },
      { path: 'emotion', name: 'emotion', component: () => import('@/views/emotion/EmotionView.vue'), meta: { requiresAuth: true, requiresConsent: true } },
      // 站内搜索（任务 T3.9 · 需求 FR4.8）。只要登录，不要敏感信息授权：
      // 搜索读的是「已经对全体登录用户公开」的内容，输入关键词这件事本身不涉及处理敏感个人信息，
      // 给这条加 requiresConsent 会把「没勾敏感授权的人」完全挡在站外，而他本来就该能搜帖。
      // U6 话题详情页（任务 T3.8 · 需求 FR4.5）。只要登录，不要敏感信息授权：这一页读的是已过审话题的公开帖流，
      // 判据与广场/搜索完全相同，多一道闸只会把「没勾敏感授权的人」挡在话题门外，而他本来就该能看。
      // 路径参数用数字 id 而不是话题名：后端 TopicController 限的是 \d+，而名字要参与重名判定与折叠空白，
      // 拿它当路由参数就得再引一层「按名寻址」的接口，那条接口正是还没有的那条。
      { path: 'topic/:id', name: 'topic-detail', component: () => import('@/views/topic/TopicDetailView.vue'), meta: { requiresAuth: true } },
      { path: 'search', name: 'search', component: () => import('@/views/search/SearchView.vue'), meta: { requiresAuth: true } }
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
