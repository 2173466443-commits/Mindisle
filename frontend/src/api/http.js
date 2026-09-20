import axios from 'axios'
import { ElMessage } from 'element-plus'
import { textOf, isAuthCode, CODE } from './errorCode'
import router from '@/router'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截：注入 Authorization（token 由 userStore 写入 localStorage）
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('mindisle_token')
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})

let redirecting = false
function goLogin() {
  if (redirecting) return
  redirecting = true
  localStorage.removeItem('mindisle_token')
  localStorage.removeItem('mindisle_refresh')
  const current = router.currentRoute.value
  router
    .replace({ name: 'login', query: current && current.name ? { redirect: current.fullPath } : {} })
    .finally(() => { redirecting = false })
}

// 统一处理业务失败。config.silent = true 的请求（心跳探测、可选资料加载）不弹全局消息，
// 但一定 reject —— 页面自己决定如何显示降级状态，绝不吃掉错误。
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
  if (body.traceId) console.warn('[MindIsle][traceId]', body.traceId)
  return Promise.reject(err)
}

// 响应拦截：统一 {code,msg,data,success,traceId} 协议。code === 0 直接返回 data。
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
