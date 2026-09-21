import http from './http'

// 后端 com.mindisle.web.AuditController：内容预检（需登录，返回命中位置与处置建议，不含词面）。
// 为什么预检要 silent：这是输入防抖触发的自动调用，命中黑词返回 50001 属正常业务态，
// 弹全局红条会把「一边写一边提示」变成「每 300ms 打断一次」。
export const precheck = (text, scene) =>
  http.post('/audit/precheck', { text, scene: scene || 'post' }, { silent: true })
