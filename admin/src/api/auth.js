import http from './http'

// 后端 AdminController：只有 /api/admin/auth/login 在 permitAll 白名单里，
// 其余 /api/admin/** 一律要 ADMIN 或 SUPER 角色（SecurityConfig 集中声明）。
// 验证码由用户端同一接口签发：图形验证码是全局能力，不为后台单开一套。
export const getCaptcha = () => http.get('/auth/captcha', { silent: true })

// 非 ADMIN/SUPER 账号在这里会被后端以 10003 拒绝，前端如实提示，不做本地角色判断。
export const adminLogin = (payload) => http.post('/admin/auth/login', payload)

export const refresh = (refreshToken) => http.post('/auth/refresh', { refreshToken })

export const logout = () => http.post('/auth/logout', {}, { silent: true })
