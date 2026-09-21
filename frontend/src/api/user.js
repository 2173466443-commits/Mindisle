import http from './http'

// 后端 UserController（/api/users/**，全部需要登录令牌）
export const me = () => http.get('/users/me')
export const profile = () => http.get('/users/me/profile', { silent: true })
export const updateProfile = (payload) => http.put('/users/me/profile', payload)
// 授权流水（含已撤回历史，用于举证）
export const listConsents = () => http.get('/users/me/consents', { silent: true })
// type = TERMS | PRIVACY | SENSITIVE_INFO | EMOTION_SHARE | CRISIS_CONTACT；action = GRANT | WITHDRAW
export const grantConsent = (payload) => http.post('/users/me/consents', payload)

// 「我的帖子」与「屿友主页」的帖子列表（任务 T3.13 第二批 · 手册 §6.2 U12/U11）。
// 刻意做成两个函数，而不是「一个函数加 flag」：两者口径差得远 —— 前者按 user_id 直查，能看到自己的
// private 行与未过审行，还能按 status 筛；后者只放行「已过审 + 公开 + 非匿名」，参数里根本没有 status。
// 合成一个函数就会在调用点冒出一堆 if，而这两个接口以后各自要加的东西（前者加分页排序偏好、后者加资料卡）也不会收敛。
export const myPosts = (params) => http.get('/users/me/posts', { params, silent: true })
export const userPosts = (id, params) => http.get('/users/' + id + '/posts', { params, silent: true })

// 资料卡（任务 T3.6 · 手册 §6.2 U11 的上半张）：silent —— 主页即便拿不到头像和粉丝数也还得能看帖子列表，
// 卡片取不到就自己降级成一行文字，不该弹红条把人从阅读里踢出去。
export const userHomepage = (id) => http.get('/users/' + id + '/profile', { silent: true })

// 关注 / 取关（任务 T3.6）：action = follow | unfollow，两个方向都幂等。写接口，不 silent，理由同 actOnPost。
export const followUser = (id, action) => http.post('/users/' + id + '/follow', { action })
