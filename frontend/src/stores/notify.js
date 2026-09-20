import { defineStore } from 'pinia'

// 未读数（FR9.2/9.3 通知中心与实时红点的客户端侧）
export const useNotifyStore = defineStore('notify', {
  state: () => ({ unread: 0, latest: [] }),
  actions: {
    setUnread(n) { this.unread = Number(n) || 0 },
    inc(n = 1) { this.unread += n },
    push(item) { this.latest.unshift(item); if (this.latest.length > 50) this.latest.pop() },
    clear() { this.unread = 0; this.latest = [] }
  }
})
