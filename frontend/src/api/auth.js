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
 * GET /api/notifications 与 POST /api/notifications/read（T3.11-b 站内通知，顶栏铃铛已接通）、
 * GET /api/feed/following（T3.17 关注流，首页「广场 / 关注」两个 Tab 已接通）、
 * GET /api/search/{posts,topics,users}（T3.9 站内搜索，/search 页已接通）、
 * POST /api/ai/chat/stream 与 GET/POST/DELETE /api/ai/conversations、
 * GET /api/ai/conversations/{id}/messages、POST /api/ai/messages/{id}/feedback
 * （阶段 4 的 AI 域 6 条，2026-09-24 按 web 包 @*Mapping 反推核对；/ai 页已接通多会话侧栏、
 * 流式回复、旧会话回看与赞踩）。同一天的情绪域三条（POST /api/emotions/checkin、
 * GET /api/emotions/profile、GET /api/emotions/weekly-report，外加 GET /api/emotions/checkins 回看）
 * 也已从本表删掉：它们不再是「点了没反应」，/emotion 页在真浏览器里出图、写库、回看均已实测。
 * 后两批是 2026-09-23 按 web 包里的 @*Mapping 反推核对的。搜索这条不存在「从表里删掉旧的一行」，
 * 因为需求 §9.1 那条单接口 /search 后端从来没映射过 —— 它一落地就是三条路径。
 * <b>2026-09-29 阶段 7 收口时又删掉两行</b>（同样按 web 包里的 @*Mapping 逐条反推核对，不凭印象）：
 * ① GET /api/feed/recommend —— 桩已在 T7.9 拆掉，改读 recommend_result 缓存，首页「为你推荐」那张卡就是它的消费方；
 * ② WS /ws/pm —— 这一行原本连路径都是错的：真实端点是 WebSocketConfig 里的 /ws（SockJS）与 /ws-native，
 *    阶段 5 就随私信一起上线了，挂着「→ 阶段 5」是把已交付的东西继续标成欠账。
 * 新增的 POST /api/feed/dislike（T7.7）与 GET /api/posts/{id}/similar（T7.16）从上线那天起就不在这张表里：
 * 它们不是「点了没反应」，是「点了有反应」。
 * 顺手记一句同一天新出现的<b>假话风险</b>：这张表如果哪天又长出一行「阶段 X 未实现」，
 * 除了删这一行，还得回前端把「钉这句话存在」的探针判据一起改 ——
 * routecrawl.mjs 第 152 行那条就吃过这个亏（判据要求屏上有「阶段 7 未实现」，功能落地后它必红）。
 * 独立的「通知中心」整页仍属任务 3.16 —— 那是同一批接口的第二个消费者，不是接口没做，
 * 所以它不进这张表：这张表列的是「点了会没反应」，不是「还没长成设计稿那样」。</p>
 *
 * <p><b>有一种特殊情况既不算「已实现」也不该从表里删</b>：路由已经挂在控制器上、
 * 但方法体直接抛 90001 —— 目前只有 PUT /api/users/me/profile（资料编辑）。它不在这张表的
 * 字符串清单里（那不是路由清单，是「点了会没反应」清单），但 ProfileView 提交时确实会拿到
 * 「该功能尚未实现」，所以前端照旧要把那句话显示出来，不许当成保存成功。</p>
 */
export const NOT_IMPLEMENTED_YET = [
  'PATCH/DELETE /api/posts/{id}（编辑进重审、树洞到期销毁）→ 任务 3.15'
]
