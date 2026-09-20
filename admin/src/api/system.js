import http from './http'

// 后端 SystemController 只有这四个匿名只读接口。
// 再次强调：/api/system/ping 与 /api/system/health 不存在，探活只能用 /api/system/info。
export const systemInfo = () => http.get('/system/info', { silent: true })
export const systemVersion = () => http.get('/system/version', { silent: true })
export const hotline = () => http.get('/system/hotline', { silent: true })
export const configs = (keys) =>
  http.get('/system/configs', { params: keys ? { keys } : {}, silent: true })
