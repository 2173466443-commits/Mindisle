import http from './http'

// 后端 AuthController：/api/auth/{captcha,register,login,refresh,logout}
export const getCaptcha = () => http.get('/auth/captcha', { silent: true })
export const register = (payload) => http.post('/auth/register', payload)
export const login = (payload) => http.post('/auth/login', payload)
export const refresh = (refreshToken) => http.post('/auth/refresh', { refreshToken })
export const logout = () => http.post('/auth/logout', {}, { silent: true })

/**
 * 后端还没实现的接口：显式列出来，前端不得静默吞掉「尚未实现」（手册 §5.8 第 1 条）。
 *
 * <p>这张表的维护口径写死成一句话：**按 backend/src/main/java/com/mindisle/web 里的
 * {@code @*Mapping} 反推，不凭印象写**。上一版把它当「待办清单」养，结果 T3.13 把
 * 发帖与列表接通之后，界面上还挂着两行「POST /api/posts → 阶段3」，
 * 用户照着这句话就会以为功能没做。所以每接完一个任务，除了改页面，还得回到这里删掉一行。</p>
 *
 * <p>已实现并从本表移除的：POST/GET /api/posts 与 /api/posts/{id}（T3.3/T3.5/T3.13）、
 * POST /api/files/image（T3.1）、POST /api/audit/precheck（T3.2）、
 * PUT /api/users/me/profile（T2.x 资料编辑）。</p>
 */
export const NOT_IMPLEMENTED_YET = [
  'GET  /api/feed/recommend（情绪感知协同过滤推荐）→ 阶段 6/7，接口在但直接返 90001',
  'POST /api/posts/{id}/like · /collect（点赞与收藏）→ 任务 3.6',
  'GET/POST /api/posts/{id}/comments（评论与楼中楼）→ 任务 3.7',
  'POST /api/posts/{id}/report（举报分类→工单）→ 任务 3.11',
  'PATCH/DELETE /api/posts/{id}（编辑进重审、树洞到期销毁）→ 任务 3.15',
  'GET /api/feed/following（关注 Tab 时间线）→ 任务 3.17',
  'GET/POST /api/notifications（通知中心与未读红点）→ 任务 3.16',
  'POST /api/emotions/checkin（情绪打卡与档案）→ 阶段 4',
  'POST /api/ai/chat/stream（SSE 流式对话）→ 阶段 4',
  'GET/DELETE /api/ai/conversations（会话历史）→ 阶段 4',
  'WS /ws/pm（私信实时推送）→ 阶段 5'
]
