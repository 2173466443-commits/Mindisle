<template>
  <div class="feed">
    <!-- 1 U5 发布器（紧凑形态，与 /publish 同一组件） -->
    <section class="mi-card">
      <post-composer compact :topic-list="topicList" @published="onPublished" />
    </section>

    <!-- 1.5 信息流来源切换：广场 = 全站已过审的公开内容；关注 = 我只关注的那些人的更新（任务 T3.17）。
         这一排只切「读哪条流」，不切下面那排类型 Tab —— 因为 GET /api/feed/following 的入参
         只有 page/size/beforeId，没有 type。后端不接受的过滤条件不能在前端假装支持：
         客户端自己筛会做出「一页 20 条筛完只剩 3 条、点加载更多又翻回 20 条」的假翻页。
         类型 Tab 于是整排留在广场那节里，切到关注就一起藏起来。 -->
    <div class="mi-card source">
      <el-radio-group v-model="source" size="small" @change="switchSource">
        <el-radio-button v-for="s in SOURCES" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
      </el-radio-group>
      <span class="hint">{{ sourceHint }}</span>
    </div>

    <!-- 2 U3 广场：游标翻页的信息流 -->
    <section v-show="source === 'plaza'" class="plaza">
      <div class="tabs mi-card">
        <el-radio-group v-model="activeType" size="small" @change="switchType">
          <el-radio-button v-for="t in TABS" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
        </el-radio-group>
        <div class="tab-right">
          <span class="hint">{{ totalLine }}</span>
          <el-button size="small" text :loading="feed.loading" @click="reload">刷新</el-button>
        </div>
      </div>

      <stage-notice v-if="feed.errorCode" :code="feed.errorCode" stage="3" api-name="GET /api/posts"
                    :extra="listExtra" />

      <template v-else>
        <p v-if="!feed.items.length && !feed.loading" class="hint empty">
          这里暂时没有能看的帖子。广场只放「已过审且公开」的内容，你自己的私密帖与审核中的帖不在这条流里。
        </p>
        <div v-for="item in feed.items" :key="item.id" class="row">
          <post-card :item="item" @dismiss="dismiss" />
        </div>
        <div ref="sentinel" class="sentinel">
          <el-button v-if="feed.hasMore && !feed.loading" size="small" :loading="feed.loading" @click="loadMore">
            加载更多
          </el-button>
          <span v-else-if="feed.loading" class="hint">加载中…</span>
          <span v-else-if="feed.items.length" class="hint">到这里就是全部了（共 {{ feed.items.length }} 条已加载）</span>
        </div>
      </template>
    </section>

    <!-- 2.5 U3-b 关注流（任务 T3.17 · 需求 FR4.6）。它是另一份列表（usePagedPosts + 独立游标），
         与广场那份 pinia store 互不覆盖：切回广场时不该丢掉已经翻到的第 3 页。
         刻意用 v-show 而不是 v-if：两节各有一个无限滚动哨兵，节点一旦被销毁，
         观察器就再也等不到「第二次进视野」，表现成「切一次 Tab 之后无限滚动再也无效」——
         这类故障只有在真机上连点两下才会露头，所以宁可在模板里多写一行注释。 -->
    <section v-show="source === 'following'" class="plaza">
      <div class="tabs mi-card">
        <span class="hint">这里只有「你关注的人」的公开实名帖；他们发的树洞与匿名帖恒不出现（需求 FR1.4）。</span>
        <div class="tab-right">
          <span class="hint">{{ followingLine }}</span>
          <el-button size="small" text :loading="folLoading" @click="reloadFollowing">刷新</el-button>
        </div>
      </div>

      <p v-if="!user.isLogged" class="hint empty">关注流要先登录才读得到：它的第一件事是问「你关注了谁」。</p>
      <stage-notice v-else-if="folErrorCode" :code="folErrorCode" stage="3"
                    api-name="GET /api/feed/following" :extra="followingListExtra" />
      <template v-else>
        <p v-if="!folItems.length && !folLoading" class="hint empty">
          还没有可看的更新。去广场点某张卡片上的名字进主页关注，回来这一栏就会亮起来。
          刚注册的人在这里看到空白是正常的：后端对「没关注任何人」回的是空页，不是错误。
        </p>
        <div v-for="item in folItems" :key="item.id" class="row">
          <post-card :item="item" :dismissable="false" />
        </div>
        <div ref="folSentinel" class="sentinel">
          <el-button v-if="folHasMore && !folLoading" size="small" @click="loadFollowingMore">加载更多</el-button>
          <span v-else-if="folLoading" class="hint">加载中…</span>
          <span v-else-if="folItems.length" class="hint">到这里就是全部了（已加载 {{ folItems.length }} 条）</span>
        </div>
      </template>
    </section>

    <!-- 3 推荐流：后端仍是 90001，占位说明保留（阶段 6/7 才接真逻辑） -->
    <section class="mi-card">
      <div class="sec-head">
        <h2>为你推荐</h2>
        <el-button size="small" text :loading="busy.recommend" @click="loadRecommend">刷新</el-button>
      </div>
      <p class="formula">排序目标 emotion_match(u, i) = 1 − | valence_now(u) − comfort_valence(i) |，仅在当前心情为负向时启用（需求 §1.5 创新点 2）。</p>
      <stage-notice v-if="codes.recommend" :code="codes.recommend" :stage="RECOMMEND_STAGE" api-name="GET /api/feed/recommend" />
      <el-empty v-else-if="!recList.length" description="暂无推荐结果（召回与加权分别排在阶段 3 / 阶段 4）" />
      <ul v-else class="lines">
        <li v-for="(r, i) in recList" :key="i">{{ r.title || r.name || JSON.stringify(r) }}</li>
      </ul>
    </section>

    <!-- 4 官方话题墙（GET /api/topics，游客可逛）。点任意一张卡进话题详情页（任务 T3.8 · 需求 FR4.5）。
         标题里「官方」两个字是有判据的：这条查询过滤 is_official=1，用户自建的话题恒不进来。 -->
    <section class="mi-card">
      <div class="sec-head">
        <h2>官方话题墙</h2>
        <div class="sec-ops">
          <el-button class="btn-create-topic" size="small" text @click="openCreate">创建话题</el-button>
          <el-button size="small" text :loading="busy.topics" @click="loadTopics">重新加载</el-button>
        </div>
      </div>
      <stage-notice v-if="codes.topics" :code="codes.topics" stage="2" api-name="GET /api/topics" />
      <el-empty v-else-if="!topicList.length && !busy.topics" description="暂无已过审话题" />
      <div v-else class="topics">
        <div v-for="t in topicList" :key="t.id" class="topic topic-link" @click="goTopic(t)">
          <div class="t-name"># {{ t.name }}</div>
          <div class="t-desc">{{ t.desc || '官方话题' }}</div>
          <div class="t-meta">
            <span>发帖 {{ t.postCnt || 0 }}</span>
            <span>关注 {{ t.followCnt || 0 }}</span>
            <span>热度 {{ fmtHot(t.hotScore) }}</span>
            <span class="t-go">进入话题 →</span>
          </div>
        </div>
      </div>
      <p class="hint">数据来自 topic 表（audit_status=APPROVED 且 is_official=1，按 hot_score 倒序）。看不到内容多半是数据库还没建。</p>
      <p class="hint">你自己建的话题不会出现在这面墙上 —— 这里只挂官方角标的那些。建好之后从话题详情页进，或者去搜索页按名字找它。</p>
    </section>

    <!-- 创建话题（需求 FR1.7「用户可创建话题」+ FR4.5「创建需审核」）。入口挨着话题墙：
         想开一个新圈子的人，此刻正好在挑圈子。刻意不做 maxlength 而是自己数码点 ——
         后端 normalizeName 按 codePointCount 判超长，一个 emoji 算 1 个字，用 maxlength 会截错。 -->
    <el-dialog v-model="create.open" title="创建新话题" width="440px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="话题名">
          <el-input v-model="create.name" placeholder="例：#期末破防瞬间#" @input="onCreateInput" />
          <p class="hint">{{ nameLine }}</p>
        </el-form-item>
        <el-form-item label="一句话简介（可以不填）">
          <el-input v-model="create.desc" type="textarea" :rows="2" @input="onCreateInput" />
          <p class="hint">{{ descLine }}</p>
        </el-form-item>
      </el-form>
      <el-alert v-if="create.done" :type="create.done.tone" show-icon :closable="false" class="dlg-line"
                :title="create.done.title">
        <p class="dlg-body">{{ create.done.tip }}</p>
        <p v-if="create.done.note" class="dlg-body">{{ create.done.note }}</p>
        <el-button v-if="create.done.usable" size="small" type="primary" text @click="goCreated">现在就进去 →</el-button>
      </el-alert>
      <p v-else-if="create.error" class="hint dlg-line">{{ createErrorLine }}</p>
      <template #footer>
        <div class="dlg-foot">
          <span class="hint">每人每天最多创建 {{ TOPIC_CREATE_PER_DAY }} 个（后端配额）</span>
          <span class="dlg-btns">
            <el-button size="small" @click="create.open = false">关闭</el-button>
            <el-button class="btn-do-create" size="small" type="primary" :loading="create.busy" @click="doCreateTopic">提交</el-button>
          </span>
        </div>
      </template>
    </el-dialog>

    <section class="mi-card todo">
      <h2>后端尚未实现的接口（按排期）</h2>
      <ul class="lines">
        <li v-for="x in pending" :key="x">{{ x }}</li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { topics, recommend, followingFeed } from '@/api/feed'
import { createTopic, TOPIC_NAME_MAX, TOPIC_DESC_MAX, TOPIC_CREATE_PER_DAY, topicLength } from '@/api/topic'
import { NOT_IMPLEMENTED_YET } from '@/api/auth'
import { useFeedStore } from '@/stores/feed'
import { useUserStore } from '@/stores/user'
import { CODE } from '@/api/errorCode'
import { fmtHot } from '@/utils/format'
import PostCard from '@/components/PostCard.vue'
import PostComposer from '@/components/PostComposer.vue'
import { usePagedPosts } from '@/composables/usePagedPosts'
import StageNotice from '@/components/StageNotice.vue'

const TABS = [
  { value: '', label: '全部' },
  { value: 'hole', label: '树洞' },
  { value: 'help', label: '求助' },
  { value: 'normal', label: '分享' }
]

// 信息流的两个来源。值同时是上面 v-show 的比较对象，不做中文名以外的用途。
const SOURCES = [
  { value: 'plaza', label: '广场' },
  { value: 'following', label: '关注' }
]

// 话题卡与创建回执都要跳详情页（T3.8）。广场这张页原来是纯展示页，没有任何跳转需求，
// 所以 router 是这一批才引入的 —— 注意它必须在 useFeedStore 之前之后都无差别可用。
const router = useRouter()
const feed = useFeedStore()
const user = useUserStore()
const topicList = ref([])
const recList = ref([])
const pending = NOT_IMPLEMENTED_YET
const activeType = ref('')
const busy = reactive({ topics: false, recommend: false })

/* 推荐流的两件事，分开看：
   1) 后端 GET /api/feed/recommend 在阶段 6/7 之前**恒返 90001 / HTTP 501**（这是刻意的诚实占位，不用假数据糊弄演示）；
   2) 前端过去在 onMounted 里就调它一次，为一个**已经知道答案的问题**发请求，代价是每次进广场
      DevTools 都多一条红色 501 —— Gate3 第 4 条要「无 console 红字」，而这条红字既不是故障也没带来新信息。
   所以：占位说明改成由 RECOMMEND_LANDED 这个开关决定的静态状态，页面照常把「为什么这里没内容」讲清楚；
   「刷新」按钮仍然真调这个接口，后端哪天接上，点一下就出真数据，不需要改回前端。
   协同过滤落地时把 RECOMMEND_LANDED 置 true（并把 RECOMMEND_STAGE 里的「未实现」文案改掉）。 */
const RECOMMEND_LANDED = false
const RECOMMEND_STAGE = '6/7'
const codes = reactive({ topics: null, recommend: RECOMMEND_LANDED ? null : 90001 })
// 创建话题弹窗的状态。done 与 error 在每次敲字时清掉：留着一份旧回执，
// 用户会以为「第二次提交的结果」就是屏幕上那一块，而它其实是上一次的。
const create = reactive({ open: false, name: '', desc: '', busy: false, done: null, error: null })

const nameLine = computed(() => '已输入 ' + topicLength(create.name) + ' / 上限 ' + TOPIC_NAME_MAX
  + ' 字（一个表情算一个字，与后端同口径）；首尾空白与连续空格会被折叠，全站重名会被拒')
const descLine = computed(() => '已输入 ' + topicLength(create.desc) + ' / 上限 ' + TOPIC_DESC_MAX + ' 字；话题页上只显示前几行')
const createErrorLine = computed(() => {
  const code = Number(create.error)
  if (code === CODE.CONTENT_REJECTED) {
    return '机审把这个话题拦下了：名字或简介里有平台不允许发布的内容。换个说法再来一次 —— 被拦下的这次不落库，也不占今天的创建额度。'
  }
  if (code === CODE.RATE_LIMITED) return '今天的创建额度用完了，上限 ' + TOPIC_CREATE_PER_DAY + ' 个。先逛逛已有的话题吧。'
  if (code === CODE.PARAM_INVALID) {
    return '后端没接受这次提交，具体原因见上面那条提示：多半是名字为空、超过 ' + TOPIC_NAME_MAX + ' 字，或者这个话题已经有人建过了。'
  }
  if (code === CODE.FORBIDDEN) return '账号正在禁言期：能逛、能点赞、能关注话题，但不能创建话题（需求 BR6：禁言夺的是「说」的权利）。'
  return ''
})

const sentinel = ref(null)
let observer = null

const totalLine = computed(() => (feed.total >= 0 ? '广场共 ' + feed.total + ' 条可见内容' : '已加载 ' + feed.items.length + ' 条'))
const listExtra = computed(() => {
  if (Number(feed.errorCode) === CODE.DB_UNAVAILABLE) {
    return '数据库暂不可用：帖子列表要读 post 表，后端已按「库挂了也让页面活着」的口径降级，这里是空态而不是白屏。'
  }
  return ''
})

const source = ref('plaza')
const folSentinel = ref(null)
let folObserver = null
// 只在第一次进入「关注」Tab 时拉：来回切 Tab 不该每次都重转一圈（要最新的，右边有「刷新」）。
const folLoadedOnce = ref(false)

/**
 * 关注流列表。解构赋值这一步不是风格选择：usePagedPosts 返回的是「普通对象里装着一堆 ref」，
 * 而模板只会自动解包顶层绑定。写成 const flow = usePagedPosts(...) 再在模板里用 flow.items，
 * 拿到的是 Ref 本身而不是数组 —— v-for 会一声不响地什么都不画，看上去和「接口没数据」一模一样。
 * fol 前缀是因为广场那份已经占掉了 items/loading 这些名字，两份列表要在同一个作用域里共存。
 */
const {
  items: folItems,
  loading: folLoading,
  hasMore: folHasMore,
  total: folTotal,
  errorCode: folErrorCode,
  reload: folReload,
  loadMore: folLoadMore
} = usePagedPosts(followingFeed, {})

const sourceHint = computed(() => (source.value === 'plaza'
  ? '广场是全站视角：只放已过审且公开的内容，你自己设为仅自己可见的帖不在这条流里。'
  : '关注流是你自己的视角：只有你关注的人的公开实名帖，翻页口径与广场完全一致。'))

const followingLine = computed(() => {
  if (!user.isLogged || !folLoadedOnce.value) return ''
  return folTotal.value >= 0
    ? '关注的人共 ' + folTotal.value + ' 条可见更新'
    : '已加载 ' + folItems.value.length + ' 条'
})

// 三种「这里为什么没内容」要分开说：登录态没了 / 库挂了 / 你真的还没关注任何人。
// 最后那种不是错误，所以它走模板里的空态文案，不进这个函数。
const followingListExtra = computed(() => {
  const code = Number(folErrorCode.value)
  if (code === CODE.UNAUTHORIZED || code === CODE.TOKEN_EXPIRED || code === CODE.TOKEN_INVALID) {
    return '登录态已经不成立了：关注流读的是你自己的关注关系，这条路径不能退化成「那就给你看广场」。请重新登录。'
  }
  if (code === CODE.DB_UNAVAILABLE) {
    return '数据库暂不可用：关注流要读 user_follow 与 post 两张表，后端按「库挂了也让页面活着」的口径降级，这里是空态而不是白屏。'
  }
  return ''
})

async function ensureFollowing() {
  if (!user.isLogged || folLoadedOnce.value) return
  folLoadedOnce.value = true
  await folReload()
}

function switchSource(value) {
  if (value === 'following') ensureFollowing()
}

async function reloadFollowing() {
  folLoadedOnce.value = true
  await folReload()
}

async function loadFollowingMore() {
  if (!folHasMore.value || folLoading.value) return
  await folLoadMore()
}

async function loadTopics() {
  busy.topics = true
  codes.topics = null
  try {
    const data = await topics(10)
    topicList.value = Array.isArray(data) ? data : []
  } catch (e) {
    codes.topics = e.code || 'network'
    topicList.value = []
  } finally {
    busy.topics = false
  }
}

function openCreate() {
  create.open = true
  create.done = null
  create.error = null
}

function onCreateInput() {
  create.done = null
  create.error = null
}

/** 话题卡与刚建成的话题都跳同一个落点（T3.8 详情页）。搜索页那张卡走的是同一命名路由，不各写一份路径字符串。 */
function goTopic(t) {
  if (!t || t.id === undefined || t.id === null) return
  router.push({ name: 'topic-detail', params: { id: t.id } })
}

function goCreated() {
  goTopic(create.done)
}

async function doCreateTopic() {
  const name = create.name.trim()
  const desc = create.desc.trim()
  if (!name) { ElMessage.warning('话题名要写点什么才好记'); return }
  if (topicLength(name) > TOPIC_NAME_MAX) { ElMessage.warning('话题名最长 ' + TOPIC_NAME_MAX + ' 字'); return }
  if (topicLength(desc) > TOPIC_DESC_MAX) { ElMessage.warning('简介最长 ' + TOPIC_DESC_MAX + ' 字'); return }
  create.busy = true
  create.done = null
  create.error = null
  try {
    const data = await createTopic({ name: name, desc: desc })
    const usable = !!(data && data.usable)
    create.done = {
      id: data ? data.id : null,
      name: (data && data.name) || name,
      usable: usable,
      tone: usable ? 'success' : 'info',
      title: usable ? '# ' + ((data && data.name) || name) + ' 已创建，现在就能进去发帖' : '# ' + ((data && data.name) || name) + ' 已提交',
      tip: (data && data.tip) || '',
      // 🔴 这句必须跟着回执一起说：后端那句「通常很快」在阶段 3 是不成立的 ——
      // 预审开关（FR8.6）默认开着，而 audit_task.target_type 里还没有 topic 这一档，
      // 也就是今天没有任何通道能把一个待审话题放行。管理端属任务 6.1，所以任务 3.8 只能标 ◐。
      note: usable ? '' : '但要说明白：阶段 3 还没有审核台，今天没有人能把这条待审话题放行 ——'
        + '管理端属任务 6.1。它已经落库了，不是没建上，只是暂时进不去。'
    }
    create.name = ''
    create.desc = ''
    // 只有 APPROVED 才进这面墙，而自建话题在预审开着时必定不是 APPROVED：
    // 所以「重新加载话题墙」多半看不到变化，这一点由上面那句 note 解释，不假装刷新成功了。
    loadTopics()
  } catch (e) {
    create.error = e && e.code !== undefined && e.code !== null ? e.code : 'network'
  } finally {
    create.busy = false
  }
}

async function loadRecommend() {
  busy.recommend = true
  codes.recommend = null
  try {
    const data = await recommend()
    recList.value = Array.isArray(data) ? data : []
  } catch (e) {
    codes.recommend = e.code || 'network'
  } finally {
    busy.recommend = false
  }
}

async function reload() {
  await feed.fetchPage({ replace: true })
}

async function loadMore() {
  if (!feed.hasMore) return
  await feed.fetchPage()
}

async function switchType(value) {
  await feed.setType(value)
}

function onPublished(data) {
  if (!data) return
  if (data.status === 'PUBLISHED') {
    ElMessage.success('已发布')
    // 直接回第一屏：服务端刚写完库，前端自己拼一条列表项反而会出现「本地有、刷新没」的不一致。
    reload()
  } else if (data.status === 'HUMAN_REVIEW') {
    ElMessage.warning('已提交人工审核，这条现在只有你自己看得到')
  } else {
    ElMessage.info('这条内容没有发出去，原因见上方提示')
  }
}

function dismiss(id) {
  feed.dismiss(id)
  ElMessage.info('已从当前界面移除（真正的「不感兴趣」反馈要等阶段 7 写进召回过滤）')
}

// 无限滚动用 IntersectionObserver 而不是 scroll 事件：列表用 flex 布局，
// 滚动容器其实是 window，scroll 监听每帧都算一次高度；哨兵元素进视野才发请求，天然带去抖。
function bindObserver() {
  if (!sentinel.value || typeof IntersectionObserver === 'undefined') return
  observer = new IntersectionObserver(function (entries) {
    if (entries.some(function (x) { return x.isIntersecting; })) loadMore()
  }, { rootMargin: '240px' })
  observer.observe(sentinel.value)
}

// 关注流的无限滚动是同一套办法的第二次实例化，而不是和上面共用一个：
// 两个哨兵分属两节，共用就得在回调里判「现在显示的是哪一节」，那份判断很快会长成 if-else 链。
// 这里只多一道 source 守卫：被 v-show 藏起来的元素不会 intersecting，
// 而切回来的那一帧可能正好 intersecting —— 那也就是首次加载的时机，所以顺带兜住「没点过 Tab 也能滚出来」。
function bindFollowingObserver() {
  if (!folSentinel.value || typeof IntersectionObserver === 'undefined') return
  folObserver = new IntersectionObserver(function (entries) {
    if (source.value !== 'following') return
    if (entries.some(function (x) { return x.isIntersecting })) {
      if (!folLoadedOnce.value) ensureFollowing()
      else loadFollowingMore()
    }
  }, { rootMargin: '240px' })
  folObserver.observe(folSentinel.value)
}

onMounted(async () => {
  loadTopics()
  // 见上面 RECOMMEND_LANDED 的注释：没落地就别去问，问了只会留一条红字
  if (RECOMMEND_LANDED) loadRecommend()
  if (user.isLogged) {
    await reload()
    await nextTick()
    bindObserver()
    bindFollowingObserver()
  }
})
onUnmounted(() => {
  if (observer) observer.disconnect()
  if (folObserver) folObserver.disconnect()
})
</script>

<style scoped>
.feed { display: flex; flex-direction: column; gap: 18px; }
.plaza { display: flex; flex-direction: column; gap: 12px; }
.sec-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
h2 { margin: 0; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
.tabs { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 18px; }
.source { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 18px; }
.tab-right { display: flex; align-items: center; gap: 12px; }
.row { scroll-margin-top: 80px; }
.sentinel { display: flex; justify-content: center; align-items: center; min-height: 44px; }
.empty { padding: 18px; }
.hint { font-size: 12px; color: var(--mi-text-dim); }
.formula { font-size: 12px; color: var(--mi-text-dim); font-family: Consolas, monospace; margin: 0 0 8px; }
.topics { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
.topic { border: 1px solid var(--mi-border); border-radius: 10px; padding: 12px 14px; background: rgba(127, 167, 196, 0.06); }
.t-name { font-weight: 700; color: var(--mi-primary); }
.t-desc { font-size: 12px; color: var(--mi-text-dim); margin: 6px 0; min-height: 32px; }
.t-meta { display: flex; gap: 12px; font-size: 12px; color: var(--mi-mist); }
.sec-ops { display: flex; align-items: center; gap: 4px; }
.topic-link { cursor: pointer; }
.topic-link:hover { border-color: var(--mi-primary); }
.t-go { margin-left: auto; font-size: 12px; color: var(--mi-mist); }
.dlg-line { margin: 10px 0 0; line-height: 1.8; }
.dlg-body { margin: 4px 0 0; font-size: 13px; line-height: 1.7; }
.dlg-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.dlg-btns { display: flex; gap: 6px; }
.lines { margin: 0; padding-left: 20px; line-height: 2; font-size: 13px; }
.todo h2 { margin-bottom: 8px; }
</style>
