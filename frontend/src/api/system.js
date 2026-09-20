import http from './http'

// 后端 SystemController 的真实四个只读接口（/v3/api-docs 实测得到 20 个 path，系统组只有这四个）。
// 注意：/system/ping 与 /system/health 不存在，连接探测只能用 /system/info（否则会拿到 90006）。
export const systemInfo = () => http.get('/system/info', { silent: true })
export const systemVersion = () => http.get('/system/version', { silent: true })

// hotline 在后端有兜底常量，数据库未建也能返回 200，并带 degraded=true 表示读的是内置文案。
export const hotline = () => http.get('/system/hotline', { silent: true })

// 入参名是 keys（逗号分隔），后端只接受白名单键的交集；不传则返回全部公开项。
export const configs = (keys, opts) =>
  http.get('/system/configs', { params: keys ? { keys } : {}, silent: !!(opts && opts.silent) })
