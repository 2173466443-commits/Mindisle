import { defineStore } from 'pinia'
import { ADMIN_TOKEN_KEY, ADMIN_REFRESH_KEY } from '@/api/http'
import { adminMe } from '@/api/auth'
import { CODE, isAuthCode } from '@/api/errorCode'

// 管理端身份：后端登录返回 AuthResponse{accessToken,refreshToken,expiresIn,user}。
// user 是 AuthResponse.UserBrief，字段逐字为 id/username/nickname/avatar/role/aiStyle/status。
// 这里原来注释成「id/username/nickname/avatarUrl/gender/role/status」，是照抄用户端 User 实体
// 想当然写的，结果顶栏头像绑到了一个不存在的字段上（永远空白）。出参字段以
// backend/src/main/java/com/mindisle/auth/dto/AuthResponse.java 为准，不以任何人的记忆为准。
// role 是权限唯一来源，而它的权威在后端 user.role，不在浏览器的任何缓存里。
export const useAdminUserStore = defineStore('adminUser', {
  state: () => ({
    token: localStorage.getItem(ADMIN_TOKEN_KEY) || '',
    refreshToken: localStorage.getItem(ADMIN_REFRESH_KEY) || '',
    profile: null,
    // pending：令牌还在、只是这一刻没读回身份。它和「没登录」是两件事，界面必须能区分——
    // 2026-09-29 取证首跑就拍到过：/api/admin/me 被 60 次/分限流挡下后，顶栏写成 GUEST、
    // A6 的「解匿」按钮消失，一张「看起来像没登录」的图差点进论文。
    profilePending: false,
    expiresIn: 0
  }),
  getters: {
    isLogged: (s) => !!s.token,
    // 顶栏显示的角色：读到 profile 才是权威值；只有令牌、身份还没读回时显示「身份确认中」，
    // 而不是谎称 GUEST。真·没登录才给 GUEST。
    role: (s) => {
      if (s.profile) return s.profile.role
      return s.token && s.profilePending ? 'PENDING' : 'GUEST'
    },
    rolePending: (s) => !!s.token && s.profilePending,
    // 后端只认这两个角色（SecurityConfig: hasAnyRole("ADMIN","SUPER")）
    isAdmin: (s) => ['ADMIN', 'SUPER'].includes((s.profile && s.profile.role) || ''),
    displayName: (s) => {
      if (!s.profile) return s.token && s.profilePending ? '身份确认中…' : '未登录'
      return s.profile.nickname || s.profile.username || '管理员'
    }
  },
  actions: {
    setToken(access, refresh, expiresIn) {
      this.token = access || ''
      this.refreshToken = refresh || ''
      this.expiresIn = expiresIn || 0
      if (access) localStorage.setItem(ADMIN_TOKEN_KEY, access)
      else localStorage.removeItem(ADMIN_TOKEN_KEY)
      if (refresh) localStorage.setItem(ADMIN_REFRESH_KEY, refresh)
      else localStorage.removeItem(ADMIN_REFRESH_KEY)
    },
    setProfile(p) { this.profile = p },
    clear() {
      this.setToken('', '', 0)
      this.profile = null
    },
    // F5 之后只剩令牌：向后端回读一次身份，把角色和操作人 id 找回来。
    // 不在 localStorage 里存一份 profile 副本 —— 那份副本会在「降权」之后继续活着，
    // 界面上「解匿」按钮还在、点下去全是 10003，那比少一个头像严重得多。
    // inflight 放在模块作用域：路由守卫可能在一秒内为多条导航各调一次，别打两次请求。
    async ensureProfile(retryMs) {
      if (this.profile) return this.profile
      if (!this.token) return null
      if (!inflight) {
        this.profilePending = true
        inflight = this.fetchProfileOnce(retryMs === undefined ? 1200 : retryMs)
          .then((u) => { this.setProfile(u); return u })
          .catch((e) => {
            // 只有「令牌作废 / 这个账号不是管理员」才清登录态；限流和网络抖动保留令牌——
            // 把一次还有效的会话踢下线，是比「暂时不知道角色」更坏的假阳性。
            if (isAuthCode(e.code) || e.code === CODE.FORBIDDEN) this.clear()
            return null
          })
          // pending 只在「令牌还在、身份还没读回来」这一段时间里为真。
          // 上一版这里写的是 !!this.token，于是回读成功之后它依然挂着，顶栏永远显示
          // 「身份确认中」——修一个「谎称 GUEST」的 bug，写出了另一个「谎称未确认」的 bug，
          // 这就是为什么改完必须让真浏览器再看一眼，而不是只看代码顺眼。
          .finally(() => { inflight = null; this.profilePending = !!this.token && !this.profile })
      }
      return inflight
    },
    // 一次临时失败不该让整个后台退化成 GUEST，所以给「可以好转的错误」一次退避重试。
    // 只重试一次：路由守卫在 await 这条链上，重试越多首屏越卡，而 429 的真实恢复时间就是几十秒。
    async fetchProfileOnce(backoff) {
      try {
        return await adminMe()
      } catch (e) {
        if (!isRetryable(e.code) || !backoff) throw e
        await new Promise((r) => setTimeout(r, backoff))
        return await adminMe()
      }
    }
  }
})

let inflight = null

/**
 * 值得再试一次的错误：10010 限流、90002/90003/90004 服务端临时不可用，以及没有 code 的网络异常。
 * 10002/10004/10005（令牌问题）和 10003（不是管理员）都不在这里——那三种重试只会原地打转。
 */
function isRetryable(code) {
  if (code === undefined || code === null) return true
  return code === CODE.RATE_LIMITED || code === CODE.DB_UNAVAILABLE ||
    code === CODE.CACHE_UNAVAILABLE || code === CODE.INTERNAL_ERROR
}