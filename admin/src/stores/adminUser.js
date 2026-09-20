import { defineStore } from 'pinia'
import { ADMIN_TOKEN_KEY, ADMIN_REFRESH_KEY } from '@/api/http'

// 管理端身份：后端登录返回 AuthResponse{accessToken,refreshToken,expiresIn,user}
// user 只有 7 个字段（id/username/nickname/avatarUrl/gender/role/status），role 是权限唯一来源。
export const useAdminUserStore = defineStore('adminUser', {
  state: () => ({
    token: localStorage.getItem(ADMIN_TOKEN_KEY) || '',
    refreshToken: localStorage.getItem(ADMIN_REFRESH_KEY) || '',
    profile: null,
    expiresIn: 0
  }),
  getters: {
    isLogged: (s) => !!s.token,
    role: (s) => (s.profile && s.profile.role) || 'GUEST',
    // 后端只认这两个角色（SecurityConfig: hasAnyRole("ADMIN","SUPER")）
    isAdmin: (s) => ['ADMIN', 'SUPER'].includes((s.profile && s.profile.role) || ''),
    displayName: (s) => {
      if (!s.profile) return '未登录'
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
    }
  }
})
