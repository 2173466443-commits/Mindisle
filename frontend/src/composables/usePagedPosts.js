import { ref } from 'vue'

/** 每页条数：与后端 PageQuery.DEFAULT_SIZE、stores/feed.js 的 PAGE_SIZE 同口径（都会被 normalize 夹进 1..50）。 */
const PAGE_SIZE = 20

/**
 * 单页自用的「游标翻页列表」（任务 T3.13 第二批 · 手册 §6.2 U11/U12）。
 *
 * <p><b>为什么不是 pinia store</b>：U12「我的帖子」和 U11「屿友主页」都是进页面才要、离开就没用的
 * 局部列表。放进全局 store 会出现「点开 A 的主页 → 返回 → 点开 B 的主页，屏幕上先闪一下 A 的缓存」，
 * 而修它的正确做法是在每个入口手动清 store —— 那等于把全局态又拆回局部态，还多出一条必须遵守的纪律。
 * 组件级 composable 随组件销毁，天生不需要这条纪律。广场（U3）相反：它要在发帖成功时插到队首、
 * 阶段 5 的 SSE 推新也要写它，所以继续留在 stores/feed.js，两处不强行统一。</p>
 *
 * <p><b>为什么翻页口径与 stores/feed.js 刻意保持一致</b>：后端三张列表（广场 / 我的 / 主页）共用同一个
 * PostQueryService.pageResult，出参形状、游标语义、total 为 -1 的约定完全相同。前端两处各写一套
 * 「什么时候算翻到底」，迟早会出现同一个接口在广场能翻页、在主页翻不动 —— 而这种偏差只有真机点才暴露。
 * 三条一致：首屏走页码模式（后端回 total，用于「共 N 条」），之后一律 beforeId=nextCursor；
 * hasMore 只看后端的 hasMore 与「本页是否为空」，绝不拿 total 判翻页；拼接前按 id 去重。</p>
 *
 * <p><b>并发用一个 seq 兜住</b>：切 Tab 时旧请求可能比新请求后到。直接丢弃后到的请求（loading 就 return）
 * 会让「切 Tab」点了没反应，所以这里不丢请求、只丢晚到的响应：每次 load 领一个号，回来时号不是最新的就整份丢掉。
 * 代价是一次点击可能发出两个请求，换来的是列表永远显示最后一次操作想要的那份数据。</p>
 *
 * <p><b>两套翻页口径（opts.pager）不是历史包袱，是后端有两个不同的分页实现</b>：
 * 广场 / 关注 / 我的 / 主页这四条流走的是 PostQueryService.pageResult，认 beforeId 游标；
 * 推荐流走的是 FeedService.feed，它只取 PageQuery#offset()（=（page−1）×size），
 * <b>压根不看 beforeId</b>，可出参又照用 PageResult.ofCursor，于是照样回一个 nextCursor。
 * 拿游标模式去驱动推荐流的结果是：第二屏带着 nextCursor 打过去、后端看不懂这个参数、
 * 于是原样退回第一屏那六条，前端按 id 去重之后界面上就是「点加载更多什么都不发生」。
 * 所以这里加 pager:'page'，而不是给推荐流再写一份第二套列表 —— 去重、seq 竞态、
 * hasMore 口径这三件事一旦有两份，就一定会出现两份不一致。</p>
 *
 * @param fetcher 取数函数，签名 (params) => Promise of PageResult，由调用方注入，便于同一实现喂不同接口
 * @param opts.query 发请求那一刻才取的额外参数（比如当前 Tab 的 status），不传即空对象
 * @param opts.pager 'cursor'（默认，四条帖子流）| 'page'（推荐流，理由见上）
 * @param opts.size 每页条数，不传即 PAGE_SIZE=20；推荐流一屏 6 条用的就是这个口
 */
export function usePagedPosts(fetcher, opts) {
  const options = opts || {}
  const pager = options.pager === 'page' ? 'page' : 'cursor'
  const pageSize = Number(options.size) > 0 ? Number(options.size) : PAGE_SIZE
  const items = ref([])
  const loading = ref(false)
  const hasMore = ref(true)
  /** 只有首屏（页码模式）后端才回真值，游标模式下恒为 -1，别拿它判「还有没有下一页」。 */
  const total = ref(-1)
  /** null 表示上一次请求成功；否则是后端 code 或 'network'，由页面决定怎么降级展示（手册 §5.8 第 1 条：不许静默失败）。 */
  const errorCode = ref(null)
  let cursor = null
  /** 只在 pager:'page' 下有意义。初值 0 而不是 1：第一次 load 无论哪种模式都往后走一格。 */
  let page = 0
  let seq = 0

  /**
   * @param replace  true = 整屏替换（首屏、点「刷新」）；false = 接在已有列表尾巴上
   * @param advance  只在 replace=true 时用：true = 「换一批」，整屏替换但翻页位照样往前走一格
   */
  async function load(replace, advance) {
    if (!replace && loading.value) return false
    const mine = ++seq
    loading.value = true
    errorCode.value = null
    try {
      const params = Object.assign({ size: pageSize }, options.query ? options.query() : {})
      if (pager === 'page') {
        // 三种走位收敛成一行：首屏归 1；加载更多与换一批都是「往后一格」。
        page = replace && !advance ? 1 : page + 1
        params.page = page
      } else if (!replace && cursor) {
        params.beforeId = cursor
      }
      const data = await fetcher(params)
      if (mine !== seq) return false
      const rows = data && Array.isArray(data.list) ? data.list : []
      applyRows(rows, replace, data || {})
      return true
    } catch (e) {
      if (mine !== seq) return false
      errorCode.value = e && e.code !== undefined && e.code !== null ? e.code : 'network'
      return false
    } finally {
      // 晚到的那份不许把新请求的 loading 关掉，否则「刷新」按钮会在新请求还在跑时就松开
      if (mine === seq) loading.value = false
    }
  }

  function applyRows(rows, replace, data) {
    if (replace) items.value = []
    const seen = new Set(items.value.map(function (x) { return x.id }))
    rows.forEach(function (row) {
      if (row && row.id !== undefined && !seen.has(row.id)) {
        items.value.push(row)
        seen.add(row.id)
      }
    })
    hasMore.value = !(data && data.hasMore === false) && rows.length > 0
    // 后端没回 nextCursor 时用本页末条 id 兜底：宁可重复一条（上面 seen 会吸收）也不能翻不动。
    // page 模式刻意不回写这个游标：推荐流出参里那个 nextCursor 是 ofCursor 顺手填的末条 id，
    // 拿它去发请求等于什么都没发（后端不看），留着它只会诱使后来人「顺手改成游标模式」。
    if (pager !== 'page') cursor = data.nextCursor || (rows.length ? rows[rows.length - 1].id : null)
    if (typeof data.total === 'number' && data.total >= 0) total.value = data.total
  }

  /** 丢弃已有列表重头拉：切 Tab、点刷新、换主页对象都走这里。 */
  function reload() {
    cursor = null
    page = 0
    items.value = []
    total.value = -1
    hasMore.value = true
    return load(true)
  }

  /**
   * 不请求后端就把列表清干净：地址栏编号从「能读」变成「读不了」时用
   * （UserHomeView 与 TopicDetailView 的 watch 各有一个调用点）。
   * 为什么不能直接 reload()：reload 会带着空编号去打接口，既换回一个 404，
   * 也违背「前端先判一次、不发注定失败的请求」那条口径。
   * 这里顺手把 seq 推进一格、并把 loading 落回 false：否则还在路上的那一次响应
   * 回来时会把已经清空的列表又填满上一个人的帖子，而「刷新」那颗按钮会一直转。
   */
  function clear() {
    seq += 1
    cursor = null
    page = 0
    items.value = []
    total.value = -1
    hasMore.value = true
    errorCode.value = null
    loading.value = false
  }

  function loadMore() {
    if (!hasMore.value) return Promise.resolve(false)
    return load(false)
  }

  /**
   * 「换一批」：整屏替换，但翻页位往后走一格（Gate7 判据 D6「刷新三次内容不重复」就落在这一步）。
   *
   * <p>为什么不是 reload()：reload 把页号归 1，用户连点三次「换一批」会拿到同一批六条 ——
   * 而那正是 D6 要抓的行为。为什么和 loadMore 共用一个 page：两条手势消费同一个游标，
   * 才会出现「先加载更多再换一批」仍然不重复；各记一套的话，两套之间必然互咬。</p>
   *
   * <p>读到这一批见底（hasMore=false）时退回第一屏重头再来：推荐缓存每用户每轮只有 50 条，
   * 见底之后再往后翻必然是空页，而「点了换一批结果整栏空掉」比「又看到第一批」更让人以为坏了。
   * 这个回卷是明说的（按钮旁边那行字会写「这一批已看完」），不是偷偷重复喂给用户。</p>
   */
  function swapToNext() {
    if (!hasMore.value) return reload()
    return load(true, true)
  }

  return { items, loading, hasMore, total, errorCode, reload, loadMore, swapToNext, clear }
}
