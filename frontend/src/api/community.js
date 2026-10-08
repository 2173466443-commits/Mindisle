import http from './http'

// 落地页（U1）的唯一数据源：GET /api/system/community-pulse。
// 后端把它挂在 /api/system/** 之下，那条前缀本来就在 SecurityConfig 的 permitAll 清单里，
// 所以这是全站唯一一条「不需要令牌也能 200」的内容侧读口 —— 免登录是设计，不是遗漏。
// silent：落地页取不到数时靠自己的 degraded 空态说话，不弹全局消息（游客被红 toast 吓到没有意义）。
export const getPulse = (days) =>
  http.get('/system/community-pulse', { params: days ? { days } : {}, silent: true })