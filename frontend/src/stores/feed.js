import { defineStore } from 'pinia'

// 列表与去重（§5.8 第 4 条）：feed 分页拼接时按 postId 去重，避免 SSE/WS 推送与分页重复。
export const useFeedStore = defineStore('feed', {
  state: () => ({
    items: [],
    cursor: 0,
    hasMore: true,
    loading: false
  }),
  actions: {
    replace(list) {
      this.items = Array.isArray(list) ? list.slice() : []
      this.cursor = this.items.length
    },
    append(list) {
      if (!Array.isArray(list) || list.length === 0) return
      const seen = new Set(this.items.map((x) => x.id ?? x.postId))
      for (const it of list) {
        const key = it.id ?? it.postId
        if (key === undefined || !seen.has(key)) {
          this.items.push(it)
          seen.add(key)
        }
      }
      this.cursor = this.items.length
    },
    reset() { this.items = []; this.cursor = 0; this.hasMore = true }
  }
})
