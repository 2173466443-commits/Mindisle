import http from './http'

// 后端 com.mindisle.web.TopicController（任务 T3.8 · 需求 FR4.5「话题聚合页」+ FR1.7「创建话题」+ 手册 §6.2 U6）。
//
// 四条端点与前端函数的对应：详情 / 话题帖流 / 创建 / 关注。注意 GET /api/topics（话题墙，游客可逛）
// 不在这里 —— 它在 api/feed.js 的 topics()，两者不是同一判据：话题墙只回已过审的官方挑选结果，
// 而这四条要求登录且会因为你点进一个待审话题拿到 409/30004。把它们混成一个文件里的一组同名函数，
// 早晚会出现「拿话题墙的静默失败去解释详情页的待审」。
//
// 读接口一律 silent：详情页要能把「这个话题为什么还没有内容」就地画出来（待审 / 已驳回 / 库挂了
// 是三件不同的事），而不是在头图上盖一条全局红条（手册 §5.8 第 1 条）。写接口刻意不 silent：
// 创建话题失败的那句话（重名、超长、被机审拦下、当天额度用完）本身就是该给用户看的下一步，
// 与 api/post.js 里 createPost 的口径一致。

/** 话题名上限：与后端 mindisle.topic.max-name-chars（默认 32）和 DDL 的 name VARCHAR(32) 同源，按码点计。 */
export const TOPIC_NAME_MAX = 32

/** 话题简介上限：与后端 mindisle.topic.max-desc-chars（默认 200）和 DDL 的 desc_txt VARCHAR(200) 同源。 */
export const TOPIC_DESC_MAX = 200

/** 单账号每天可创建的话题数：与后端 mindisle.topic.max-create-per-day（默认 5）同源。只用来写提示语，判定在后端。 */
export const TOPIC_CREATE_PER_DAY = 5

/**
 * 话题页帖流的排序档位。值就是接口的 sort 参数，后端 normalizeSortFilter 只认这两个
 * （hot / relevance 会被直接判 10001，所以这里不能提前摆一个「热度」出来骗点击）。
 *
 * 🔴 top 这一档今天的真实含义只是「置顶帖排在前面」：post.is_top 只有管理员能写，而管理端
 * 排在 T6.1，所以库里该列恒为 0 —— 两档排序在今天的序列完全相同。这句诚实话写在界面上
 * （TopicDetailView 的注脚），不藏在这里的注释里。
 */
export const TOPIC_SORTS = [
  { value: 'latest', label: '最新' },
  { value: 'top', label: '热帖' }
]

/** 话题资料头 + 当前用户是否已关注：TopicCard（10 个字段，含 following）。待审 409/30004、驳回与不存在 404/90006。 */
export const topicDetail = (id) => http.get('/topics/' + id, { silent: true })

/** 话题下的帖子流：params = { sort?, page?, size?, beforeId? }，出参 PageResult<PostListItem>，与广场 / 主页同形，所以能直接喂 usePagedPosts。 */
export const topicPosts = (id, params) => http.get('/topics/' + id + '/posts', { params, silent: true })

/** 创建话题：body = { name, desc }，出参 TopicCreateView（含 usable 与 tip）。恒 200，「进了待审」也是成功。 */
export const createTopic = (payload) => http.post('/topics', payload)

/** 关注 / 取关话题：action = follow | unfollow，两个方向都幂等，出参 TopicFollowView（following + 重算后的 followCnt）。 */
export const followTopic = (id, action) => http.post('/topics/' + id + '/follow', { action })

/**
 * 按码点计数，与 api/post.js 的 commentLength 同一口径（后端用 codePointCount）。
 * 话题名允许带 emoji，用 String.length 会数出两倍的字数。
 */
export function topicLength(value) {
  return Array.from(value || '').length
}

