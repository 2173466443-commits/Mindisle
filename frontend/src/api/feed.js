import http from './http'

// 后端 FeedController：本期只剩话题墙、推荐流与关注流三件事——
// 帖子读接口（列表/详情）与发帖已经挪到 PostController，前端对应 src/api/post.js（任务 3.5 落地时一并归位）。
// /api/feed/recommend 仍由后端返回 90001（阶段 6/7 的情绪感知协同过滤），页面必须显式提示而不是画假数据。
export const topics = (limit) =>
  http.get('/topics', { params: limit ? { limit } : {}, silent: true })
export const recommend = () => http.get('/feed/recommend', { silent: true })

// 关注流（任务 T3.17 · 需求 FR4.6「关注 TA，就能在首页看到 TA 更新的内容」）。
// 入参与出参与广场完全同形：首屏不带 beforeId（页码模式，后端回 total），之后 beforeId=nextCursor，
// 游标模式下 total 恒为 -1。正因为同形，首页那份列表可以直接交给 usePagedPosts 驱动，不必再写一套翻页。
//
// 两个不能靠猜的口径，都记在后端 PostQueryService#following 的注释里：
// 1) 只放「关注的人」的公开实名帖 —— 匿名与树洞帖恒不出现（同一个人不能既是「我关注的某某」又是马甲名）；
// 2) 没关注任何人时后端回的是 PageResult.empty()（list 空、total 0、hasMore false，且没有 nextCursor 这个键），
//    那是「空」不是「出错」，页面要画成引导文案，不能画成错误态。
//
// 未登录/令牌过期会拿到 10002/10004/10005；silent 意味着 http 层不弹红条也不自动跳登录页，
// 这一句要由首页的 Tab 自己说清楚（它不打算退化成「给你看广场」）。
export const followingFeed = (params) => http.get('/feed/following', { params, silent: true })
