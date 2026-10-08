import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

// 路由 meta：requiresAuth / requiresConsent / requiresAdmin（§5.8 第 5 条）
const routes = [
  // U1 首页/着陆（需求分析文档 §6 视图清单第 1 行 · 任务 T3.13 结转项，2026-10-08 落地）。
  // 这条记录的位置刻意保持在路由表第一项：它和下面那条 { path: '/', component: BasicLayout } 是同一个
  // path，vue-router 的 matcher 在分数相同处按插入顺序取第一条——旧版 { path: '/', redirect: '/feed' }
  // 正是靠这个顺序才生效的，改成带 component 之后依赖的还是同一条规则。位置一动，'/' 就会解析成
  // BasicLayout 的空子路由（一个白框页面），而这是只有真机点开根路径才会暴露的故障。
  // 已登录的人不该停在游客页，所以 beforeEach 里那条 home -> feed 是本路由的另一半，两处不能拆开改。
  { path: '/', name: 'home', component: () => import('@/views/LandingView.vue'), meta: { public: true } },
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
      { path: 'search', name: 'search', component: () => import('@/views/search/SearchView.vue'), meta: { requiresAuth: true } },
      // 隐私中心（任务 T4.21 · 需求 FR2.10 与个保法第 45/47 条）。只要登录，不加 requiresConsent：
      // 这一页是「查自己的数据、导出、注销、撤回授权」的地方，没勾敏感同意的人恰恰最需要走进来 ——
      // 给他加一道同意闸，等于把撤回授权的入口锁在授权之后。
      { path: 'privacy', name: 'privacy', component: () => import('@/views/privacy/PrivacyView.vue'), meta: { requiresAuth: true } },
      // U9 私信会话列表 / U10 会话详情（任务 T5.5 · 需求 FR6）。只要登录，不加 requiresConsent：
      // 私信读的是「别人发给我的内容」，和通知、评论同一性质；给它加一道敏感信息同意闸，
      // 等于让「撤回授权的人」连自己的收件箱都打不开 —— 那既不是同意的本意，也会把危机提醒一起挡在门外
      // （私信里的 L2/L3 风险提示就长在这条链路上，见 stores/pm.js#applyAlertFrame）。
      // 详情用 :uid（对方用户 id）而不是消息 id 或自造会话号：后端 PmController 的入参就是对端 user id，
      // 而 NotifyMessage#REF_PM 的 ref_id 也是它 —— 通知跳转和私信入口因此能共用同一条路由。
      { path: 'chat', name: 'chat', component: () => import('@/views/chat/ChatListView.vue'), meta: { requiresAuth: true } },
      { path: 'chat/:uid', name: 'chat-detail', component: () => import('@/views/chat/ChatDetailView.vue'), meta: { requiresAuth: true } },
      // U13 通知中心整页（需求分析文档 §10.1 · 任务 T3.16 欠账 ①，2026-10-08 落地）。
      // 只要登录、不要 requiresConsent：通知里躺的是「有人赞了你 / 审核结果 / 账号被处置」这类对自己数据的回执，
      // 给同意闸等于让「撤回敏感信息授权的人」连自己的处置通知都看不到，那与隐私中心（/privacy）刻意不设同意闸是同一条理由。
      // 路径不带数字 id：通知的收件人只来自 JWT，后端 GET /api/notifications 也不接受 user_id，
      // 所以这一页没有「别人的通知中心」这个地址，做成 /notifications 才是诚实的。
      { path: 'notifications', name: 'notifications', component: () => import('@/views/notify/NotificationsView.vue'), meta: { requiresAuth: true } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'notfound', component: () => import('@/views/NotFound.vue'), meta: { public: true } }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const user = useUserStore()
  // 🔴 已登录访问 '/' 一律直接进广场，不停在游客落地页。这条守卫同时钉住了取证探针的一个隐含前提：
  // frontend/probe/domprobe.mjs 用 w.__probeRouter.push('/') 之后断言的是「广场卡片」（.feed .row 计数）。
  // 旧版 '/' 是硬 redirect，所以那句话永远成立；现在 '/' 是一张真页面，少了这条守卫，
  // 登录态的探针会落到落地页并报「找不到广场卡片」——那是契约变更造成的假故障，不是产品缺陷。
  // 落地页本身要能被游客看到，所以判据用 isLogged（有令牌）而不是「路由来源」。
  if (to.name === 'home' && user.isLogged) return { name: 'feed' }
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
