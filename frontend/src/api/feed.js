import http from './http'

// 后端 FeedController：本期只剩话题墙与推荐流两件事——
// 帖子读接口（列表/详情）与发帖已经挪到 PostController，前端对应 src/api/post.js（任务 3.5 落地时一并归位）。
// /api/feed/recommend 仍由后端返回 90001（阶段 6/7 的情绪感知协同过滤），页面必须显式提示而不是画假数据。
export const topics = (limit) =>
  http.get('/topics', { params: limit ? { limit } : {}, silent: true })
export const recommend = () => http.get('/feed/recommend', { silent: true })
