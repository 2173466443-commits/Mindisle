<template>
  <div class="feed">
    <!-- 1 U5 发布器（紧凑形态，与 /publish 同一组件）。
         2026-09-30 改版：默认收成一条胶囊，点一下才展开成整块表单。
         为什么收 —— 小红书进首页第一眼是笔记墙，而这块表单原先把首屏整屏占满，
         「看内容」的路被「发内容」的路挡住了；发布是低频动作，不该占最高位。
         为什么用 v-show 而不是 v-if —— 收起是视觉层的事，不该动结构层：
         取证脚本（domprobe 那套 .composer 选择器）和草稿自动保存都要求发布器 DOM 常驻，
         v-if 会把它整个卸载，那时「收起一个框」就变成了「改一条契约」。 -->
    <section class="mi-card composer-card" :class="{ 'is-pill': !composerOpen }">
      <button v-if="!composerOpen" class="composer-pill" type="button" @click="composerOpen = true">
        <span class="pill-face">🏝</span>
        <span class="pill-ph">此刻想说点什么…</span>
        <span class="pill-cta">写点什么</span>
      </button>
      <div v-show="composerOpen" class="composer-fold-wrap">
        <post-composer compact :topic-list="topicList" @published="onPublished" />
        <button class="pill-fold" type="button" @click="composerOpen = false">收起发布框</button>
      </div>
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
        <!-- 2026-09-30 改版：一行一张张卡片（笔记墙）。row 这个类没删，只是从「包裹层」
             挪到了卡片自己身上（Vue 的属性透传会把它合并到 <article class="post"> 上），
             因为取证脚本数的是 .feed .row 的条数，少一层 div 就少一格计数。 -->
        <div class="mi-wall">
          <post-card v-for="item in feed.items" :key="item.id" class="row" :item="item" @dismiss="dismiss" />
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
        <div class="mi-wall">
          <post-card v-for="item in folItems" :key="item.id" class="row" :item="item" :dismissable="false" />
        </div>
        <div ref="folSentinel" class="sentinel">
          <el-button v-if="folHasMore && !folLoading" size="small" @click="loadFollowingMore">加载更多</el-button>
          <span v-else-if="folLoading" class="hint">加载中…</span>
          <span v-else-if="folItems.length" class="hint">到这里就是全部了（已加载 {{ folItems.length }} 条）</span>
        </div>
      </template>
    </section>

    <!-- 3 推荐流（任务 T7.9/T7.10 接通 · 手册 §10.2 7.9/7.10 · 需求 FR5.6、FR5.8、D6）。
         2026-09-29 之前这一节是一张「阶段 7 未实现」的占位卡，现在是真数据：
         卡片仍然复用广场那张 PostCard（D6 要的就是「同一形状」），多出来的只有 #reason 插槽那一行。
         刻意不加 v-show="source === 'plaza'"：这一节不属于「读哪条流」那个切换，
         它既不是广场也不是关注，切到关注 Tab 时把它一起藏起来没有任何依据（手册 §6.2 U3 没这么写）。 -->
    <section class="mi-card recommend">
      <div class="sec-head">
        <h2>为你推荐</h2>
        <div class="sec-ops">
          <span class="hint">{{ recLine }}</span>
          <el-button class="btn-rec-swap" size="small" text :loading="recLoading" @click="swapRecommend">换一批</el-button>
          <el-button class="btn-rec-refresh" size="small" text :loading="recLoading" @click="reloadRecommend">刷新</el-button>
        </div>
      </div>
      <p class="formula">排序目标 emotion_match(u, i) = 1 − | valence_now(u) − comfort_valence(i) |，仅在当前心情为负向时启用（需求 §1.5 创新点 2）。这条流读的是离线批次预计算的 recommend_result 缓存，缓存见底或整批不可见时退热读兜底。</p>

      <!-- 未登录、后端报错、真的没缓存，是三件不同的事，分三处说（手册 §5.8 第 1 条：不许静默失败）。 -->
      <p v-if="!user.isLogged" class="hint empty">
        推荐流要先登录才读得到：它的第一件事是问「你读过什么」，游客没有行为矩阵。
        给游客一条长得像推荐的东西，等于把对照组当实验组卖（需求 §9.1 六组对照就是这么废的）。
      </p>
      <stage-notice v-else-if="recErrorCode" :code="recErrorCode" stage="7" api-name="GET /api/feed/recommend" :extra="recExtra" />
      <template v-else>
        <div class="mi-wall">
          <post-card v-for="item in recItems" :key="item.id" class="rec-card rec-row" :item="item" @dismiss="dismissRecommend(item)">
            <template #reason>
              <div class="rec-why">
                <span class="rec-reason">{{ item.recReason || recReasonFallback(item) }}</span>
                <el-popover placement="top-start" :width="300" trigger="click">
                  <template #reference>
                    <el-button class="btn-rec-why" size="small" text @click.stop="">为什么推给我</el-button>
                  </template>
                  <div class="why-pop">
                    <p><b>为什么推给我</b></p>
                    <p>召回通道：{{ channelLabel(item.recChannel) }}（<code>{{ item.recChannel || '未知' }}</code>）</p>
                    <p>离线打分：<code>{{ fmtRecScore(item.recScore) }}</code></p>
                    <p>这条流为什么有它：{{ item.recReason || recReasonFallback(item) }}</p>
                    <p class="hint">上面三行都是 GET /api/feed/recommend 原样给的字段（recall_channel / score / reason）。页面不自己算分，也不自己编理由。</p>
                  </div>
                </el-popover>
              </div>
            </template>
          </post-card>
        </div>
        <div class="rec-foot">
          <el-button v-if="recHasMore" class="btn-rec-more" size="small" :loading="recLoading" @click="loadRecommendMore">加载更多</el-button>
          <span v-else class="hint">{{ recEndLine }}</span>
        </div>
      </template>
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
import { topics, recommend, dislike, followingFeed } from '@/api/feed'
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
const pending = NOT_IMPLEMENTED_YET
const activeType = ref('')
const busy = reactive({ topics: false })

/* 推荐流（任务 T7.9 / T7.10 接通 · 手册 §10.2 7.9-7.10 · 需求 FR5.6、FR5.8 · Gate7 判据 D6）。
   2026-09-29 之前这一节是一张「阶段 7 未实现」的静态占位卡，当时的理由是「为一个已经知道答案的问题
   发请求，只会让每次进广场多留一条红色 501」。那个前提已经不成立了，所以占位开关
   （RECOMMEND_LANDED / RECOMMEND_STAGE）连同 codes.recommend 一起删掉：
   留着它们，下一次接口出错时页面会永远显示那张假占位卡，而不是错误码 —— 那是把「不许静默失败」反着做。 */

/**
 * 一屏 6 条，不是广场那个 20。两条依据：
 * 1) 后端每用户每批只写 knobs.cacheRowsPerUser = RecConstants.FEED_MAX_SIZE = 50 条缓存，
 *    于是「换一批 ×3」＝18 条仍落在同一批次内，Gate7 D6 要的「刷新三次内容不重复」才量得准
 *    —— 若一屏 20 条，第三屏就跨批次了，届时重复不重复混着「批次换血」一起发生，判据作废；
 * 2) 这一节夹在广场与话题墙中间，不是整页主角，20 条会把话题墙推到两屏之外。
 */
const REC_SIZE = 6
const codes = reactive({ topics: null })

/**
 * 推荐接口的出参是三层：{list:[{post:{id,…}, reason, recallChannel, score}]}，
 * 而 PostCard 与 usePagedPosts 认的都是广场那个扁平形状（顶层就有 id）。
 *
 * <p><b>这一层扁平化不是图省事，是正确性</b>：usePagedPosts 按 row.id 去重，
 * 少了这一步 row.id 恒为 undefined —— 第一条被收下、后面每一条都被判成
 * 「和已见过的 undefined 重复」，于是第一屏之后所有翻页静默丢光数据，
 * 界面上表现成「点加载更多什么都不发生」，而且控制台一声不响。
 * 三个附加字段统一带 rec 前缀，是为了不和 post 自己的字段（如 status）撞名。</p>
 */
async function recommendPage(params) {
  const data = await recommend(params)
  const rows = data && Array.isArray(data.list) ? data.list : []
  const flat = rows.map(function (row) {
    const post = (row && row.post) || {}
    return Object.assign({}, post, {
      id: post.id,
      recReason: row ? row.reason : null,
      recChannel: row ? row.recallChannel : null,
      recScore: row ? row.score : null
    })
  })
  return Object.assign({}, data, { list: flat })
}

/** 与关注流同样的解构写法（理由见上面 folItems 那段注释：模板只自动解包顶层绑定）。 */
const {
  items: recItems,
  loading: recLoading,
  hasMore: recHasMore,
  errorCode: recErrorCode,
  reload: recReload,
  loadMore: recLoadMore,
  swapToNext: recSwapToNext
} = usePagedPosts(recommendPage, { pager: 'page', size: REC_SIZE })

/**
 * 召回通道的中文名。key 是后端 RecConstants / ColdStart 的通道常量的值，
 * 一个都不许自己发明：这张表读错一次，「为什么推给我」那三行就全成了前端编的话。
 * 未知通道回「未标注通道」而不是留空 —— 空会被读成「后端坏了」，而真正该问的是这条数据。
 */
const CHANNEL_LABELS = {
  hot: '热读榜兜底',
  itemcf: '你读过的帖子的相似帖',
  usercf: '口味相近的屿民在看',
  content: '你关注的话题',
  emotion: '今天的心情加权',
  explore: '探索位（推一条你没读过的）'
}

function channelLabel(ch) {
  return CHANNEL_LABELS[ch] || '未标注通道'
}

/**
 * 离线分数的显示口径。热读兜底通道的 score 后端恒为 null（FeedService#hotFallback 明写「reason 留空、不给分」），
 * 所以这里不能写成 (v||0).toFixed(4) —— 那会把「没有打分」显示成「0.0000」，
 * 用户读成「这条相关性极低」，而事实是它压根没参与相关性计算。
 */
function fmtRecScore(v) {
  if (v === null || v === undefined || v === '') return '未打分（热读兜底通道不给相关性分）'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(4) : String(v)
}

/** reason 为 null 时说什么：只说「后端没给理由」这一件事实，绝不代它编一句像是个性化出来的话（需求 D6 的下半句）。 */
function recReasonFallback(item) {
  if (item && item.recChannel === 'hot') {
    return '这条来自热读兜底：你的协同过滤缓存这一轮没覆盖到它，后端因此没给个性化理由。'
  }
  return '后端这一条没返回理由（reason 为空）。这一栏宁可空着，也不替你编一句。'
}

const recLine = computed(() => {
  const rows = recItems.value
  if (!rows.length) return ''
  const personalized = rows.filter(function (x) { return x.recChannel && x.recChannel !== 'hot' }).length
  return '已加载 ' + rows.length + ' 条 · 其中协同过滤/内容/情绪等个性化通道 ' + personalized
    + ' 条，热读兜底 ' + (rows.length - personalized) + ' 条'
})

const recEndLine = computed(() => {
  if (!recItems.value.length) {
    return '后端这一轮没有可推的内容：推荐缓存为空，热读兜底池也没凑出一屏。这不是错误，稍后点「刷新」再看。'
  }
  return '这一批 ' + recItems.value.length + ' 条已全部看到（每用户每批缓存上限 50 条，见底之后再往后翻必然是空页）。'
    + '点「换一批」会退回第一屏重新给一批 —— 明说这一点，是为了不让人以为「换一批」永远换得出新东西。'
})

// 三种「这一栏为什么没内容」分开说：登录态失效 / 库挂了 / 真的没缓存。最后那种不是错误，走模板空态与上面的 recEndLine。
const recExtra = computed(() => {
  const code = Number(recErrorCode.value)
  if (code === CODE.UNAUTHORIZED || code === CODE.TOKEN_EXPIRED || code === CODE.TOKEN_INVALID) {
    return '登录态已经不成立了：推荐流读的是你自己的行为矩阵与 recommend_result 缓存，'
      + '这条路径不能退化成「那就给你看热读榜」——那等于把对照组当实验组卖（需求 §9.1）。请重新登录。'
  }
  if (code === CODE.DB_UNAVAILABLE) {
    return '数据库暂不可用：推荐流要读 recommend_result 与 post 两张表，后端已按「库挂了也让页面活着」的口径降级，'
      + '这里是空态而不是白屏。'
  }
  return ''
})

async function reloadRecommend() {
  await recReload()
}

async function swapRecommend() {
  await recSwapToNext()
}

async function loadRecommendMore() {
  if (!recHasMore.value || recLoading.value) return
  await recLoadMore()
}

/**
 * 推荐位上的「不感兴趣」（T7.7 · POST /api/feed/dislike · Gate7 D6「当场点当场没」）。
 *
 * <p>回执里两个数的口径抄后端 FeedService#dislike：removed = 本批次缓存里被逻辑删掉的这条的行数，
 * removedSimilar = 沿 item_similarity 一起被压掉的邻居行数。两者都是「数据库受影响行数」，
 * 不是「你屏幕上少了几条」，所以提示里分开写，不合并成一个总数糊在一起。</p>
 */
async function dismissRecommend(item) {
  if (!item || item.id === undefined || item.id === null) return
  try {
    const data = await dislike(item.id)
    recItems.value = recItems.value.filter(function (x) { return x.id !== item.id })
    const removed = Number((data && data.removed) || 0)
    const removedSimilar = Number((data && data.removedSimilar) || 0)
    ElMessage.success('这条已从本批缓存里删掉（' + removed + ' 行），并压掉与它相似的 '
      + removedSimilar + ' 条下一屏候选'
      + (removedSimilar ? '' : '：这一条暂时取不到邻居，只驳回本尊'))
  } catch (e) {
    // 失败不在这里补文案：dislike() 刻意不加 silent，http 层已经把后端那句原因弹成红条（10002 未登录等）。
    // 这里再弹一条就成了同一件事说两遍。
  }
}
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
/* 发布器收起/展开（2026-09-30 改版）：默认收起成一条胶囊。
   刷新后回到收起态是有意的 —— 它只是「这块表单要不要占屏」的临时视图开关，
   不是用户写下的内容，所以不进 localStorage（草稿本身仍然照旧自动存）。 */
const composerOpen = ref(false)
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
    composerOpen.value = false  // 发完自动收回胶囊：这块表单刚用完还占着首屏，没有道理
    // 直接回第一屏：服务端刚写完库，前端自己拼一条列表项反而会出现「本地有、刷新没」的不一致。
    reload()
  } else if (data.status === 'HUMAN_REVIEW') {
    ElMessage.warning('已提交人工审核，这条现在只有你自己看得到')
  } else {
    ElMessage.info('这条内容没有发出去，原因见上方提示')
  }
}

/**
 * 广场卡片上的「不感兴趣」（手册 §6.2 U3 那颗按钮，任务 T7.7 接的真逻辑）。
 *
 * <p>这里刻意<b>不做乐观剔除</b>：过去是「本地先删掉、再弹一句『真正的反馈要等阶段 7』」。
 * 阶段 7 落地之后如果保留那套写法，会出现最难查的一种不一致 ——
 * 后端拒了（未登录 10002 / 库挂了），帖却已经从界面上消失，刷新又全部回来，
 * 而用户刚才那一秒相信的是「我已经告诉平台不推它了」。
 * 所以现在改成先等回执、成功后才让 store 剔除，失败就一条都不动（错误文案由 http 层弹，
 * dislike() 不带 silent）。剔除同时落到 recommend_result：广场这一条也会从推荐缓存里被压掉，
 * 这个 scene 口径的简化写在 api/feed.js 的注释里，不在这儿重复一遍。</p>
 */
async function dismiss(id) {
  if (id === undefined || id === null) return
  const data = await feed.dismiss(id)
  if (data) {
    ElMessage.success('这条已从本批推荐缓存里删掉（' + Number(data.removed || 0) + ' 行），'
      + '与它相似的下一屏候选也压掉了 ' + Number(data.removedSimilar || 0) + ' 条；'
      + '这条负反馈同时记进你的行为流，下一次离线重算照样生效')
  }
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
  // 登录了才拉一屏推荐：这条流不做游客态（后端 recommend 未登录直接 10002），
  // 所以这里先判一次 user.isLogged，而不是发一个注定失败的请求再把红条弹给用户看。
  if (user.isLogged) {
    recReload()
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
.feed { display: flex; flex-direction: column; gap: 14px; }
/* ---- 发布器：收起态是一条胶囊，展开态才是整块表单（模板注释里写了为什么用 v-show） ---- */
.composer-card.is-pill { padding: 10px; }
.composer-pill {
  display: flex; align-items: center; gap: 10px; width: 100%;
  height: 44px; padding: 0 8px 0 14px; margin: 0;
  border: none; border-radius: 999px; background: var(--mi-fill);
  color: var(--mi-text-dim); font: inherit; font-size: 14px; cursor: pointer; text-align: left;
  transition: background .15s;
}
.composer-pill:hover { background: var(--mi-hover); }
.pill-face { flex: none; font-size: 16px; line-height: 1; }
.pill-ph { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 右侧那颗红色小胶囊是「点这里能干什么」的提示，纯装饰，不另绑事件。 */
.pill-cta {
  flex: none; padding: 6px 14px; border-radius: 999px;
  background: var(--mi-primary); color: var(--mi-on-primary); font-size: 13px; font-weight: 600;
}
.composer-fold-wrap { display: flex; flex-direction: column; gap: 6px; }
.pill-fold {
  align-self: flex-start; padding: 0; border: none; background: none;
  font: inherit; font-size: 12px; color: var(--mi-text-dim); cursor: pointer;
}
.pill-fold:hover { color: var(--mi-primary); }
.plaza { display: flex; flex-direction: column; gap: 12px; }
.sec-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
h2 { margin: 0; font-size: 16px; font-weight: 700; color: var(--mi-text); }
.tabs { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 10px 14px; }
.source { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 10px 14px; }
.tab-right { display: flex; align-items: center; gap: 12px; }
/* 改版后 .row 落在卡片本身（见模板那三处 mi-wall）。scroll-margin 留着：
       顶栏现在是 sticky 的两层头，锚点跳转不留出净距离会被它盖住。 */
.row { scroll-margin-top: 108px; }
.sentinel { display: flex; justify-content: center; align-items: center; min-height: 44px; }
.empty { padding: 18px; }
.hint { font-size: 12px; color: var(--mi-text-dim); }
.formula { font-size: 12px; color: var(--mi-text-dim); font-family: Consolas, monospace; margin: 0 0 8px; }
/* 推荐流那一节（T7.9/T7.10）。卡片本体沿用 PostCard，这里只管「多出来的那一行」与其容器。
   2026-09-30 改版两条：
   ① 容器 .mi-card 的白底内衬去掉，让推荐卡直接坐在页面灰底上 —— 白卡里再嵌一排白卡，
      浅色下两层都看不见边，只剩一片糊。需求 D6 要的「与广场同一形状」正好因此更成立。
   ② 原来给 .rec-card 加的蓝灰边框/内边距整条删除：它和 PostCard 自己的 .mi-card 打架，
      会把刚做好的笔记卡压回成一行行列表。 */
.recommend { background: transparent; border: none; box-shadow: none; padding: 2px 0 0; }
.rec-why { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin: 8px 0 0; padding-top: 8px; border-top: 1px dashed var(--mi-border); }
.rec-reason { font-size: 12px; line-height: 1.7; color: var(--mi-mist); }
.btn-rec-why { flex: none; }
.rec-foot { display: flex; justify-content: center; align-items: center; min-height: 40px; margin-top: 10px; }
.rec-foot .hint { text-align: center; line-height: 1.7; }
.why-pop { font-size: 12px; line-height: 1.8; }
.why-pop p { margin: 4px 0; }
.why-pop code { font-family: Consolas, monospace; color: var(--mi-primary); }
.why-pop .hint { color: var(--mi-text-dim); }
.topics { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
.topic { border: 1px solid var(--mi-hairline); border-radius: 12px; padding: 12px 14px; background: var(--mi-mist-bg); }
.t-name { font-weight: 700; color: var(--mi-primary); }
.t-desc { font-size: 12px; color: var(--mi-text-dim); margin: 6px 0; min-height: 32px; }
.t-meta { display: flex; gap: 12px; font-size: 12px; color: var(--mi-mist); }
.sec-ops { display: flex; align-items: center; gap: 4px; }
.topic-link { cursor: pointer; }
.topic-link:hover { border-color: var(--mi-primary-line); background: var(--mi-primary-soft); box-shadow: var(--mi-shadow-sm); }
.t-go { margin-left: auto; font-size: 12px; color: var(--mi-mist); }
.dlg-line { margin: 10px 0 0; line-height: 1.8; }
.dlg-body { margin: 4px 0 0; font-size: 13px; line-height: 1.7; }
.dlg-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.dlg-btns { display: flex; gap: 6px; }
.lines { margin: 0; padding-left: 20px; line-height: 2; font-size: 13px; }
.todo h2 { margin-bottom: 8px; }
</style>
