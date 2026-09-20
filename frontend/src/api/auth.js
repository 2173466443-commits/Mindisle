import http from './http'

// 后端 AuthController：/api/auth/{captcha,register,login,refresh,logout}
export const getCaptcha = () => http.get('/auth/captcha', { silent: true })
export const register = (payload) => http.post('/auth/register', payload)
export const login = (payload) => http.post('/auth/login', payload)
export const refresh = (refreshToken) => http.post('/auth/refresh', { refreshToken })
export const logout = () => http.post('/auth/logout', {}, { silent: true })

// 阶段 3+ 才实现的接口：显式列出，前端不得静默吞掉「尚未实现」错误（手册 §5.8 第 1 条）。
export const NOT_IMPLEMENTED_YET = [
  'POST /api/posts（树洞发布）→ 阶段3',
  'GET  /api/posts（帖子列表）→ 阶段3',
  'GET  /api/feed/recommend（情绪感知推荐）→ 阶段6',
  'PUT  /api/users/me/profile（资料编辑与头像上传）→ 阶段3',
  'POST /api/ai/chat/stream（SSE 流式对话，需求 §9.2）→ 阶段4',
  'GET / DELETE /api/ai/conversations（会话历史）→ 阶段4',
  'GET/POST /api/emotions/**（情绪打卡与档案）→ 阶段3/4',
  'WS   /ws/pm（私信）→ 阶段5'
]
