import http from './http'

// 后端 AdminController 目前只有登录是真实现，其余三个接口按排期抛 90001（阶段 5）。
// 这里如实声明，页面拿到 90001 后用 StageNotice 说明原因，绝不编造看板数字。
export const statsOverview = () => http.get('/admin/stats/overview', { silent: true })
export const auditTasks = (params) => http.get('/admin/audit/tasks', { params: params || {}, silent: true })
export const adminConfigs = () => http.get('/admin/configs', { silent: true })

// 唯一当前可读的参数入口是公开白名单（SystemController，键取交集）：
// prompt.version / prompt.crisis_card / audit.wordlib_version / ai.model
export const publicConfigs = (keys) =>
  http.get('/system/configs', { params: keys ? { keys } : {}, silent: true })
