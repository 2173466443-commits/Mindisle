import http from './http'

// 后端 PrivacyController（/api/privacy/**，任务 T4.21 · 需求 FR1.5 + FR1.6 + NFR8）。
// 九条口径逐条抄自 web/PrivacyController.java，不自己发明路径：
//   GET    /api/privacy/summary                我的数据概览：逐域条数 + 账号白名单字段 + 冷静期状态
//   GET    /api/privacy/export?format=json|csv 提交导出任务，同步返回的是 PENDING 的任务视图，不是文件
//   GET    /api/privacy/export/latest          最近一次任务；从没导出过时 data = null，这不是错误
//   GET    /api/privacy/export/history?limit=  导出历史，新的在前（上限夹在 200）
//   GET    /api/privacy/export/file?token=     下载产物裸字节 —— 全站唯一一处不套 Result<T> 的接口
//   POST   /api/privacy/deactivate             进入 30 天冷静期，幂等、重复提交不延长到期
//   POST   /api/privacy/restore                撤回注销（仅冷静期内，不在期内 10001）
//   DELETE /api/privacy/consent/{type}         撤回某项授权，追加一条 WITHDRAW 流水（TERMS/PRIVACY 被闸门拦住）
//   POST   /api/privacy/retention/run?limit=   立即跑一批到期清除（仅管理员，非管理员 10003）

export const summary = () => http.get('/privacy/summary')

// submitExport 是写操作：不 silent，失败要让用户当场看到「任务没排上队」。
export const submitExport = (format) => http.get('/privacy/export', { params: { format } })

// latestExport / exportHistory 是轮询与可选信息：silent —— 页面自己有「还没有导出记录」这一格空态，
// 不需要全局红条在每次轮询失败时把人从页面上踢出去。
export const latestExport = () => http.get('/privacy/export/latest', { silent: true })
export const exportHistory = (limit) => http.get('/privacy/export/history', { params: { limit }, silent: true })

export const deactivate = () => http.post('/privacy/deactivate')
export const restore = () => http.post('/privacy/restore')
export const withdrawConsent = (type) => http.delete('/privacy/consent/' + encodeURIComponent(String(type || '').trim()))

// runRetention 会真删数据，所以只有管理员页面上有按钮；冒烟脚本只允许对测试账号跑（见后端注释那条纪律）。
export const runRetention = (limit) => http.post('/privacy/retention/run', null, { params: { limit } })

/**
 * 下载导出包。
 *
 * 🔴 这一条既不能走 axios，也不能写成 <a href="downloadPath"> 或 window.open：
 * 那条端点要的是 Authorization 头里的 JWT，而本项目把 token 存在 localStorage（不是 Cookie），
 * 浏览器发起的顶层导航不会带这个头。真按「点链接直接下载」实现，用户拿到的永远是 401，
 * 而后端为了不泄露存在性，把口令无效/非本人/未成功/已过期统一回成 404 —— 界面只会显示一句
 * 「下载链接无效或已过期」，谁都看不出原因是没带头。所以这里用 fetch 带头取字节，
 * 再换成一个 object URL 触发下载，并把文件名从 Content-Disposition 里读出来。
 *
 * 🔴 「是不是错误」只能看 HTTP 状态码，不能看 content-type。上一版写的是
 * 「!res.ok 或 content-type 含 application/json 就抛」，而 format=json 的**成功**产物本身
 * 就是 application/json（后端 PrivacyExportService 按格式给出 contentType），于是 200 的
 * 正常包裹被当成错误扔掉：用户点了「下载到本机」什么也不会发生，连红条都在 3 秒后自己消失
 * （2026-09-28 真浏览器取证 dlprobe 抓到的那条 RESP 200 / 157023 字节就是这条链路的铁证）。
 * 非 2xx 才是失败：口令无效/非本人/未成功/已过期后端统一回 404 + Result<T>，这时才去解 msg。
 */
export async function downloadExport(task) {
  const path = task && task.downloadPath ? String(task.downloadPath) : ''
  if (!path) {
    const err = new Error('这条导出记录还不可下载')
    err.code = 90006
    throw err
  }
  const token = localStorage.getItem('mindisle_token')
  const res = await fetch(path, { headers: token ? { Authorization: 'Bearer ' + token } : {} })
  // 判据只看状态码：2xx 一律当包裹存下来（格式可能是 json 也可能是 csv），非 2xx 才去解 Result<T>。
  if (!res.ok) {
    let msg = 'HTTP ' + res.status
    let bizCode = null
    try {
      const body = await res.json()
      if (body && body.msg) msg = String(body.msg)
      if (body && body.code !== undefined) bizCode = Number(body.code)
    } catch (e) {
      /* 不是 JSON 就留着状态码，至少能定位 */
    }
    const err = new Error(msg)
    if (bizCode !== null) err.code = bizCode
    throw err
  }
  const blob = await res.blob()
  const dispo = res.headers.get('content-disposition') || ''
  const at = dispo.indexOf('filename=')
  let fileName = at >= 0 ? dispo.slice(at + 9).replace(/[";]/g, '').trim() : ''
  if (!fileName) fileName = 'mindisle-export-' + ((task && task.format) || 'json') + '-' + Date.now()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  // 立刻 revoke 会让部分浏览器把这次下载一起取消；留 30 秒回收，既不让 object URL 长期挂着一份个人信息，也不吞掉下载。
  setTimeout(() => URL.revokeObjectURL(url), 30000)
  return { fileName: fileName, bytes: blob.size }
}
