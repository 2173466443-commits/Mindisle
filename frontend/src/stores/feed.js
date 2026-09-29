import { defineStore } from 'pinia'
import { listPosts } from '@/api/post'
import { dislike } from '@/api/feed'

/** 每页条数：后端 normalize 夹在 1..50，取 20 与 PageQuery.DEFAULT_SIZE 同口径。 */
const PAGE_SIZE = 20

/**
 * 广场信息流状态（任务 T3.13 · U3）。
 *
 * <p>翻页只用一种方式：第一屏不带 beforeId（后端页码模式，回 total 给「共 N 条」），
 * 之后一律带 beforeId=上一页的 nextCursor。这里刻意不把 total 当翻页依据 ——
 * 服务端游标模式下 total 恒为 -1，写代码的人一旦习惯「items.length >= total 就停」，
 * 翻页会在第二屏直接停住。</p>
 */
export const useFeedStore = defineStore('feed', {
  state: () => ({
    items: [],
    /** '' 表示全部；normal/hole/help 白名单外的值不发请求（后端会 400）。 */
    type: '',
    nextCursor: null,
    /** 仅首屏（页码模式）有值，游标模式下为 -1，别拿它判「还有没有下一页」。 */
    total: -1,
    hasMore: true,
    loading: false,
    /** null 表示本次请求成功；否则是后端码或 'network'，由页面决定降级展示。 */
    errorCode: null,
    updatedAt: 0
  }),
  actions: {
    /**
     * 翻一页。首次调用不带游标，之后自动带 nextCursor。
     *
     * @param opts.replace true=丢弃已有列表重头拉（切 Tab、下拉刷新）
     */
    async fetchPage(opts) {
      const replace = !!(opts && opts.replace)
      if (this.loading) return false
      if (!replace && !this.nextCursor && this.items.length > 0) return false
      this.loading = true
      this.errorCode = null
      try {
        const params = { size: PAGE_SIZE }
        if (this.type) params.type = this.type
        if (!replace && this.nextCursor) params.beforeId = this.nextCursor
        const data = await listPosts(params)
        const rows = data && Array.isArray(data.list) ? data.list : []
        this.applyRows(rows, replace, data || {})
        return true
      } catch (e) {
        this.errorCode = e.code || 'network'
        return false
      } finally {
        this.loading = false
        this.updatedAt = Date.now()
      }
    },

    /**
     * 按 id 去重后拼接，并回填游标三件套。
     *
     * <p>去重是真需要的：SSE 新帖推送（阶段 5）会把列表顶部的条目挤进下一页，
     * 游标只保证「published_at 早于锚点」，不保证「你没看过」。</p>
     */
    applyRows(rows, replace, data) {
      if (replace) this.items = []
      const seen = new Set(this.items.map((x) => x.id))
      for (const row of rows) {
        if (row && row.id !== undefined && !seen.has(row.id)) {
          this.items.push(row)
          seen.add(row.id)
        }
      }
      this.hasMore = data.hasMore !== false && rows.length > 0
      // 首屏页码模式下后端也回 nextCursor；万一没回，就用本页末条 id 兜底，
      // 宁可重复一条也不能翻不动（重复由上面的 seen 吸收）。
      this.nextCursor = data.nextCursor || (rows.length ? rows[rows.length - 1].id : null)
      if (typeof data.total === 'number' && data.total >= 0) this.total = data.total
    },

    /** 切 Tab（全部/树洞/求助）：换 type 后从第一屏重来。 */
    async setType(type) {
      this.type = type || ''
      this.nextCursor = null
      this.items = []
      this.hasMore = true
      this.total = -1
      return this.fetchPage({ replace: true })
    },

    /** 发帖成功后把新帖塞回队首：后端刚写完库，再拉一次首屏最省事，但会闪一下列表。 */
    prepend(row) {
      if (!row || row.id === undefined) return
      if (this.items.some((x) => x.id === row.id)) return
      this.items.unshift(row)
    },

    /**
     * 「不感兴趣」（任务 T7.7 · 需求 FR1.7 / FR5.7 · Gate7 判据 D6「当场点当场没」）。
     *
     * <p><b>口径从「本地剔除」改成了「先拿后端回执，再动列表」</b>。旧写法是阶段 3 的既定口径
     * （那句注释写着「阶段 7 才接真过滤逻辑」），它的前提是接口不存在；现在接口有了，
     * 继续乐观剔除会做出最难查的一种不一致 —— 后端拒了（10002 未登录 / 10003 库不可用），
     * 帖却已经从界面上消失，一刷新又全回来，而用户在中间那一秒相信的是
     * 「我已经告诉平台不推它了」。负反馈尤其不能这样：它承诺的是「以后也不会」。</p>
     *
     * <p>后端这一步做三件事（顺序在后端 FeedService#dislike 的注释里）：记 user_action(dislike)、
     * 逻辑删除 recommend_result 本批这一行、沿 item_similarity 压掉邻居。所以点完之后
     * 广场少一条、推荐流的下一屏也少一批，这是同一份事实的两种体现，不是两个开关。</p>
     *
     * @returns 成功回 {removed, removedSimilar}；失败回 null（列表保持原样，错误文案交给 http 层弹条）
     */
    async dismiss(id) {
      if (id === undefined || id === null) return null
      try {
        const data = await dislike(id)
        this.items = this.items.filter((x) => x.id !== id)
        return data
      } catch (e) {
        return null
      }
    },

    reset() {
      this.items = []
      this.nextCursor = null
      this.total = -1
      this.hasMore = true
      this.errorCode = null
    }
  }
})
