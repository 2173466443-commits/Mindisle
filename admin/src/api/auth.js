import http from './http'

// 后端 AdminController：只有 /api/admin/auth/login 在 permitAll 白名单里，
// 其余 /api/admin/** 一律要 ADMIN 或 SUPER 角色（SecurityConfig 集中声明）。
// 验证码由用户端同一接口签发：图形验证码是全局能力，不为后台单开一套。
export const getCaptcha = () => http.get('/auth/captcha', { silent: true })

// 非 ADMIN/SUPER 账号在这里会被后端以 10003 拒绝，前端如实提示，不做本地角色判断。
export const adminLogin = (payload) => http.post('/admin/auth/login', payload)

// 令牌回读身份：刷新页面后 pinia 里的 profile 是空的（内存态不落盘），只靠令牌向后端取回角色。
// 路径是 /admin/me，不是 /admin/auth/me —— 后者会落进 JwtAuthFilter 的匿名前缀，令牌根本不被解析，
// 接口只会稳定返回 10002，而它看起来和白名单里那条登录接口只差一个词。
export const adminMe = () => http.get('/admin/me', { silent: true })

export const refresh = (refreshToken) => http.post('/auth/refresh', { refreshToken })

export const logout = () => http.post('/auth/logout', {}, { silent: true })
