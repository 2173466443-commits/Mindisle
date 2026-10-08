<!--
  U1 首页 / 着陆页（需求分析文档 §6 视图清单 U1：「品牌介绍、情绪指数氛围、进入社区/立即倾诉」）。
  ----------------------------------------------------------------------------
  这是全站唯一对游客开放的一屏。形态照小红书 explore 借：浅灰底 + 白卡 + 双列笔记墙，
  但内容口径不照它抄——心屿是心理陪伴社区，公开页只能放「已过审的公开笔记」和「脱敏聚合数」。

  【为什么这一页值得存在】改版之前 '/' 是 { path: '/', redirect: '/feed' }，
  于是没登录的人打开根路径直接被弹到登录页：品牌介绍、情绪氛围、求助入口一屏都看不见，
  「先看看这里是不是安全的地方」这件对新用户最要紧的事没有载体。U1 就是补这一屏。

  【三条写死在这里的纪律】
  ① 数据只走 GET /api/system/community-pulse（免登录）。卡片本体由后端 feedCards(0L, …) 组装，
     可见性判据与广场同一条，前端不做任何「游客能不能看」的二次判断——二次判断等于第二条判据。
  ② 数字为 null 就显示「—」，不显示 0。「今天还没人发帖」和「暂时取不到数」是两句话，
     后者由 degraded 标记如实说出来。
  ③ 外层类名一律 land-* 前缀，不复用取证契约类名（.feed / .row / .mi-nav 等）。
     PostCard 自己那套 article.post / .title / .excerpt 是组件的对外契约，照用；
     但登录态探针（domprobe 推 '/' 那条）依赖的是「已登录访问 / 就是广场」，
     所以 router 里那条 home→feed 的重定向守卫必须和本页一起改，两处不能拆开。
-->
<template>
  <div class="land">
    <header class="land-top">
      <span class="land-brand">
        <span class="land-logo">🏝</span>
        <span class="land-name">心屿</span>
        <span class="land-slogan">AI 心理陪伴社区</span>
      </span>
      <span class="land-top-actions">
        <router-link class="land-link" to="/help">求助热线</router-link>
        <el-button size="small" text @click="go('login')">登录</el-button>
        <el-button size="small" type="primary" @click="go('register')">免费注册</el-button>
      </span>
    </header>

    <!-- 品牌区：一句话主张 + 两个出口。CTA 的措辞直接抄 U1 原文「进入社区 / 立即倾诉」。 -->
    <section class="land-hero">
      <h1 class="land-h1">把说不清的情绪，<br />放到一座有人接住的岛上。</h1>
      <p class="land-sub">
        心屿是面向高校学生的 AI 心理陪伴社区：先 AI 倾听、再同伴回应。
        所有内容由机器与人工双重审核，匿名发帖不留身份痕迹，危机内容 5 分钟内转介。
      </p>
      <div class="land-cta">
        <el-button type="primary" size="large" @click="go('feed')">进入社区</el-button>
        <el-button size="large" @click="goConsentAi">立即倾诉</el-button>
        <router-link class="land-hot" to="/help">
          <span class="land-hot-dot"></span>心理援助热线 12356，24 小时有人接
        </router-link>
      </div>

      <!-- 情绪氛围：两个最关键的聚合数放在主张旁边，落地页第一眼就给「这里有人在好好说话」的证据。 -->
      <div class="land-mood">
        <div v-if="loading" class="land-mood-line">正在读取社区氛围…</div>
        <div v-else-if="degraded" class="land-mood-line">社区氛围读数暂时取不到，不影响你浏览下面的笔记。</div>
        <div v-else class="land-mood-line">
          近 {{ pulse.windowDays || 30 }} 日，{{ num(pulse.week?.checkinUsers) }} 位屿友记录了
          <b>{{ num(pulse.week?.checkinCnt) }}</b> 次心情，社区效价
          <b>{{ valenceLine }}</b>，最常见的三种情绪是
          <span v-for="(e, i) in topEmotions" :key="e.label" class="land-emo"
                :style="{ color: emotionColor(e.label) }">{{ emotionZh(e.label) }}<i v-if="i < topEmotions.length - 1">、</i></span>。
        </div>
      </div>
    </section>

    <!-- 聚合数卡：六个数一排小卡，null 显式「—」。 -->
    <section class="land-nums">
      <div v-for="n in numCards" :key="n.key" class="mi-card land-num-card">
        <p class="land-num">{{ n.value }}</p>
        <p class="land-num-label">{{ n.label }}</p>
        <p class="land-num-tip">{{ n.tip }}</p>
      </div>
    </section>

    <!-- 情绪分布：条宽按 ratio，颜色取 theme.css 的七色板，中文名复用 EmotionPill 那份映射（不在这儿再翻译一遍）。 -->
    <section v-if="emotions.length" class="land-sec">
      <h2 class="land-h2">此刻屿上的情绪</h2>
      <div class="land-bars">
        <div v-for="e in emotions" :key="e.label" class="land-bar-row">
          <emotion-pill class="land-bar-pill" :value="e.label" />
          <span class="land-bar-track">
            <span class="land-bar-fill" :style="{ width: barWidth(e), background: emotionColor(e.label) }"></span>
          </span>
          <span class="land-bar-num">{{ num(e.cnt) }} · {{ pct(e.ratio) }}</span>
        </div>
      </div>
    </section>

    <!-- 精选笔记墙：与广场同一张 PostCard，所以「游客看到的卡片」和「登录后的卡片」形状一致。 -->
    <section class="land-sec">
      <h2 class="land-h2">屿上的笔记</h2>
      <p class="land-hint">这些是近 {{ pulse.windowDays || 30 }} 日被加精或最受欢迎的公开笔记。点开需要登录——我们不做「游客能翻别人的内容、登录后反而不能」这种两套判据的事。</p>
      <div v-if="loading" class="mi-wall land-wall">
        <el-skeleton v-for="i in 6" :key="i" class="land-skeleton mi-card" :rows="4" animated />
      </div>
      <div v-else-if="featured.length" class="mi-wall land-wall">
        <post-card v-for="item in featured" :key="item.id" :item="item"
                   :dismissable="false" :show-status="false" />
      </div>
      <p v-else class="land-hint land-empty">
        这一屏暂时没有公开的精选笔记。注册之后，你的第一篇树洞也可以成为别人看到的那一条。
      </p>
    </section>

    <!-- 热门话题 chips -->
    <section v-if="hotTopics.length" class="land-sec">
      <h2 class="land-h2">大家在聊的话题</h2>
      <div class="land-topics">
        <span v-for="t in hotTopics" :key="t.id" class="land-topic" @click="goTopic(t)">
          # {{ t.name }}<em>{{ num(t.postCnt) }} 篇</em>
        </span>
      </div>
    </section>

    <footer class="land-foot">
      <p>心屿 MindIsle · 本页只展示脱敏后的聚合统计与已过审的公开笔记，不含任何个人内容。</p>
      <p>匿名帖的展示名是系统马甲，不是用户身份标识；如需专业帮助，请拨打全国心理援助热线 12356。</p>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getPulse } from '@/api/community'
import { fmtCount } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import PostCard from '@/components/PostCard.vue'
import EmotionPill from '@/components/EmotionPill.vue'

const router = useRouter()
const user = useUserStore()
const loading = ref(true)
const raw = ref({})

const pulse = computed(() => raw.value || {})
const degraded = computed(() => !!pulse.value.degraded)
const featured = computed(() => pulse.value.featured || [])
const emotions = computed(() => pulse.value.emotions || [])
const hotTopics = computed(() => pulse.value.hotTopics || [])
const topEmotions = computed(() => emotions.value.slice(0, 3))

const week = computed(() => pulse.value.week || {})
const today = computed(() => pulse.value.today || {})

// 数字一律走 num()：null/undefined 显示「—」，这是纪律 ② 的唯一实现处。
function num(v) {
  if (v === null || v === undefined || v === '') return '—'
  return fmtCount(Number(v))
}

// 效价是 -1/0/1 三档的均值，落在 [-1,1]。显示成带符号的两位小数，正负一眼可读。
const valenceLine = computed(() => {
  const v = week.value.valenceAvg
  if (v === null || v === undefined) return '—'
  const n = Number(v)
  return (n > 0 ? '+' : '') + n.toFixed(2)
})

const numCards = computed(() => ([
  { key: 'today', label: '今日新增笔记', tip: '已过审并公开的帖子', value: num(today.value.postCnt) },
  { key: 'post', label: '近 ' + (pulse.value.windowDays || 30) + ' 日发帖', tip: '含树洞与普通分享', value: num(week.value.postCnt) },
  { key: 'checkin', label: '签到记录心情', tip: '主动打卡的人数', value: num(week.value.checkinUsers) },
  { key: 'comfort', label: '被温暖的次数', tip: '别人的笔记被点赞', value: num(week.value.comfortCnt) },
  { key: 'positive', label: '正向情绪占比', tip: '效价为正的记录', value: pct(week.value.positiveRatio) },
  { key: 'hole', label: '树洞数', tip: '7 天自动销毁的匿名帖', value: num(week.value.holeCnt) }
]))

function pct(v) {
  if (v === null || v === undefined) return '—'
  return (Number(v) * 100).toFixed(1) + '%'
}

function barWidth(e) {
  const max = emotions.value.length ? Number(emotions.value[0].cnt || 0) : 0
  if (!max) return '2%'
  return Math.max(2, (Number(e.cnt || 0) / max) * 100).toFixed(1) + '%'
}

// 七色板的变量名与 emotion_record.label 的取值域逐字相同（joy/trust/.../neutral），
// 所以这里用 var(--mi- + label) 是「一份定义」，而不是「前端自己造一套色表」。
// ECharts 之外的 DOM 都能读 CSS 变量，这里没有绕过它的需要。
const EMOTION_KEYS = ['joy', 'trust', 'anger', 'sadness', 'fear', 'disgust', 'neutral']
const EMOTION_ZH = { joy: '喜悦', trust: '信任', anger: '愤怒', sadness: '难过', fear: '恐惧', disgust: '厌恶', neutral: '平静' }
function emotionColor(label) {
  return EMOTION_KEYS.includes(label) ? 'var(--mi-' + label + ')' : 'var(--mi-neutral)'
}
function emotionZh(label) {
  return EMOTION_ZH[label] || label
}

function go(name) {
  router.push({ name })
}
// 立即倾诉指向 /ai，而那条路由是 requiresAuth + requiresConsent：
// 游客点了会被守卫带去登录并带上 redirect，登录后直达对话页。这是预期路径，不是 bug。
function goConsentAi() {
  router.push({ name: 'ai-chat' })
}
function goTopic(t) {
  router.push({ name: 'topic-detail', params: { id: t.id } })
}

async function load() {
  loading.value = true
  try {
    // http 拦截器在 code===0 时已经把 Result 剥成 body.data 了（api/http.js:55），
    // 这里再取一次 res.data 只会得到 undefined —— 页面会「结构正常、全是破折号」。
    raw.value = (await getPulse()) || {}
  } catch (e) {
    // silent 请求：失败不打全局消息，页面靠 degraded/空态自己说清楚。
    raw.value = { degraded: true, week: {}, today: {}, emotions: [], hotTopics: [], featured: [] }
  } finally {
    loading.value = false
  }
}

onMounted(load)
void user
</script>

<style scoped>
.land {
  min-height: 100vh; background: var(--mi-bg); color: var(--mi-text);
  display: flex; flex-direction: column; align-items: center; gap: 22px; padding: 0 0 40px;
}
/* ---- 顶栏：落地页自带一条，不动 BasicLayout 的 nav.mi-nav（取证契约） ---- */
.land-top {
  position: sticky; top: 0; z-index: 5; width: 100%;
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 26px; background: var(--mi-bg-elev); border-bottom: 1px solid var(--mi-border);
}
.land-brand { display: flex; align-items: baseline; gap: 8px; }
.land-logo { font-size: 20px; }
.land-name { font-size: 20px; font-weight: 800; letter-spacing: 1px; color: var(--mi-primary); }
.land-slogan { font-size: 12px; color: var(--mi-text-dim); }
.land-top-actions { display: flex; align-items: center; gap: 6px; }
.land-link { font-size: 13px; color: var(--mi-mist); text-decoration: none; margin-right: 6px; }
.land-link:hover { color: var(--mi-primary); }

/* ---- 品牌区 ---- */
.land-hero { width: min(1120px, 94vw); padding: 28px 4px 6px; }
.land-h1 { margin: 0; font-size: 34px; line-height: 1.35; font-weight: 800; letter-spacing: .5px; }
.land-sub { margin: 14px 0 18px; max-width: 660px; font-size: 14px; line-height: 1.9; color: var(--mi-text-2); }
.land-cta { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.land-hot { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--mi-mist); text-decoration: none; }
.land-hot:hover { color: var(--mi-primary); }
.land-hot-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--mi-danger); }
.land-mood { margin-top: 16px; }
.land-mood-line {
  display: inline-block; padding: 10px 14px; border-radius: var(--mi-radius);
  background: var(--mi-primary-soft); font-size: 13px; line-height: 1.8; color: var(--mi-text-2);
}
.land-mood-line b { color: var(--mi-primary); font-weight: 700; }
.land-emo { font-weight: 700; }
.land-emo i { font-style: normal; color: var(--mi-text-dim); font-weight: 400; }

/* ---- 聚合数 ---- */
.land-nums {
  width: min(1120px, 94vw); display: grid; gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
}
.land-num-card { padding: 14px 16px; }
.land-num { margin: 0; font-size: 26px; font-weight: 800; color: var(--mi-primary); letter-spacing: .5px; }
.land-num-label { margin: 4px 0 0; font-size: 13px; font-weight: 600; color: var(--mi-text); }
.land-num-tip { margin: 2px 0 0; font-size: 12px; color: var(--mi-text-dim); }

/* ---- 分区 ---- */
.land-sec { width: min(1120px, 94vw); }
.land-h2 { margin: 0 0 8px; font-size: 18px; font-weight: 800; }
.land-hint { margin: 0 0 12px; font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); }
.land-empty { padding: 18px; background: var(--mi-card); border-radius: var(--mi-radius); border: 1px solid var(--mi-border); }

.land-bars { display: flex; flex-direction: column; gap: 8px; }
.land-bar-row { display: flex; align-items: center; gap: 10px; }
.land-bar-pill { flex: none; width: 58px; justify-content: center; }
.land-bar-track { flex: 1 1 auto; height: 8px; border-radius: 999px; background: var(--mi-fill); overflow: hidden; }
.land-bar-fill { display: block; height: 100%; border-radius: 999px; }
.land-bar-num { flex: none; width: 128px; text-align: right; font-size: 12px; color: var(--mi-text-dim); }

.land-skeleton { min-height: 220px; }
.land-topics { display: flex; flex-wrap: wrap; gap: 10px; }
.land-topic {
  display: inline-flex; align-items: center; gap: 8px; padding: 8px 14px; cursor: pointer;
  border-radius: 999px; background: var(--mi-card); border: 1px solid var(--mi-border);
  font-size: 14px; font-weight: 600; color: var(--mi-text); transition: all .15s;
}
.land-topic:hover { border-color: var(--mi-primary-line); color: var(--mi-primary); background: var(--mi-primary-soft); }
.land-topic em { font-style: normal; font-weight: 400; font-size: 12px; color: var(--mi-text-dim); }

.land-foot {
  width: min(1120px, 94vw); margin-top: 12px; padding-top: 18px;
  border-top: 1px solid var(--mi-border); font-size: 12px; line-height: 2; color: var(--mi-text-dim);
}
.land-foot p { margin: 0; }
</style>