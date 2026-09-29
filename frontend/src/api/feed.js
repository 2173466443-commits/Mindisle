import http from './http'

// 后端 FeedController：本期只剩话题墙、推荐流与关注流三件事——
// 帖子读接口（列表/详情）与发帖已经挪到 PostController，前端对应 src/api/post.js（任务 3.5 落地时一并归位）。
export const topics = (limit) =>
  http.get('/topics', { params: limit ? { limit } : {}, silent: true })

/**
 * 推荐流一屏（任务 T7.9 接通 · 手册 §10.2 7.9 · 需求 FR5.6、D6）。
 *
 * <p>2026-09-29 起它不再是 90001 的桩：后端读的是离线批次算好的 recommend_result 缓存，
 * 缓存空或整批不可见时退热读兜底。silent 与广场同口径 —— 这一节取不到数据时页面要把
 * 「为什么这一栏是空的」就地写出来（手册 §5.8 第 1 条），而不是在别的栏目上面盖一条全局红条。</p>
 *
 * <p><b>翻页只能用 page，不能用 beforeId</b>：后端 FeedService#feed 只取 {@code PageQuery#offset()}
 * （=（page−1）×size），压根不看 beforeId；可出参走的是 {@code PageResult.ofCursor}，
 * 于是它照样回一个 nextCursor（本页末条 id）。前端若照广场那套「首屏之后带 beforeId」写，
 * 第二屏会拿到和第一屏一模一样的六条 —— 而且因为 id 相同，去重之后界面上是
 * 「点了加载更多什么都不发生」。Gate7 的量判据（D6 刷新三次不重复）就是把这条量出来的。
 * 所以这里传 {page, size}，由 usePagedPosts 的 pager:'page' 模式负责往后走页号。</p>
 *
 * <p>出参形状也不是 PageResult&lt;PostListItem&gt;，而是 PageResult&lt;FeedItem&gt;：
 * 每条卡片 = 广场那套字段全量 + reason / recallChannel / score 三位，
 * 帖子字段挂在 item.post 下面，顶层没有 id。渲染前必须摊平，见 FeedView 的 toCard()。</p>
 */
export const recommend = (params) => http.get('/feed/recommend', { params, silent: true })

/**
 * 「不感兴趣」负反馈（任务 T7.7 · 需求 FR1.7 / FR5.7 · Gate7 判据 D6「当场点当场没」）。
 *
 * <p>刻意<b>不</b> silent：后端这一步会返回 {removed, removedSimilar} 两个真实计数，
 * 而失败原因（未登录 10002 / 参数不合法）正是要让人看见的那句话 —— 闷着不响只会留下
 * 一个「点了没反应」的界面，而那恰好是这条按钮要治的病。</p>
 *
 * <p>一个已知的简化要写在这里而不是藏起来：后端 FeedService#dislike 把 scene 写死成 'feed'，
 * 所以从广场那张卡片上点「不感兴趣」，这条负反馈也会按 feed 场景记账。
 * 手册 §6.2 U3 本来就写着这颗按钮「阶段 7 接真逻辑」，而真逻辑目前只有一条推荐流消费它；
 * 影响是广场点掉的这条同样会从推荐缓存里被压掉（对用户是更保守的行为），
 * 不是「反馈丢了」。等第二条场景化的负反馈路径出现时再拆 scene。</p>
 */
export const dislike = (postId) => http.post('/feed/dislike', { postId })

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
