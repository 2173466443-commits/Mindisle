import http from './http'

// 后端 com.mindisle.web.SearchController（任务 T3.9 · 需求 FR4.8「关键词搜帖子 / 话题 / 人」）。
//
// 为什么是三个函数而不是「一条路径加个 ?type=」：需求 §9.1 原本给的是单接口
// GET /search?q=&type=post|topic|user，后端落地时拆成了 /api/search/{posts,topics,users} 三条。
// 完整理由写在那个类的注释里，一句话版是三种结果的出参形状不同 —— 搜帖是「游标分页的帖子流」
// （PostListItem：带互动态、马甲名、求助热线、审核提示），搜话题与搜人是「定长数组」。
// 硬塞进一条路径只有两种做法：把 data 声明成 Object（Swagger 上是一个空 schema，前端拿不到任何类型），
// 或者拼一个三种都装的大对象（每次请求顺带返回两个空数组）。前端跟着拆成三个函数是同一个取舍的第二半，
// 不是忘了合并；这条偏离同时记在制作步骤文档 §19 的版本记录里。
//
// 三条都是读接口，全部 silent：搜索页要能扛住「后端还没起 / 库还没建」，
// 拿不到结果时在结果区写明是哪一个码（手册 §5.8 第 1 条禁止静默失败，也禁止用全局红条代替就地说明）。
//
// 🔴 三条都要求登录：不在后端 permitAll 白名单里，未登录与令牌过期分别拿到 10002 / 10004 / 10005。
// silent 的请求不会被 http 层自动带去登录页，所以搜索页必须自己认出这三个码并画一句人话。
// 对照 GET /api/topics 是游客可访问的话题墙：那是「展示运营选好的内容」，
// 这是「按任意关键词命中全站正文」，两者口径不同，别拿前者当先例给这条开白名单。

/** 关键词长度上限：与后端 mindisle.search.max-keyword-chars（默认 64）同源。超长后端直接 10001，不做截断兜底。 */
export const SEARCH_KEYWORD_MAX = 64

/** 搜话题 / 搜人一次能给的条数上限：与后端 mindisle.search.max-profiles（默认 20）同源。传更大的值会被夹住而不是报错。 */
export const SEARCH_PROFILE_MAX = 20

// 后端 limit 缺省是 10（SearchService.DEFAULT_LIMIT），上限是 20（max-profiles）。
// 这里只导出上限，页面统一按上限传：定长结果要一次给够一屏；
// 缺省值 10 是后端自己的兜底口径，前端没有参与逻辑的地方，就不复制一份常量出来占位置。

/**
 * 三种结果的中文名。value 同时用作地址栏里的 ?m=，所以这里就是唯一的一份映射：
 * 页面拿它渲染单选框，也拿它决定当前该发哪一条请求。
 * apiName 是「为什么这一栏没数据」那句话里要报给用户的接口名，不是内部注释。
 */
export const SEARCH_MODES = [
  { value: 'post', label: '帖子', apiName: 'GET /api/search/posts' },
  { value: 'topic', label: '话题', apiName: 'GET /api/search/topics' },
  { value: 'user', label: '屿友', apiName: 'GET /api/search/users' }
]

/** 帖子流：params = { q, type?, page?, size?, beforeId? }，出参 PageResult<PostListItem>（与广场同形）。 */
export const searchPosts = (params) => http.get('/search/posts', { params, silent: true })

/** 话题：params = { q, limit? }，出参 List<TopicHit>（7 个字段，不含 cover / 审核状态）。 */
export const searchTopics = (params) => http.get('/search/topics', { params, silent: true })

/** 屿友：params = { q, limit? }，出参 List<UserHit> = { id, nickname, avatar? }。白名单只有这三样，别指望在这里拿到邮箱或角色。 */
export const searchUsers = (params) => http.get('/search/users', { params, silent: true })