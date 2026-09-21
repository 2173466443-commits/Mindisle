<template>
  <div class="feed">
    <!-- 1 U5 发布器（紧凑形态，与 /publish 同一组件） -->
    <section class="mi-card">
      <post-composer compact :topic-list="topicList" @published="onPublished" />
    </section>

    <!-- 2 U3 广场：游标翻页的信息流 -->
    <section class="plaza">
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

    <!-- 3 推荐流：后端仍是 90001，占位说明保留（阶段 6/7 才接真逻辑） -->
    <section class="mi-card">
      <div class="sec-head">
        <h2>为你推荐</h2>
        <el-button size="small" text :loading="busy.recommend" @click="loadRecommend">刷新</el-button>
      </div>
      <p class="formula">排序目标 emotion_match(u, i) = 1 − | valence_now(u) − comfort_valence(i) |，仅在当前心情为负向时启用（需求 §1.5 创新点 2）。</p>
      <stage-notice v-if="codes.recommend" :code="codes.recommend" stage="4" api-name="GET /api/feed/recommend" />
      <el-empty v-else-if="!recList.length" description="暂无推荐结果（召回与加权分别排在阶段 3 / 阶段 4）" />
      <ul v-else class="lines">
        <li v-for="(r, i) in recList" :key="i">{{ r.title || r.name || JSON.stringify(r) }}</li>
      </ul>
    </section>

    <!-- 4 官方话题墙 -->
    <section class="mi-card">
      <div class="sec-head">
        <h2>官方话题墙</h2>
        <el-button size="small" text :loading="busy.topics" @click="loadTopics">重新加载</el-button>
      </div>
      <stage-notice v-if="codes.topics" :code="codes.topics" stage="2" api-name="GET /api/topics" />
      <el-empty v-else-if="!topicList.length && !busy.topics" description="暂无已过审话题" />
      <div v-else class="topics">
        <div v-for="t in topicList" :key="t.id" class="topic">
          <div class="t-name"># {{ t.name }}</div>
          <div class="t-desc">{{ t.desc || '官方话题' }}</div>
          <div class="t-meta">
            <span>发帖 {{ t.postCnt || 0 }}</span>
            <span>关注 {{ t.followCnt || 0 }}</span>
            <span>热度 {{ fmtHot(t.hotScore) }}</span>
          </div>
        </div>
      </div>
      <p class="hint">数据来自 topic 表（audit_status=APPROVED，按 hot_score 倒序）。看不到内容多半是数据库还没建。</p>
    </section>

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
import { ElMessage } from 'element-plus'
import { topics, recommend } from '@/api/feed'
import { NOT_IMPLEMENTED_YET } from '@/api/auth'
import { useFeedStore } from '@/stores/feed'
import { useUserStore } from '@/stores/user'
import { CODE } from '@/api/errorCode'
import PostCard from '@/components/PostCard.vue'
import PostComposer from '@/components/PostComposer.vue'
import StageNotice from '@/components/StageNotice.vue'

const TABS = [
  { value: '', label: '全部' },
  { value: 'hole', label: '树洞' },
  { value: 'help', label: '求助' },
  { value: 'normal', label: '分享' }
]

const feed = useFeedStore()
const user = useUserStore()
const topicList = ref([])
const recList = ref([])
const pending = NOT_IMPLEMENTED_YET
const activeType = ref('')
const busy = reactive({ topics: false, recommend: false })
const codes = reactive({ topics: null, recommend: null })
const sentinel = ref(null)
let observer = null

const totalLine = computed(() => (feed.total >= 0 ? '广场共 ' + feed.total + ' 条可见内容' : '已加载 ' + feed.items.length + ' 条'))
const listExtra = computed(() => {
  if (Number(feed.errorCode) === CODE.DB_UNAVAILABLE) {
    return '数据库暂不可用：帖子列表要读 post 表，后端已按「库挂了也让页面活着」的口径降级，这里是空态而不是白屏。'
  }
  return ''
})

function fmtHot(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(1) : '-'
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

onMounted(async () => {
  loadTopics()
  loadRecommend()
  if (user.isLogged) {
    await reload()
    await nextTick()
    bindObserver()
  }
})
onUnmounted(() => {
  if (observer) observer.disconnect()
})
</script>

<style scoped>
.feed { display: flex; flex-direction: column; gap: 18px; }
.plaza { display: flex; flex-direction: column; gap: 12px; }
.sec-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
h2 { margin: 0; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
.tabs { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 18px; }
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
.lines { margin: 0; padding-left: 20px; line-height: 2; font-size: 13px; }
.todo h2 { margin-bottom: 8px; }
</style>
