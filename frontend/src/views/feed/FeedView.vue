
<template>
  <div class="feed">
    <!-- 1 树洞发帖：后端 POST /api/posts 在阶段 3 落地（先审后发 + 危机分流），
         阶段 2 把 90001 直接显示出来，不放假数据（手册 §5.8 第 1 条）。 -->
    <section class="mi-card">
      <div class="sec-head">
        <h2>此刻想说点什么</h2>
        <el-tag size="small" type="info">树洞默认匿名</el-tag>
      </div>
      <el-input v-model="draft.content" type="textarea" :rows="3" maxlength="2000" show-word-limit
                placeholder="这里可以先说说话。若出现自伤或伤人的表达，系统会先给你 12356 等求助入口。" />
      <div class="cmp-row">
        <el-select v-model="draft.topicId" placeholder="选择话题（可不选）" clearable class="w240">
          <el-option v-for="t in topicList" :key="t.id" :label="'# ' + t.name" :value="t.id" />
        </el-select>
        <el-select v-model="draft.mood" placeholder="当前心情" class="w140">
          <el-option v-for="m in MOODS" :key="m.value" :label="m.label" :value="m.value" />
        </el-select>
        <el-button type="primary" :loading="busy.publish" @click="doPublish">发布</el-button>
        <span class="hint">心情字段供阶段 4 情绪感知加权使用，阶段 2 不落库</span>
      </div>
      <stage-notice :code="codes.publish" stage="3" api-name="POST /api/posts"
                    extra="发帖牵涉内容安全链（敏感词预审 → 危机识别 → 人工复审）与图片上传，阶段 3 才接通；现在点发布拿到的是后端 90001，不是接口故障。" />
    </section>

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
      <p class="hint">数据来自 topic 表（audit_status=PASS 且 is_official=1，按 hot_score 倒序）。看不到内容多半是数据库还没建。</p>
    </section>

    <section class="mi-card">
      <div class="sec-head">
        <h2>屿友动态</h2>
        <el-button size="small" text :loading="busy.posts" @click="loadPosts">刷新</el-button>
      </div>
      <stage-notice v-if="codes.posts" :code="codes.posts" stage="3" api-name="GET /api/posts" />
      <el-empty v-else description="还没有动态" />
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
import { onMounted, reactive, ref } from 'vue'
import { topics, posts, publish, recommend } from '@/api/feed'
import { NOT_IMPLEMENTED_YET } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import StageNotice from '@/components/StageNotice.vue'

const MOODS = [
  { value: 'joy', label: '开心' },
  { value: 'trust', label: '安心' },
  { value: 'neutral', label: '平静' },
  { value: 'sadness', label: '难过' },
  { value: 'fear', label: '害怕' },
  { value: 'anger', label: '烦躁' },
  { value: 'disgust', label: '反感' }
]

const user = useUserStore()
const topicList = ref([])
const recList = ref([])
const pending = NOT_IMPLEMENTED_YET
const draft = reactive({ content: '', topicId: null, mood: 'neutral' })
const busy = reactive({ topics: false, posts: false, publish: false, recommend: false })
// 每个动作各自记一个后端码：null 表示这次调用成功（不弹提示条）
const codes = reactive({ topics: null, posts: null, publish: null, recommend: null })

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

async function loadPosts() {
  busy.posts = true
  codes.posts = null
  try {
    const data = await posts({ page: 1, size: 20 })
    recList.value = Array.isArray(data && data.records) ? data.records : []
  } catch (e) {
    codes.posts = e.code || 'network'
  } finally {
    busy.posts = false
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

async function doPublish() {
  if (!draft.content.trim()) return
  busy.publish = true
  codes.publish = null
  try {
    await publish({ content: draft.content, topicId: draft.topicId, mood: draft.mood, anonymous: true })
    draft.content = ''
  } catch (e) {
    codes.publish = e.code || 'network'
  } finally {
    busy.publish = false
  }
}

onMounted(() => {
  loadTopics()
  if (user.isLogged) loadPosts()
})
</script>

<style scoped>
.feed { display: flex; flex-direction: column; gap: 18px; }
.sec-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
h2 { margin: 0; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
.cmp-row { display: flex; align-items: center; gap: 12px; margin-top: 12px; flex-wrap: wrap; }
.w240 { width: 240px; }
.w140 { width: 140px; }
.hint { font-size: 12px; color: var(--mi-text-dim); }
.formula { font-size: 12px; color: var(--mi-text-dim); font-family: Consolas, monospace; margin: 0 0 8px; }
.topics { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
.topic { border: 1px solid var(--mi-border); border-radius: 10px; padding: 12px 14px; background: rgba(127, 167, 196, 0.06); }
.t-name { font-weight: 700; color: var(--mi-primary); }
.t-desc { font-size: 12px; color: var(--mi-text-dim); margin: 6px 0; min-height: 32px; }
.t-meta { display: flex; gap: 12px; font-size: 12px; color: var(--mi-mist); }
.lines { margin: 0; padding-left: 20px; line-height: 2; font-size: 13px; }
</style>
