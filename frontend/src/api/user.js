import http from './http'

// 后端 UserController（/api/users/**，全部需要登录令牌）
export const me = () => http.get('/users/me')
export const profile = () => http.get('/users/me/profile', { silent: true })
export const updateProfile = (payload) => http.put('/users/me/profile', payload)
// 授权流水（含已撤回历史，用于举证）
export const listConsents = () => http.get('/users/me/consents', { silent: true })
// type = TERMS | PRIVACY | SENSITIVE_INFO | EMOTION_SHARE | CRISIS_CONTACT；action = GRANT | WITHDRAW
export const grantConsent = (payload) => http.post('/users/me/consents', payload)
