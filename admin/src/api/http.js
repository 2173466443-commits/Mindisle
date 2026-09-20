import axios from 'axios'
import { ElMessage } from 'element-plus'
import { textOf, isAuthCode, CODE } from './errorCode'
import router from '@/router'

// 管理端令牌与用户端分开存放，键名带 admin 前缀（同浏览器双身份不串号）。
export const ADMIN_TOKEN_KEY = 'mindisle_admin_token'
export const ADMIN_REFRESH_KEY = 'mindisle_admin_refresh'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(ADMIN_TOKEN_KEY)
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})

let redirecting = false
function goLogin() {
  if (redirecting) return
  redirecting = true
  localStorage.removeItem(ADMIN_TOKEN_KEY)
  localStorage.removeItem(ADMIN_REFRESH_KEY)
  const current = router.currentRoute.value
  router
    .replace({
      name: 'admin-login',
      query: current && current.name ? { redirect: current.fullPath } : {}
    })
    .finally(() => { redirecting = false })
}

// 与用户端完全一致的统一协议处理：code === 0 返回 data，否则 reject 带 code/traceId 的错误。
// silent 只抑制全局弹窗，不吞异常——「没有数据」必须在界面上说出来（手册 §5.8 第 1 条）。
function fail(body, config) {
  const silent = !!(config && config.silent)
  const msg = textOf(body.code, body.msg)
  const err = new Error(msg)
  err.code = body.code
  err.traceId = body.traceId
  if (isAuthCode(body.code)) {
    if (!silent) {
      ElMessage.error(msg)
      goLogin()
    }
  } else if (!silent) {
    ElMessage.error(msg)
  }
  if (body.traceId) console.warn('[MindIsle][admin][traceId]', body.traceId)
  return Promise.reject(err)
}

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === CODE.SUCCESS) return body.data
      return fail(body, resp.config)
    }
    return body
  },
  (err) => {
    const config = err.config || {}
    const silent = !!config.silent
    const body = err.response && err.response.data
    if (body && typeof body === 'object' && 'code' in body) return fail(body, config)
    if (err.response && err.response.status === 401) {
      if (!silent) {
        ElMessage.error(textOf(CODE.UNAUTHORIZED))
        goLogin()
      }
      return Promise.reject(err)
    }
    if (!silent) {
      ElMessage.error(err.response ? '服务异常 HTTP ' + err.response.status : '网络异常，请确认后端 8080 已启动')
    }
    return Promise.reject(err)
  }
)

export default http
