<template>
  <div class="page">
    <div class="topbar">
      <h1 class="h1">{{ title }}</h1>
      <div class="ops">
        <el-button size="small" @click="goFeed">回广场</el-button>
        <el-button size="small" text :loading="cardBusy || loading" @click="refresh">刷新</el-button>
      </div>
    </div>

    <!-- U6 话题头（手册 §6.2 U6 · 需求 FR4.5「话题聚合页」）。三个计数直接取自接口，前端不做任何加一减一的估算；
         关注按钮是唯一会本地先改数字的地方，理由写在 actFollow 的注释里。 -->
    <section v-if="card" class="mi-card hero">
      <div class="cover" :class="{ 'cover-img': hasCover }" :style="coverStyle"><span v-if="!hasCover" class="hash">#</span></div>
      <div class="hero-main">
        <div class="t-name"># {{ card.name }}</div>
        <p class="t-desc">{{ card.desc || '这个话题还没有简介' }}</p>
        <div class="t-stats">
          <span>发帖 {{ fmtCount(card.postCnt) }}</span>
          <span>关注 {{ fmtCount(card.followCnt) }}</span>
          <span>热度 {{ fmtHot(card.hotScore) }}</span>
          <el-tag v-if="card.isOfficial" size="small" effect="plain">官方</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">屿友创建</el-tag>
        </div>
      </div>
      <div class="hero-op">
        <el-button class="follow-btn" :type="card.following ? '' : 'primary'" :loading="followBusy" @click="actFollow">
          {{ card.following ? '已关注' : '关注' }}
        </el-button>
        <el-button class="pub-btn" size="small" text @click="goPublish">发帖到该话题</el-button>
      </div>
    </section>

    <!-- 头拿不到时不画那颗「关注」按钮：宁可少一块卡片，也不留一颗点了没反应的按钮（与屿友主页同一取舍）。
         30004 与 90006 是两件不同的事，措辞也分开：前者是「等」，后者是「这个地址本来就没有」。 -->
    <stage-notice v-else-if="cardError" :code="cardError" :stage="stage" api-name="GET /api/topics/{id}"
                  :extra="cardExtra" />
    <p v-else-if="cardNote" class="note">{{ cardNote }}</p>

    <!-- 话题下的帖子流（需求 FR4.5「话题页展示热帖」）。排序档位由后端定：只认 latest / top，
         这里不敢提前摆一个「热度」出来，因为 normalizeSortFilter 会把它判成 10001。 -->
    <section v-if="card" class="mi-card flow">
      <div class="tabs">
        <el-radio-group v-model="sort" size="small" @change="onSortChange">
          <el-radio-button v-for="s in TOPIC_SORTS" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
        </el-radio-group>
        <span class="hint">{{ totalLine }}</span>
      </div>

      <stage-notice v-if="errorCode" :code="errorCode" :stage="stage" api-name="GET /api/topics/{id}/posts"
                    :extra="listExtra" />
      <template v-else>
        <p v-if="!items.length && !loading" class="hint empty">{{ emptyLine }}</p>
        <div v-else class="list mi-wall">
          <post-card v-for="item in items" :key="item.id" :item="item" :dismissable="false" />
        </div>
        <div class="more">
          <el-button v-if="hasMore && items.length" size="small" :loading="loading" @click="loadMore">加载更多</el-button>
          <span v-else-if="items.length" class="dim">到这里就是全部了（已加载 {{ items.length }} 条）</span>
        </div>
      </template>
      <p class="note">{{ sortNote }}</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { topicDetail, topicPosts, followTopic, TOPIC_SORTS } from '@/api/topic'
import { fmtCount, fmtHot } from '@/utils/format'
import { CODE } from '@/api/errorCode'
import { usePagedPosts } from '@/composables/usePagedPosts'
import PostCard from '@/components/PostCard.vue'
import StageNotice from '@/components/StageNotice.vue'

const route = useRoute()
const router = useRouter()

// 与后端 TopicController 那条路径变量的约束同一形状（那边限数字，这里再要求不带前导 0）：
// 地址栏打错时先由前端说一句人话，而不是把一个 abc 发出去换回 404 再让人猜为什么是空白页。
const topicId = computed(() => {
  const raw = String(route.params.id || '')
  return /^[1-9][0-9]*$/.test(raw) ? raw : ''
})

const card = ref(null)
const cardBusy = ref(false)
const cardError = ref(null)
const cardNote = ref('')
const followBusy = ref(false)
const sort = ref('latest')
const stage = '3'

const title = computed(() => (card.value ? '# ' + card.value.name : topicId.value ? '话题 ' + topicId.value : '话题不存在'))
const hasCover = computed(() => !!(card.value && card.value.cover))
// cover 这一列在 DDL 里有，但阶段 3 没有给话题上传头图的通道（管理端属 T6.1，用户侧也没开这个口），
// 所以它今天恒为 null，界面按渐变占位画。写法留在这里：一旦将来有图，这一行就自动生效，不必再改组件。
const coverStyle = computed(() => (hasCover.value
  ? { backgroundImage: 'url("' + card.value.cover + '")', backgroundSize: 'cover', backgroundPosition: 'center' }
  : null))

const { items, loading, hasMore, total, errorCode, reload, loadMore, clear } = usePagedPosts(
  (params) => topicPosts(topicId.value, params),
  { query: () => (sort.value === 'latest' ? {} : { sort: sort.value }) }
)

const totalLine = computed(() => (total.value >= 0
  ? '这个话题下共 ' + total.value + ' 条可见帖子'
  : '已加载 ' + items.value.length + ' 条'))

// 空态要说清「为什么是空的」，其中最容易被当成 bug 的是「帖子存在但都不可见」：
// topic.post_cnt 统计的是全部关联行，读侧还要再过一遍可见性判据，所以头图上写「发帖 23」、
// 列表却是空的，这是两件事各自都对。这句话必须出现在界面上，否则用户只会认为翻页坏了。
const emptyLine = computed(() => {
  const cnt = card.value ? Number(card.value.postCnt) || 0 : 0
  if (!cnt) return '这个话题还没有人发帖。想到什么了？点上面的「发帖到该话题」开个头。'
  return '这里暂时没有能看的帖子：头图上的「发帖 ' + cnt + '」数的是这个话题关联的全部帖子，'
    + '而列表还要再过一遍与广场同一套可见性判据（已过审、公开、未被删除、未到期销毁），'
    + '所以「有计数、没内容」是两件事各自都对的正常结果，不是翻页坏了。'
})

const sortNote = computed(() => (sort.value === 'top'
  ? '「热帖」这一档今天做到的只有「置顶帖排在前面」：post.is_top 只能由管理员写，而管理端排在任务 6.1，'
    + '库里这一列恒为 0 —— 也就是说这一档和「最新」今天给出的顺序是一样的。真正按 hot_score 的热度序属阶段 4。'
    + '把档位先做成这样，是为了让接口和界面的形状先定下来，不必为将来加一档排序再改一次调用点。'
  : '按发布时间倒序，与广场、屿友主页同一条游标翻页口径（首屏给总数，往后只看 nextCursor）。')
)

const cardExtra = computed(() => {
  const code = Number(cardError.value)
  if (code === CODE.TOPIC_PENDING) {
    return '这个话题还在审核中，所以既进不去、也挂不上帖子。要说清的边界：需求 FR8.6 那个「话题预审开关」默认是开的，'
      + '而阶段 3 还没有审核台能把待审话题放行（管理端属任务 6.1，audit_task 的 target_type 里今天还没有 topic 这一档）。'
      + '所以自己新建的话题停在待审是默认配置下的正常结局，不是这次创建失败了 —— 回执里那句「已提交」是真的。'
  }
  if (code === CODE.RESOURCE_NOT_FOUND) {
    return '这个话题不存在，或者已经被删除、或者审核没通过 —— 后端对这三种情况给的是同一个 404，不区分「存在但被拒」。'
  }
  if (code === CODE.DB_UNAVAILABLE) {
    return '数据库暂不可用：话题资料要读 topic 表，后端按「库挂了也让页面活着」的口径降级，这里是空态而不是白屏。'
  }
  return ''
})

const listExtra = computed(() => (Number(errorCode.value) === CODE.DB_UNAVAILABLE
  ? '数据库暂不可用：话题下的帖子流要读 post 与 post_topic 两张表，后端已降级成空列表而不是白屏。'
  : ''))

async function loadCard() {
  cardBusy.value = true
  card.value = null
  cardError.value = null
  cardNote.value = ''
  if (!topicId.value) { cardBusy.value = false; return }
  try {
    card.value = await topicDetail(topicId.value)
  } catch (e) {
    const code = e && e.code !== undefined && e.code !== null ? e.code : 'network'
    // 待审与不存在是「这个话题的页面本来就该这样」，整页交给 StageNotice 说；
    // 其余（网络、库挂了）留一句降级文字，帖子列表该继续显示自己的状态。
    if (Number(code) === CODE.TOPIC_PENDING || Number(code) === CODE.RESOURCE_NOT_FOUND) cardError.value = code
    else cardNote.value = '话题资料暂时取不到（' + code + '），帖子列表照常显示。'
  }
  cardBusy.value = false
}

/** 关注 / 取关：与点赞同一套「先改界面、以回执覆盖、失败回滚」。回执里的 followCnt 是后端重算的真相，
 * 本地那次加减只是让按钮点下去立刻有反应，绝不当最终值用。 */
async function actFollow() {
  if (!card.value || followBusy.value) return
  const snapshot = { following: !!card.value.following, followCnt: Number(card.value.followCnt) || 0 }
  const next = !snapshot.following
  card.value.following = next
  card.value.followCnt = Math.max(0, snapshot.followCnt + (next ? 1 : -1))
  followBusy.value = true
  try {
    const view = await followTopic(topicId.value, next ? 'follow' : 'unfollow')
    if (view && typeof view === 'object') {
      card.value.following = !!view.following
      card.value.followCnt = Number(view.followCnt) || 0
    }
  } catch (e) {
    card.value.following = snapshot.following
    card.value.followCnt = snapshot.followCnt
  } finally {
    followBusy.value = false
  }
}

function onSortChange() {
  reload()
}

function refresh() {
  loadCard()
  reload()
}

// 带 topic 参数去发布页：那边会把这个话题预填进「关联话题」，用户不必再找一遍。
// 话题 id 走 query 而不是 params，是为了让 /publish 那张卡片自己决定怎么消化它（草稿恢复之后才 merge）。
function goPublish() {
  if (!topicId.value) return
  router.push({ name: 'publish', query: { topic: topicId.value } })
}

function goFeed() {
  router.push({ name: 'feed' })
}

// 从话题 A 直接改地址栏进话题 B 时 Vue 复用同一个组件实例，不重新 mounted。
// 不 watch 就会「在 A 的话题页看到 B 的名字配 A 的帖子」——头图和列表分家，是最难被自己发现的那类错。
watch(topicId, (to) => {
  if (!to) {
    // 「有效 → 无效」这一支也必须收尾：/topic/1 改成 /topic/abc 时若只认 to 为真那一支，
    // 屏幕上就是「地址栏已经 abc、头图却还是失眠夜」，那颗关注按钮点下去用的还是空编号 —— 比白屏更坏。
    // 判据不能省：从本来就取不到资料的话题（待审、不存在）跳来 abc 抓不到这个症状，必须从「有卡片」那一边切过来。
    card.value = null
    cardNote.value = ''
    cardError.value = CODE.RESOURCE_NOT_FOUND
    clear()
    return
  }
  reload()
  loadCard()
})

onMounted(() => {
  if (topicId.value) {
    reload()
    loadCard()
  } else {
    cardError.value = CODE.RESOURCE_NOT_FOUND
  }
})
</script>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; display: flex; flex-direction: column; gap: 14px; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h1 { margin: 0; font-size: 22px; }
.ops { display: flex; gap: 4px; flex-shrink: 0; }
.hero { display: flex; align-items: flex-start; gap: 14px; }
.cover { width: 84px; height: 84px; border-radius: 14px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #e8eff7, #fdeede); }
.cover-img { background-color: transparent; }
.hash { font-size: 34px; font-weight: 700; color: var(--mi-primary); opacity: 0.75; }
.hero-main { flex: 1 1 auto; min-width: 0; }
.t-name { font-size: 17px; font-weight: 700; color: var(--mi-primary); word-break: break-word; }
.t-desc { margin: 6px 0 8px; font-size: 13px; line-height: 1.8; color: var(--mi-text-dim); word-break: break-word; }
.t-stats { display: flex; gap: 14px; flex-wrap: wrap; align-items: center; font-size: 12px; color: var(--mi-text-dim); }
.hero-op { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; flex-shrink: 0; }
.flow { padding: 14px 18px; }
.tabs { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.list { margin-top: 14px; }
.more { display: flex; justify-content: center; align-items: center; min-height: 44px; }
.hint { font-size: 12px; color: var(--mi-text-dim); }
.empty { padding: 18px 0; line-height: 1.9; }
.dim { font-size: 12px; color: var(--mi-text-dim); }
.note { margin: 12px 0 0; font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); }
</style>
