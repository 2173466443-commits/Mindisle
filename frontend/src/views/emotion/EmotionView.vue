<template>
  <div class="emo">
    <!-- ============================ ① 打卡（FR3.1 / T4.9） ============================ -->
    <section class="mi-card">
      <div class="sec-head">
        <h2>今天感觉怎么样</h2>
        <span class="dim">打卡可以随时补，最长补到 30 天前；补卡是加一条，不会盖掉之前写过的话</span>
      </div>
      <div class="picks">
        <button v-for="e in EMOTIONS" :key="e.value" type="button"
                class="pick" :class="{ on: form.emotion === e.value }" @click="form.emotion = e.value">
          <span class="face">{{ e.face }}</span>
          <emotion-pill :value="e.value" />
          <span class="val">valence {{ e.valence.toFixed(2) }}</span>
        </button>
      </div>

      <div class="row">
        <span class="lab">强度</span>
        <el-slider v-model="form.intensity" :min="1" :max="5" :step="1" show-stops class="slider" />
        <b class="num">{{ form.intensity }}</b>
      </div>
      <div class="row">
        <span class="lab">睡得怎么样</span>
        <el-radio-group v-model="form.sleepBucket">
          <el-radio-button v-for="h in SLEEPS" :key="h.value" :value="h.value">{{ h.label }}</el-radio-button>
        </el-radio-group>
      </div>
      <div class="row">
        <span class="lab">补哪一天</span>
        <el-date-picker v-model="form.recordDate" type="date" size="small" value-format="YYYY-MM-DD"
                        :disabled-date="futureDisabled" placeholder="留空 = 今天" />
        <span class="dim">只能选今天或过去 30 天内的日期</span>
      </div>
      <el-input v-model="form.note" type="textarea" :rows="2" maxlength="200" show-word-limit
                placeholder="一句话就好，写给未来的自己（可选，不会公开展示）" />
      <el-button type="primary" class="submit" :loading="busy" @click="submit">完成打卡</el-button>
      <p v-if="lastSaved" class="saved">
        已记下 {{ lastSaved.recordDate }} 的「{{ lastSaved.emotionZh }}」（强度 {{ lastSaved.intensity }}）
        <span v-if="lastSaved.appended" class="dim">—— 这一天已经有过记录，这是追加的第 2+ 条，旧的没被覆盖</span>
      </p>
      <stage-notice v-if="checkinCode" :code="checkinCode" api-name="POST /api/emotions/checkin"
                    :extra="checkinExtra" />
    </section>

    <!-- ============================ ② 档案四图（FR3.4 / T4.10） ============================ -->
    <section class="mi-card">
      <div class="sec-head">
        <h2>情绪档案</h2>
        <el-radio-group v-model="range" size="small" @change="loadProfile">
          <el-radio-button v-for="r in RANGES" :key="r" :value="r">{{ r }} 天</el-radio-button>
        </el-radio-group>
      </div>
      <stage-notice v-if="profileCode" :code="profileCode" api-name="GET /api/emotions/profile" />
      <template v-else-if="profile">
        <p v-if="chartError" class="chart-err">
          数据已经取到了（接口正常），但图表没画出来：前端渲染异常 {{ chartError }}。
          切一下时间范围或刷新页面即可恢复；下面的数字与清单不依赖图表，仍然可读。
        </p>
        <div class="meta">
          <span>{{ profile.fromDate }} ~ {{ profile.toDate }}</span>
          <span class="dim">共 {{ profile.recordCount }} 条情绪记录，其中可信 {{ profile.confidentCount }} 条</span>
          <span v-if="profile.uncertainCount" class="chip">{{ profile.uncertainCount }} 条置信度不足，没算进趋势</span>
          <span class="dim">连续打卡 {{ profile.streak.current }} 天 · 最长 {{ profile.streak.longest }} 天</span>
        </div>

        <h3 class="sub">情绪趋势</h3>
        <!-- BR12：可信天数 < 3 时折线没有统计意义，画出来反而是误导，所以给它一句人话而不是空坐标系 -->
        <div v-if="profile.accumulating" class="accum">
          <p class="accum-t">数据积累中</p>
          <p class="dim">
            这 {{ profile.rangeDays }} 天里有 {{ profile.dayCount }} 天留下了可信记录。
            满 3 天之后这里会出现趋势线——三天以下的「趋势」是噪声，我们不想拿它吓你。
          </p>
        </div>
        <div v-else ref="trendEl" class="chart" />

        <div class="grid2">
          <div>
            <h3 class="sub">情绪分布</h3>
            <div v-show="!profile.accumulating" ref="pieEl" class="chart sm" />
            <div v-show="profile.accumulating" ref="pieEl2" class="chart sm" />
            <p v-if="!profile.distribution.length" class="dim">这一段时间还没有可统计的记录。</p>
          </div>
          <div>
            <h3 class="sub">高频触发词</h3>
            <div v-show="profile.wordcloud.length" ref="wordEl" class="chart sm" />
            <p v-if="!profile.wordcloud.length" class="dim">
              词云来自打卡与对话里命中的情绪词面。写一句「为什么今天不开心」，这里就会有内容。
            </p>
          </div>
        </div>

        <h3 class="sub">日历热力</h3>
        <div ref="calEl" class="chart" />

        <div class="legend-src">
          <span class="dim">记录来源：</span>
          <span v-for="s in profile.sources" :key="s.key" class="dim">
            {{ s.zh }} {{ s.count }} 条<template v-if="s.ratio">（{{ pct(s.ratio) }}）</template>{{ ' ' }}
          </span>
        </div>
      </template>
      <el-skeleton v-else :rows="4" animated />
    </section>

    <!-- ============================ ③ 周报（FR3.5 / T4.20） ============================ -->
    <section class="mi-card">
      <div class="sec-head">
        <h2>情绪周报</h2>
        <div class="head-r">
          <el-radio-group v-model="week" size="small" @change="loadReport">
            <el-radio-button value="current">本周</el-radio-button>
            <el-radio-button value="last">上周</el-radio-button>
          </el-radio-group>
          <el-button size="small" :loading="reportBusy" @click="loadReport(true)">再算一次</el-button>
          <!-- 去标识分享（T4.20 ③ / BR13）：文案随 report.shared 变，点第二次是跳到那条帖而不是再发一条 -->
          <el-button size="small" type="primary" plain :loading="shareBusy"
                    :disabled="!report || !report.summaryText" @click="shareReport">
            {{ report && report.shared ? '已分享 · 去看那条帖' : '分享（去标识）' }}
          </el-button>
        </div>
      </div>
      <stage-notice v-if="reportCode" :code="reportCode" api-name="GET /api/emotions/weekly-report"
                    :extra="reportExtra" />
      <template v-else-if="report">
        <p class="wk-range">{{ report.weekStart }} ~ {{ report.weekEnd }}
          <el-tag size="small" :type="report.generator === 'llm' ? 'success' : 'info'">
            {{ report.generator === 'llm' ? '模型撰写' : '本地模板' }}
          </el-tag>
        </p>
        <p class="summary">{{ report.summaryText }}</p>
        <div class="nums">
          <span>打卡 {{ report.checkinDays }} 天</span>
          <span>记录 {{ report.recordCount }} 条</span>
          <span v-if="report.dominantZh">主导情绪 {{ report.dominantZh }}</span>
          <span v-if="report.avgIntensity != null">平均强度 {{ report.avgIntensity.toFixed(2) }}</span>
          <span>正向占比 {{ pct(report.positiveRatio) }}</span>
          <span v-if="report.trendDelta != null">
            环比 {{ report.trendDelta > 0 ? '+' : '' }}{{ report.trendDelta.toFixed(2) }}
          </span>
        </div>
        <p v-if="report.accumulating" class="dim">
          这一周的可信记录还没满三天，上面的结论用的是本地模板而不是模型：数据太少时让模型写「趋势」，
          它一定会编出一个趋势来。
        </p>
        <p v-if="shareMsg" class="dim" :class="{ bad: shareCode }">{{ shareMsg }}</p>
        <p class="dim">
          点「分享」会发出一条<strong>匿名马甲</strong>的普通帖，正文只有上面这段结论和这一周的区间：
          打卡原文、各情绪条数、平均强度与正向占比都不进正文（需求 BR13「分享去标识」）。
          同一条周报再点一次不会发第二条，按钮会直接变成「已分享 · 去看那条帖」。
        </p>
      </template>
      <el-skeleton v-else :rows="2" animated />
    </section>

    <!-- ============================ ④ 口径说明（答辩用） ============================ -->
    <section class="mi-card">
      <h2>这份情绪数据会被怎么用（答辩口径）</h2>
      <ol class="steps">
        <li>打卡与对话文本先过<strong>混合式中文情绪识别</strong>：情绪词典（DUT 派生 27,315 词面）出价，
            置信度 &lt; 0.55 或命中风险词才升级送 LLM（创新点 1）。</li>
        <li>结果统一写入 <code>emotion_record</code>，每行都带 <code>channel</code>（dict / llm / manual）
            与 <code>model_version</code>，所以每个数都能回答「你是怎么知道的」。</li>
        <li><code>confidence &lt; 0.6</code> 的记录标记 uncertain，<strong>不计入趋势</strong>（BR12）——
            档案页宁可少画一条线，也不把不确定的判断画成事实。</li>
        <li>连续负向或命中危机词触发 <strong>L0–L3 分级响应</strong>，置顶 12356 求助卡片（创新点 3）。</li>
      </ol>
      <p class="dim">心屿只做陪伴与倾诉，不做医学诊断；周报里的「主导情绪」是统计描述，不是结论。
        风险判断按「宁可误报不可漏报」的代价敏感策略处理。</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import http from '@/api/http'
import EmotionPill from '@/components/EmotionPill.vue'
import StageNotice from '@/components/StageNotice.vue'
import { CODE } from '@/api/errorCode'
import * as echarts from 'echarts/core'
import { CustomChart, LineChart, PieChart, HeatmapChart } from 'echarts/charts'
import {
  CalendarComponent, GridComponent, LegendComponent,
  TooltipComponent, VisualMapComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
// 词云用仓库内 vendored 副本，不直接引 npm 包：上游那一份 dist 有一句 import { number } from 'echarts'，
// 会把 echarts 全量入口拖进本路由 chunk（实测 +521 kB min / +170 kB gzip），只为一个 parsePercent。
// 副本里只有那一行被换成了本地实现，布局算法未改，许可与来源见 src/vendor/VENDOR.md。
import wordCloudInstaller from '@/vendor/word-cloud.esm.js'

// 只注册本页真正用到的四张图（趋势折线 / 分布饼 / 词云 / 日历热力），
// 不 import 'echarts' 全量包：全量约 1MB gzip 前，毕设答辩机也是普通笔记本。
echarts.use([
  CustomChart, LineChart, PieChart, HeatmapChart,
  GridComponent, TooltipComponent, LegendComponent, VisualMapComponent,
  CalendarComponent, CanvasRenderer, wordCloudInstaller
])

// 七类与效价取自需求 §1.5 / 后端 dict/prior.json，两端同一套数（<b>不含 surprise</b>）。
const EMOTIONS = [
  { value: 'joy', face: '🙂', valence: 0.8 },
  { value: 'trust', face: '😌', valence: 0.5 },
  { value: 'neutral', face: '😐', valence: 0.0 },
  { value: 'sadness', face: '😔', valence: -0.6 },
  { value: 'fear', face: '😨', valence: -0.6 },
  { value: 'anger', face: '😠', valence: -0.6 },
  { value: 'disgust', face: '🙁', valence: -0.4 }
]
const SLEEPS = [
  { value: 0, label: '不足 4h' },
  { value: 1, label: '4-6h' },
  { value: 2, label: '6-8h' },
  { value: 3, label: '8h 以上' }
]
// 后端 EmotionProfileService.ALLOWED_RANGES = {7,30,90}，传别的直接 10001，所以这里不给第三个入口。
const RANGES = [7, 30, 90]
const EMOTION_COLOR = {
  joy: '#F2C14E', trust: '#7FB3A6', anger: '#D9534F', sadness: '#5B7C99',
  fear: '#8E6BAF', disgust: '#7A8B3C', neutral: '#8A94A6'
}

const form = reactive({ emotion: 'neutral', intensity: 3, sleepBucket: 2, note: '', recordDate: '' })
const busy = ref(false)
const checkinCode = ref(null)
const lastSaved = ref(null)
const range = ref(7)
const profile = ref(null)
const profileCode = ref(null)
const chartError = ref(null)
const week = ref('current')
const report = ref(null)
const reportCode = ref(null)
const reportBusy = ref(false)
const shareBusy = ref(false)
const shareMsg = ref('')
const shareCode = ref(null)

const router = useRouter()

const trendEl = ref(null); const pieEl = ref(null); const pieEl2 = ref(null)
const wordEl = ref(null); const calEl = ref(null)
const charts = []

// 90001/20005 这类码在 errorCode.js 里有一句总表文案，但那一句不足以说明「你现在能做什么」，
// 所以这里按码补一条可执行的解释（与 ChatView 的 noticeExtra 同一口径）。
const checkinExtra = computed(() => {
  const c = Number(checkinCode.value)
  if (c === CODE.SENSITIVE_CONSENT_REQUIRED) {
    return '情绪打卡属于「敏感个人信息」，按 NFR8 需要你在「我的 · 隐私与同意」里单独授权之后才写库。没授权时一个字都不会存。'
  }
  if (c === CODE.PARAM_INVALID) return '参数没过去：检查一下强度和补卡日期（强度 1-5，补卡不能超 30 天、不能是未来）。'
  return ''
})
const reportExtra = computed(() => {
  const c = Number(reportCode.value)
  if (c === CODE.SENSITIVE_CONSENT_REQUIRED) return '周报要读你的情绪数据，这部分数据在没授权时根本没被采集，所以这里也没有。'
  return ''
})

function pct(ratio) {
  const v = Number(ratio || 0) * 100
  return (v < 10 ? v.toFixed(1) : v.toFixed(0)) + '%'
}
function futureDisabled(d) {
  return d.getTime() > Date.now()
}

// ============================================================ 打卡

async function submit() {
  busy.value = true
  checkinCode.value = null
  try {
    const data = await http.post('/emotions/checkin', {
      emotion: form.emotion,
      intensity: form.intensity,
      sleepBucket: form.sleepBucket,
      note: form.note || null,
      recordDate: form.recordDate || null
    })
    lastSaved.value = data
    form.note = ''
    await Promise.all([loadProfile(), loadReport()])
  } catch (e) {
    checkinCode.value = e.code || 'network'
  } finally {
    busy.value = false
  }
}

// ============================================================ 四图

async function loadProfile() {
  profileCode.value = null
  chartError.value = null
  try {
    profile.value = await http.get('/emotions/profile', { params: { range: range.value }, silent: true })
  } catch (e) {
    profileCode.value = e.code || 'network'
    profile.value = null
    return
  }
  // 数据到手与图画出来是两件事，以前写在同一个 try 里，于是渲染期抛的异常被当成
  // 「接口失败」记成 code=network，而模板 v-if="profileCode" 会把整段四图换成一张
  // 服务异常卡片 —— 用户看到的就是「情绪档案没做好」，而后端 200 全字段返回。
  // 现在渲染失败只登记渲染失败，保留数据与控制台里的真实异常，不再冒充网络错误。
  await nextTick()
  try {
    renderCharts()
  } catch (e) {
    chartError.value = (e && e.message) ? e.message : String(e)
    console.error('[MindIsle][chart] 情绪档案四图渲染失败', e)
  }
}

function initChart(el) {
  if (!el) return null
  let c = echarts.getInstanceByDom(el)
  if (!c) c = echarts.init(el)
  if (!charts.includes(c)) charts.push(c)
  return c
}

function renderCharts() {
  const p = profile.value
  if (!p) return
  renderTrend(p)
  renderPie(p)
  renderWord(p)
  renderCalendar(p)
}

function renderTrend(p) {
  if (p.accumulating) return
  const el = trendEl.value
  const c = initChart(el)
  if (!c) return
  c.setOption({
    grid: { left: 44, right: 18, top: 28, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      formatter: (ps) => {
        const t = p.trend[ps[0].dataIndex]
        if (!t) return ''
        const iv = t.avgIntensity == null ? '—' : t.avgIntensity.toFixed(2)
        return t.date + '<br/>' + t.labelZh + '　强度 ' + iv
          + '<br/>记录 ' + t.recordCount + ' 条（可信 ' + t.confidentCount + '）'
      }
    },
    xAxis: {
      type: 'category', boundaryGap: false,
      data: p.trend.map((t) => t.date.slice(5)),
      axisLabel: { color: '#93A1B8' }, axisLine: { lineStyle: { color: '#24304d' } }
    },
    yAxis: {
      type: 'value', min: 1, max: 5, interval: 1,
      name: '强度', nameTextStyle: { color: '#93A1B8' },
      axisLabel: { color: '#93A1B8' }, splitLine: { lineStyle: { color: '#24304d' } }
    },
    series: [{
      type: 'line', smooth: true, symbolSize: 9,
      // 每个点单独上色 = 当天的主导情绪色，一条线同时回答「强度如何」与「是什么情绪」
      data: p.trend.map((t) => ({
        value: t.avgIntensity,
        itemStyle: { color: EMOTION_COLOR[t.label] || EMOTION_COLOR.neutral }
      })),
      lineStyle: { color: '#7FA7C4', width: 2 },
      areaStyle: { color: 'rgba(127,167,196,0.10)' }
    }]
  }, true)
}

function renderPie(p) {
  const el = p.accumulating ? pieEl2.value : pieEl.value
  if (!p.distribution.length) return
  const c = initChart(el)
  if (!c) return
  c.setOption({
    tooltip: { trigger: 'item', formatter: (x) => x.name + ' ' + x.value + ' 条（' + x.percent + '%）' },
    legend: { type: 'scroll', bottom: 0, textStyle: { color: '#93A1B8', fontSize: 11 }, itemWidth: 10, itemHeight: 10 },
    series: [{
      type: 'pie', radius: ['38%', '62%'], center: ['50%', '44%'],
      label: { color: '#93A1B8', fontSize: 11, formatter: '{b} {d}%' },
      data: p.distribution.map((d) => ({
        name: d.zh, value: d.count,
        itemStyle: { color: EMOTION_COLOR[d.key] || EMOTION_COLOR.neutral }
      }))
    }]
  }, true)
}

// 词云字号由命中数线性映射，颜色按词面哈希取固定色板 —— 不用 Math.random()：
// 答辩要在同一页截两次图，随机色会给出两张不一样的图，评审问「这个词为什么变了」我答不上来。
const WC_PALETTE = ['#F2C14E', '#7FB3A6', '#7FA7C4', '#E8B4A0', '#8E6BAF', '#7A8B3C', '#8A94A6', '#5B7C99']
function wcColor(word) {
  let h = 2166136261
  const w = String(word)
  for (let i = 0; i < w.length; i++) { h ^= w.codePointAt(i); h = (h * 16777619) >>> 0 }
  return WC_PALETTE[h % WC_PALETTE.length]
}

function renderWord(p) {
  if (!p.wordcloud.length) return
  const c = initChart(wordEl.value)
  if (!c) return
  const max = p.wordcloud[0].count || 1
  c.setOption({
    tooltip: { formatter: (x) => (x.name || '') + ' 命中 ' + ((x.data && x.data.hit) || x.value[1]) + ' 次' },
    series: [{
      type: 'custom', renderItem: 'wordCloud',
      // 下面这两处是 2026-09-24 真浏览器取证修出来的，别退回上一版写法：
      // 上一版照 echarts-wordcloud v2（ECharts 5 时代）的旧形状把 shape/sizeRange/gridSize
      // 摊在 series 根上，而仓库里这份是 @echarts-x/custom-word-cloud 1.0.1，
      // 新「自定义系列」API 的读法是 params.itemPayload
      // （echarts/lib/chart/custom/CustomView.js:441 itemPayload: customSeries.get('itemPayload') || {}），
      // 摊在根上它一个都读不到，只能吃默认值。
      // 更致命的是坐标系：registerCustomSeries 只注册渲染函数
      // （customSeriesRegister.js 里就一句 customRenderers[type] = renderItem），
      // 不改 CustomSeries 默认的 cartesian2d，于是 setOption 期直接抛
      // Error: xAxis "0" not found —— 词云画不出来，还把后面两张图一起带走。
      // 它的布局算的是像素（performLayout 里 api.getWidth()/getHeight()），所以要 view 坐标系。
      coordinateSystem: 'view',
      itemPayload: {
        left: 0, top: 0, right: 0, bottom: 0,
        shape: 'circle', sizeRange: [12, 34], rotationRange: [0, 0], gridSize: 6, shrinkToFit: true
      },
      // 颜色走每个数据项自己的 itemStyle.color：echarts 的 visual/style.js:141-148
      // 把 raw item 的 itemStyle 折进该点的 style visual，而 CustomView 的
      // api.visual('color') 读的正是 style.fill —— 上游 renderItem 用的就是后者。
      data: p.wordcloud.map((w) => ({
        name: w.word,
        value: [w.word, Math.max(1, Math.round((w.count / max) * 100))],
        itemStyle: { color: wcColor(w.word) },
        hit: w.count
      }))
    }]
  }, true)
}

function renderCalendar(p) {
  const c = initChart(calEl.value)
  if (!c) return
  // 热力值 = 当日记录条数；颜色只分正负向，避免「颜色越深越糟」被读成诊断结论
  const data = p.calendar.filter((d) => d.hasRecord).map((d) => [d.date, d.count])
  const byDate = {}; p.calendar.forEach((d) => { byDate[d.date] = d })
  c.setOption({
    tooltip: {
      formatter: (x) => {
        const d = byDate[x.value[0]]
        if (!d) return x.value[0] + '<br/>没有记录'
        return d.date + '<br/>' + (d.labelZh || '未记录') + '　' + d.count + ' 条'
          + (d.checkedIn ? '（含主动打卡）' : '') + (d.maxIntensity ? '　最高强度 ' + d.maxIntensity : '')
      }
    },
    visualMap: {
      min: 0, max: Math.max(1, ...data.map((d) => d[1])), show: false,
      inRange: { color: ['#2b3d5c', '#5B7C99', '#8E6BAF'] }
    },
    calendar: {
      range: [p.fromDate, p.toDate], cellSize: ['auto', 16], left: 40, right: 12, top: 12,
      itemStyle: { color: '#101a30', borderColor: '#24304d' },
      splitLine: { show: false },
      yearLabel: { show: false },
      dayLabel: { color: '#93A1B8', fontSize: 10, firstDay: 1, nameMap: ['日', '一', '二', '三', '四', '五', '六'] },
      monthLabel: { color: '#93A1B8', fontSize: 11, nameMap: ['1月','2月','3月','4月','5月','6月','7月','8月','9月','10月','11月','12月'] }
    },
    series: [{ type: 'heatmap', coordinateSystem: 'calendar', data }]
  }, true)
}

// ============================================================ 周报

async function loadReport(refresh) {
  reportBusy.value = true
  reportCode.value = null
  try {
    const params = { week: week.value }
    if (refresh === true) params.refresh = true
    report.value = await http.get('/emotions/weekly-report', { params, silent: true })
  } catch (e) {
    reportCode.value = e.code || 'network'
    report.value = null
  } finally {
    reportBusy.value = false
  }
}

// ============================================================ 分享（T4.20 ③）

// 后端把状态机结果原样回传，这里不美化：MACHINE_REVIEW / HUMAN_REVIEW 与 PUBLISHED
// 对用户是两件不同的事（一条已经能在广场看到，一条还要等审核），说成「已分享成功」就是谎报。
const SHARE_STATUS = {
  PUBLISHED: '已经出现在广场',
  MACHINE_REVIEW: '正在机器审核，通过后会自动出现在广场',
  HUMAN_REVIEW: '辅导员复核中，通过后才会出现在广场',
  DRAFT: '已存为草稿，等待审核放行',
  REJECTED: '这条被审核拦下了，可以在「我的帖子」里看原因'
}

function shareFailText(code) {
  if (Number(code) === CODE.FORBIDDEN) return '这份周报不是当前账号的，不能被分享出去。'
  if (Number(code) === CODE.RESOURCE_NOT_FOUND) return '没找到这份周报，可能已经被删除了。刷新一下再看。'
  if (Number(code) === CODE.PARAM_INVALID) return '这份周报还没有结论文案，先点「再算一次」生成出来再分享。'
  if (Number(code) === CODE.RATE_LIMITED) return '今天的发帖配额用完了，明天再分享这一份；周报本身没有被标记为已分享，可以再点。'
  if (code === 'network') return '服务没响应（HTTP 层），周报没有被标记为已分享，可以再点一次。'
  return '分享没有成功，周报没有被标记为已分享，可以再点一次或稍后再试。'
}

async function shareReport() {
  const row = report.value
  if (!row) return
  // 已经分享过：直接跳那条帖。幂等本身是后端负责的（重复调用只会回放原帖），
  // 但这里少打一次接口，用户也少等一次来回 —— 两边都做各自那半件对的事。
  if (row.shared && row.sharedPostId) {
    router.push('/post/' + row.sharedPostId)
    return
  }
  shareBusy.value = true
  shareCode.value = null
  shareMsg.value = ''
  try {
    const data = await http.post('/emotions/weekly-report/' + row.id + '/share', null, { silent: true })
    if (data && data.alreadyShared) {
      // 幂等回放：上一次点出去的那一条其实已经成功了（多半是网络抖了一下又点了一次）
      report.value = { ...row, shared: true, sharedPostId: data.postId }
      shareMsg.value = `这一份周报早前已经分享过了，就是帖子 #${data.postId}，没有再发第二条。`
      return
    }
    report.value = { ...row, shared: true, sharedPostId: data.postId }
    shareMsg.value = `已用马甲名「${data.displayName}」发出帖子 #${data.postId}：${SHARE_STATUS[data.postStatus] || data.postStatus}。`
      + (data.tip ? `（${data.tip}）` : '')
  } catch (e) {
    shareCode.value = e.code || 'network'
    shareMsg.value = shareFailText(shareCode.value)
  } finally {
    shareBusy.value = false
  }
}

// ============================================================ 生命周期

let resizeRaf = 0
function onResize() {
  // ECharts 6 的告警「resize should not be called during main process」：
  // 在 Vue 渲染/补丁还没结束时量容器尺寸会拿到 0，HMR 全量刷新时最容易撞上。
  // 挪到下一帧，顺带把连续多次 resize 合并成一次。
  if (resizeRaf) cancelAnimationFrame(resizeRaf)
  resizeRaf = requestAnimationFrame(() => {
    resizeRaf = 0
    charts.forEach((c) => { try { c.resize() } catch (e) { /* 已销毁 */ } })
  })
}

onMounted(async () => {
  await Promise.all([loadProfile(), loadReport()])
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (resizeRaf) { cancelAnimationFrame(resizeRaf); resizeRaf = 0 }
  charts.forEach((c) => { try { c.dispose() } catch (e) { /* noop */ } })
  charts.length = 0
})
</script>

<style scoped>
.emo { display: flex; flex-direction: column; gap: 18px; }
.sec-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; gap: 12px; flex-wrap: wrap; }
.head-r { display: flex; align-items: center; gap: 10px; }
h2 { margin: 0 0 10px; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
h3.sub { margin: 18px 0 6px; font-size: 13px; color: var(--mi-mist); font-weight: 600; letter-spacing: 1px; }
.dim { font-size: 12px; color: var(--mi-text-dim); }
.picks { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px; margin-bottom: 16px; }
.pick { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 12px 6px; cursor: pointer;
        background: rgba(127, 167, 196, 0.06); border: 1px solid var(--mi-border); border-radius: 12px; color: var(--mi-text-dim); }
.pick.on { border-color: var(--mi-primary); background: rgba(240, 135, 107, 0.12); color: var(--mi-text); }
.face { font-size: 26px; }
.val { font-size: 11px; font-family: Consolas, monospace; }
.row { display: flex; align-items: center; gap: 14px; margin: 10px 0; flex-wrap: wrap; }
.lab { width: 96px; font-size: 13px; color: var(--mi-text-dim); flex: none; }
.slider { flex: 1; min-width: 220px; }
.num { width: 20px; text-align: center; }
.submit { margin-top: 14px; }
.saved { margin: 10px 0 0; font-size: 13px; color: var(--mi-trust); }
.meta { display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 12px; color: var(--mi-text-dim);
        padding-bottom: 4px; border-bottom: 1px dashed var(--mi-border); }
.chip { color: var(--mi-primary); }
.chart-err { margin: 0 0 10px; padding: 8px 10px; font-size: 12px; color: #E8B4A0;
             background: rgba(217, 83, 79, 0.10); border-left: 3px solid #D9534F; border-radius: 6px; }
.chart { width: 100%; height: 260px; }
.chart.sm { height: 210px; }
.grid2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 18px; }
.accum { padding: 26px 18px; text-align: center; background: rgba(127, 167, 196, 0.06);
         border: 1px dashed var(--mi-border); border-radius: 12px; }
.accum-t { margin: 0 0 8px; font-size: 15px; color: var(--mi-mist); letter-spacing: 2px; }
.legend-src { margin-top: 12px; font-size: 12px; }
.wk-range { margin: 0 0 8px; font-size: 13px; color: var(--mi-text-dim); display: flex; align-items: center; gap: 10px; }
.summary { margin: 0 0 10px; font-size: 14px; line-height: 2; color: var(--mi-text); white-space: pre-wrap; }
.nums { display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 12px; color: var(--mi-text-dim); }
/* 分享那一行的两种口径：灰字是说明，带 bad 的是「这次没成功」——
   两者用同一个颜色就会让用户分不清点到了什么。 */
.dim.bad { color: #E8B4A0; }
.steps { margin: 0; padding-left: 20px; line-height: 2; font-size: 13px; }
.steps code { font-family: Consolas, monospace; font-size: 12px; color: var(--mi-mist); }
</style>
