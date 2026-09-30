<template>
  <div class="page">
    <div class="topbar">
      <div>
        <h1 class="h1">站内搜索</h1>
        <p class="dim">
          搜索框按「帖子 / 话题 / 屿友」三条路各查一次（需求 FR4.8）。一次只看一张结果表，
          换标签不换关键词。
        </p>
      </div>
      <div class="ops">
        <el-button size="small" text @click="goFeed">回广场</el-button>
      </div>
    </div>

    <!-- 关键词输入。maxlength 与后端 max-keyword-chars(64) 同源：让界面先挡住，
         比提交之后拿一句 10001 回来好，但这条只是省一次往返 —— 后端那道闸不会因为这里限了就不装。 -->
    <section class="mi-card blk">
      <!-- 结果类别用单选按钮组而不是输入框前缀里的下拉：全站另外三处「换一张列表」
           （广场的类型 Tab、来源切换、我的帖子的状态 Tab）用的都是同一个控件，
           这里换成下拉就成了「同一个动作两种长相」。它排在输入框上方也是这个理由 ——
           类别决定这次搜索去哪张表，比关键词更先决。 -->
      <div class="modes">
        <el-radio-group v-model="mode" size="small" @change="switchMode">
          <el-radio-button v-for="m in SEARCH_MODES" :key="m.value" :value="m.value">{{ m.label }}</el-radio-button>
        </el-radio-group>
        <span class="dim">{{ modeNote }}</span>
      </div>
      <el-input v-model="keyword" :maxlength="SEARCH_KEYWORD_MAX" clearable placeholder="想搜什么？试试「失眠」「秋招」「焦虑」"
                @keyup.enter="submit" @clear="onClear">
        <template #append>
          <el-button :loading="busy" @click="submit">搜索</el-button>
        </template>
      </el-input>

      <!-- 类型过滤只对帖子有效：另外两条路径的入参里根本没有 type，后端不接受的条件不在这里假装支持 -->
      <div v-if="mode === 'post'" class="filters">
        <el-radio-group v-model="postType" size="small" @change="switchType">
          <el-radio-button v-for="t in TYPE_TABS" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
        </el-radio-group>
        <span class="dim">{{ resultLine }}</span>
      </div>

    </section>

    <!-- 一条始终在场的说明：搜索这件事的边界必须写在结果旁边，而不是等人来问「为什么搜不到」 -->
    <p class="dim scope">
      本机的检索口径：命中标题 / 正文 / 话题名的 LIKE 匹配，按发布时间倒序。
      <b>没有相关度排序</b>（需求 FR4.8 要的那半条属阶段 4）、
      <b>全文索引通道默认关闭</b>（要先执行 sql/10_index.sql，任务 2.2 尚为 ◐）。
      所以：搜不到不等于内容不存在 —— 未过审、被下架、设为仅自己可见、以及只出现在图片里的文字，都不在这条流里。
    </p>

    <!-- 帖子结果：与广场同一条 PostListItem，所以卡片也是同一张（含匿名马甲名、求助热线、互动态） -->
    <template v-if="mode === 'post'">
      <el-alert v-if="!started" type="info" show-icon :closable="false" class="blk"
                title="输入关键词后按回车开搜"
                description="空关键词后端直接回 10001，所以这里不发请求，也不预填一份「大家都搜了什么」——热搜要等任务 3.10 的埋点落库才有数据。" />
      <stage-notice v-else-if="errorCode" :code="errorCode" :stage="stage" :api-name="apiName" :extra="listExtra" />
      <template v-else>
        <div v-loading="loading && items.length === 0" class="list mi-wall">
          <el-empty v-if="!loading && items.length === 0" :image-size="60"
                    description="没有匹配的帖子。换少一点的词试试：这里做的是包含匹配，不是分词，「失眠很难受」搜不到只写了「失眠」的那条。" />
          <post-card v-for="item in items" :key="item.id" :item="item" :dismissable="false" />
        </div>
        <div class="more">
          <el-button v-if="hasMore && started && items.length" size="small" :loading="loading" @click="loadMore">
            加载更多
          </el-button>
          <span v-else-if="items.length" class="dim">到这里就是全部了（已加载 {{ items.length }} 条）</span>
        </div>
      </template>
    </template>

    <!-- 话题结果：定长数组，没有翻页 -->
    <template v-else-if="mode === 'topic'">
      <stage-notice v-if="profileError" :code="profileError" :stage="stage" :api-name="apiName" :extra="listExtra" />
      <el-empty v-else-if="!started" description="输入关键词后按回车开搜" :image-size="60" />
      <el-empty v-else-if="!topics.length && !busy" description="没有匹配的已过审话题" :image-size="60" />
      <div v-else class="topics">
        <!-- 话题卡可点进详情页（任务 T3.8 · 需求 FR4.5）。整张卡都是热区而不是名字那一个小链接：
             用户在搜索结果里点的从来不是某个字段，是「这个话题」。 -->
        <div v-for="t in topics" :key="t.id" class="topic topic-link" @click="goTopic(t)">
          <div class="t-name"># {{ t.name }}</div>
          <div class="t-desc">{{ t.desc || '这个话题没有简介' }}</div>
          <div class="t-meta">
            <span>发帖 {{ t.postCnt || 0 }}</span>
            <span>关注 {{ t.followCnt || 0 }}</span>
            <span>热度 {{ fmtHot(t.hotScore) }}</span>
            <el-tag v-if="t.isOfficial" size="small" effect="plain">官方</el-tag>
            <span class="t-go">进入话题 →</span>
          </div>
        </div>
      </div>
      <p v-if="started && topics.length" class="dim note">
        点任意一张话题卡进话题页（/topic/编号）：那里有这个话题下的帖子流、关注按钮和「发帖到该话题」。
        这一栏只列已过审的话题，按热度倒序、一次最多 {{ SEARCH_PROFILE_MAX }} 条；匹配的是话题名，不是简介里的字。
      </p>
    </template>

    <!-- 屿友结果：定长数组。这里不放「关注」按钮 —— UserHit 只有 id/昵称/头像三个字段，
         没有 following 状态。要在列表里画出那颗按钮，就得为每一条再各发一次 /users/{id}/profile：
         一屏 20 条就是 20 次请求，而且把「主页资料卡」这条公开口径的限流也一起放大了。
         所以按钮留在主页那一页，这里只给一个入口。 -->
    <template v-else>
      <stage-notice v-if="profileError" :code="profileError" :stage="stage" :api-name="apiName" :extra="listExtra" />
      <el-empty v-else-if="!started" description="输入关键词后按回车开搜" :image-size="60" />
      <el-empty v-else-if="!users.length && !busy" description="没有匹配的屿友" :image-size="60" />
      <ul v-else class="users">
        <li v-for="u in users" :key="u.id" class="user" @click="goUser(u)">
          <el-avatar :size="38" :src="u.avatar || ''">{{ initialOf(u.nickname) }}</el-avatar>
          <span class="u-name">{{ u.nickname }}</span>
          <span class="u-id">uid {{ u.id }}</span>
          <span class="u-go">查看主页 →</span>
        </li>
      </ul>
      <p v-if="started && users.length" class="dim note">
        搜人只匹配昵称与登录名，不匹配邮箱、院系、年级，也不返回角色与状态 —— 那是接口出参的白名单。
        匿名与马甲发帖人不可能通过这条路被反查出来（需求 FR1.4）。
      </p>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchPosts, searchTopics, searchUsers, SEARCH_KEYWORD_MAX, SEARCH_PROFILE_MAX, SEARCH_MODES } from '@/api/search'
import { POST_TYPES } from '@/api/post'
import { useUserStore } from '@/stores/user'
import { CODE } from '@/api/errorCode'
import { fmtHot } from '@/utils/format'
import { usePagedPosts } from '@/composables/usePagedPosts'
import PostCard from '@/components/PostCard.vue'
import StageNotice from '@/components/StageNotice.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const SEARCHABLE_TYPES = [{ value: '', label: '全部' }].concat(POST_TYPES)

/** 关键词：界面当前值，可能与地址栏里的 q 不一致（改了还没按回车）。 */
const keyword = ref('')
const mode = ref('post')
const postType = ref('')
const started = ref(false)
const topics = ref([])
const users = ref([])
const busy = ref(false)
const profileError = ref(null)

const apiName = computed(() => {
  const hit = SEARCH_MODES.find((m) => m.value === mode.value)
  return hit ? hit.apiName : 'GET /api/search'
})
// stage 只影响 90001 那句话的措辞。搜索是阶段 3 的东西，接口在、逻辑真跑，
// 拿不到 90001；写成 3 是为了「万一后端被换回桩」时这句话仍然说得出正确的排期。
const stage = '3'

const TYPE_TABS = SEARCHABLE_TYPES

// 每个类别一句「这次会怎么排、最多给多少」：同一句话在三张结果表上重复一遍是养分叉，
// 而把它塞进模板的三元里就会变成一整行没人读的字符串。
const modeNote = computed(() => {
  if (mode.value === 'post') return '命中标题 / 正文 / 话题名，按发布时间倒序（相关度排序未做，见下方口径说明）。'
  if (mode.value === 'topic') return '话题只给已过审的，按热度倒序，一次最多 ' + SEARCH_PROFILE_MAX + ' 条。'
  return '屿友只按昵称与登录名匹配，一次最多 ' + SEARCH_PROFILE_MAX + ' 条；出参只有 id / 昵称 / 头像三个字段。'
})

// 帖子那一栏复用广场同一个翻页引擎：后端三条列表（广场/我的/主页/搜索）共用 pageResult，
// 出参形状与游标语义完全相同，所以这里只换 fetcher，不重写一份「什么时候算翻到底」。
const { items, loading, hasMore, total, errorCode, reload, loadMore } = usePagedPosts(
  (params) => searchPosts(params),
  {
    // 发请求那一刻才取关键词与类型：把 ref 直接展开进对象会得到一份快照，
    // 后面改了筛选条件、翻页却还带着旧的 type。
    // 空 type 干脆不发：广场那份 store 就是这么做的（if (this.type) params.type = ...），
    // 后端 normalizeTypeFilter 对「缺键」和「空串」都会折成全部，但两条路径各测一遍才有意义。
    query: () => {
      const q = { q: keyword.value.trim() }
      if (postType.value) q.type = postType.value
      return q
    }
  }
)

const resultLine = computed(() => {
  if (!started.value) return ''
  if (total.value >= 0) return '共 ' + total.value + ' 条命中（第一屏已取回 ' + items.value.length + ' 条）'
  return '已加载 ' + items.value.length + ' 条命中'
})

// 三种「没结果」的原因要分开说，而且都不许只报一个码。
// 认证码不在这里处理成跳转：silent 的请求把 401 交回页面，是为了让「搜索页空态」和
// 「整站被踢去登录」这两件事由用户自己决定先后 —— 他可能只是想看一眼结果。
const listExtra = computed(() => {
  const code = Number(errorCode.value !== null ? errorCode.value : profileError.value)
  if (code === CODE.UNAUTHORIZED || code === CODE.TOKEN_EXPIRED || code === CODE.TOKEN_INVALID) {
    return '登录态不成立了：这三条搜索路径都要求登录（后端没给它们开白名单，是刻意的）。请重新登录后再搜。'
  }
  if (code === CODE.PARAM_INVALID) {
    return '关键词没被后端接受：为空或全是空白、或超过 64 个字，都会直接回这句，不做截断兜底。'
  }
  if (code === CODE.DB_UNAVAILABLE) {
    return '数据库暂不可用：搜索是全站最重的一条读路径，后端按「库挂了也让页面活着」的口径降级，这里是空态而不是白屏。'
  }
  return ''
})

function initialOf(name) {
  return name ? String(name).slice(0, 1) : '屿'
}

/** 话题 / 屿友两条定长结果。返回 false 表示这一路失败了，错误码已经存进 profileError。 */
async function loadProfiles() {
  busy.value = true
  profileError.value = null
  try {
    const params = { q: keyword.value.trim(), limit: SEARCH_PROFILE_MAX }
    if (mode.value === 'topic') topics.value = (await searchTopics(params)) || []
    else users.value = (await searchUsers(params)) || []
    return true
  } catch (e) {
    profileError.value = e && e.code !== undefined && e.code !== null ? e.code : 'network'
    if (mode.value === 'topic') topics.value = []
    else users.value = []
    return false
  } finally {
    busy.value = false
  }
}

async function run() {
  const kw = keyword.value.trim()
  if (!kw) {
    // 空关键词不发请求：后端那句 10001 是对的，但让界面亮一个错误码不等于告诉用户该做什么。
    started.value = false
    topics.value = []
    users.value = []
    profileError.value = null
    items.value = []
    return
  }
  started.value = true
  syncQuery()
  if (mode.value === 'post') await reload()
  else await loadProfiles()
}

function submit() {
  run()
}

function onClear() {
  started.value = false
  topics.value = []
  users.value = []
  profileError.value = null
  items.value = []
  // 清词之后必须把地址栏一起清掉：否则屏幕上是「还没搜」，地址栏却还是上一次那条查询，
  // 用户一刷新就凭空看到一批自己刚刚主动清掉的结果。syncQuery 对空词的做法就是只留 m。
  syncQuery()
}

function switchMode() {
  profileError.value = null
  if (started.value) run()
}

// 走 run() 而不是直接 reload()：run() 里那一步 syncQuery 才是这条路径的正文。
// 只调 reload() 的话筛选生效了、地址栏却没有 type=hole，「把当前这条搜索发给同学」
// 就会变成「把没筛选的那条发给同学」——这类不一致只有点一次再看地址栏才会发现。
function switchType() {
  if (started.value) run()
}

/** 把当前查询写进地址栏：搜索页要能刷新还在、能后退回去、能把链接发给别人。 */
function syncQuery() {
  const query = { m: mode.value }
  if (keyword.value.trim()) query.q = keyword.value.trim()
  if (mode.value === 'post' && postType.value) query.type = postType.value
  router.replace({ name: 'search', query }).catch(() => {})
}

function goUser(u) {
  if (!u || u.id === undefined || u.id === null) return
  router.push({ name: 'user-home', params: { id: u.id } })
}

// 话题结果的落点（任务 T3.8）。与点屿友结果是同一个形状：搜索页自己不做「话题资料卡」的复制品，
// 一律交给 /topic/:id 那一页 —— 两处各画一份头图，迟早一处显示官方角标一处不显示。
function goTopic(t) {
  if (!t || t.id === undefined || t.id === null) return
  router.push({ name: 'topic-detail', params: { id: t.id } })
}

function goFeed() {
  router.push({ name: 'feed' })
}

onMounted(() => {
  if (!userStore.isLogged) return
  const q = typeof route.query.q === 'string' ? route.query.q : ''
  const m = typeof route.query.m === 'string' && SEARCH_MODES.some((x) => x.value === route.query.m)
    ? route.query.m : 'post'
  const t = typeof route.query.type === 'string' && SEARCHABLE_TYPES.some((x) => x.value === route.query.type && x.value !== '')
    ? route.query.type : ''
  mode.value = m
  postType.value = t
  if (q) {
    keyword.value = q
    run()
  }
})
</script>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h1 { margin: 0; font-size: 22px; }
.ops { display: flex; gap: 4px; flex-shrink: 0; }
.blk { margin-top: 14px; }
.dim { font-size: 12px; color: var(--mi-text-dim); line-height: 1.8; }
.filters { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-top: 12px; }
.modes { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.filters-note { margin: 10px 0 0; }
.scope { margin: 12px 2px 0; }
/* mi-wall 负责铺卡片（见 theme.css）；这里只留它自己的上间距。 */
.list { margin-top: 14px; }
.more { display: flex; justify-content: center; align-items: center; min-height: 44px; }
.topics { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; margin-top: 14px; }
.topic { border: 1px solid var(--mi-hairline); border-radius: 12px; padding: 12px 14px; background: var(--mi-card); box-shadow: var(--mi-shadow-sm); }
.topic-link { cursor: pointer; }
.topic-link:hover { border-color: var(--mi-primary-line); box-shadow: var(--mi-shadow); }
.t-go { margin-left: auto; font-size: 12px; color: var(--mi-mist); }
.t-name { font-weight: 700; color: var(--mi-primary); }
.t-desc { font-size: 12px; color: var(--mi-text-dim); margin: 6px 0; min-height: 32px; }
.t-meta { display: flex; gap: 10px; align-items: center; font-size: 12px; color: var(--mi-mist); flex-wrap: wrap; }
.users { list-style: none; margin: 14px 0 0; padding: 0; }
.user { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border: 1px solid var(--mi-hairline); border-radius: 12px; background: var(--mi-card); box-shadow: var(--mi-shadow-sm); cursor: pointer; margin-bottom: 8px; }
.user:hover { border-color: var(--mi-primary-line); box-shadow: var(--mi-shadow); }
.u-name { font-size: 14px; font-weight: 700; color: var(--mi-text); }
.u-id { font-size: 12px; color: var(--mi-text-dim); flex: 1; }
.u-go { font-size: 12px; color: var(--mi-mist); }
.note { margin: 10px 2px 0; }
</style>