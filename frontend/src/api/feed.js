import http from './http'

// 后端 FeedController：话题是真接口（入参 limit，1-50）；帖子列表/详情/发帖与推荐流在阶段 2 返回 90001，
// 页面必须显式提示而不是画一张假数据糊弄联调（手册 §5.8 第 1 条）。
export const topics = (limit) =>
  http.get('/topics', { params: limit ? { limit } : {}, silent: true })
export const posts = (params) => http.get('/posts', { params, silent: true })
export const postDetail = (id) => http.get('/posts/' + id)
export const publish = (payload) => http.post('/posts', payload)
export const recommend = () => http.get('/feed/recommend', { silent: true })
