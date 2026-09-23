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
 * <p>已实现并从本表移除的（2026-09-21 按 web 包里的 @*Mapping 逐条反推核对，T3.11-b 增量复核）：
 * POST/GET /api/posts 与 /api/posts/{id}（T3.3/T3.5/T3.13）、POST /api/files/image（T3.1）、
 * POST /api/audit/precheck（T3.2）、POST /api/posts/{id}/actions（T3.6 点赞与收藏）、
 * GET/POST /api/posts/{id}/comments（T3.7 评论与楼中楼）、
 * POST /api/posts/{id}/report（T3.11 举报）、GET/POST /api/users/{id}/follow 与
 * GET /api/users/{id}/profile（T3.6）、GET /api/users/me/posts（T3.13）、
 * GET /api/notifications 与 POST /api/notifications/read（T3.11-b 站内通知，顶栏铃铛已接通）。
 * 独立的「通知中心」整页仍属任务 3.16 —— 那是同一批接口的第二个消费者，不是接口没做，
 * 所以它不进这张表：这张表列的是「点了会没反应」，不是「还没长成设计稿那样」。</p>
 *
 * <p><b>有一种特殊情况既不算「已实现」也不该从表里删</b>：路由已经挂在控制器上、
 * 但方法体直接抛 90001 —— 目前只有 PUT /api/users/me/profile（资料编辑）。它不在这张表的
 * 字符串清单里（那不是路由清单，是「点了会没反应」清单），但 ProfileView 提交时确实会拿到
 * 「该功能尚未实现」，所以前端照旧要把那句话显示出来，不许当成保存成功。</p>
 */
export const NOT_IMPLEMENTED_YET = [
  'GET  /api/feed/recommend（情绪感知协同过滤推荐）→ 阶段 6/7，接口在但直接返 90001',
  'PATCH/DELETE /api/posts/{id}（编辑进重审、树洞到期销毁）→ 任务 3.15',
  'GET /api/feed/following（关注 Tab 时间线）→ 任务 3.17',
  'POST /api/emotions/checkin（情绪打卡与档案）→ 阶段 4',
  'POST /api/ai/chat/stream（SSE 流式对话）→ 阶段 4',
  'GET/DELETE /api/ai/conversations（会话历史）→ 阶段 4',
  'WS /ws/pm（私信实时推送）→ 阶段 5'
]
