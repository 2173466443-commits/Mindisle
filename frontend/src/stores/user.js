import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('mindisle_token') || '',
    refreshToken: localStorage.getItem('mindisle_refresh') || '',
    profile: null,
    // consent：是否已完成隐私协议 + 敏感数据单独同意（BR1/BR2，user_consent 表）
    consent: { privacy: false, sensitive: false, loaded: false }
  }),
  getters: {
    isLogged: (s) => !!s.token,
    role: (s) => s.profile?.role || 'GUEST',
    isAdmin: (s) => ['ADMIN', 'SUPER'].includes(s.profile?.role)
  },
  actions: {
    setToken(access, refresh) {
      this.token = access || ''
      this.refreshToken = refresh || ''
      if (access) localStorage.setItem('mindisle_token', access)
      else localStorage.removeItem('mindisle_token')
      if (refresh) localStorage.setItem('mindisle_refresh', refresh)
      else localStorage.removeItem('mindisle_refresh')
    },
    setProfile(p) { this.profile = p },
    setConsent(c) { this.consent = { ...this.consent, ...c, loaded: true } },
    clear() {
      this.setToken('', '')
      this.profile = null
      this.consent = { privacy: false, sensitive: false, loaded: false }
    }
  }
})
