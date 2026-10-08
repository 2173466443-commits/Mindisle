<template>
  <div class="screen-wrap">
    <div class="screen" :style="stageStyle">
      <header class="hd">
        <div class="title">
          <span class="mark">🏝</span>
          <div>
            <h1>心屿 · 校园心理陪伴社区运行大屏</h1>
            <p>MindIsle Operation Board —— 只聚合，不显示任何个体（FR3.6 / FR8.2）</p>
          </div>
        </div>
        <div class="hd-right">
          <span class="clock">{{ clock }}</span>
          <span class="beat" :class="{ off: paused }">{{ beatText }}</span>
        </div>
      </header>

      <!-- 12 张数卡：字段名与后端 DashboardStatsRow 逐字一致，界面上把口径写在数字下面 -->
      <section class="cards">
        <div v-for="c in cards" :key="c.key" class="card">
          <div class="k">{{ c.label }}</div>
          <div class="v" :class="c.tone">{{ c.value }}</div>
          <div class="s">{{ c.sub }}</div>
        </div>
      </section>

      <section class="grid">
        <!-- 六图按手册 §9.3 的编号排：4 列 × 2 行 = 8 格，两个 wide 各占 2 格，正好铺满（旧版只有 5 块、右下角空一格） -->
        <div class="panel wide" data-chart="trend">
          <div class="ph">
            <span>近 {{ TREND_DAYS }} 日活跃与情绪指数（双轴：柱=活跃人数 / 线=平均效价）</span>
            <span class="dim">{{ trendNote }}</span>
          </div>
          <div ref="trendEl" class="chart"></div>
          <div v-if="bad.trend" class="err">{{ bad.trend }}</div>
        </div>

        <div class="panel" data-chart="emotion">
          <div class="ph">
            <span>情绪分布（近 {{ days }} 天 emotion_record.label）</span>
            <span class="dim">7 类标签，后端 EmotionPrior.LABELS 无 surprise</span>
          </div>
          <div ref="emotionEl" class="chart"></div>
          <div v-if="bad.emotion" class="err">{{ bad.emotion }}</div>
        </div>

        <div class="panel" data-chart="hours">
          <div class="ph">
            <span>日 × 24 小时情绪热力（近 {{ HEAT_DAYS }} 天）</span>
            <span class="dim">{{ heatNote }}</span>
          </div>
          <div class="hm" :style="{ gridTemplateRows: '13px repeat(' + heatRows.length + ', minmax(0, 1fr))' }">
            <div class="hm-corner"></div>
            <div v-for="h in 24" :key="'rule' + (h - 1)" class="hm-ruler">{{ (h - 1) % 6 === 0 ? h - 1 : '' }}</div>
            <template v-for="row in heatRows" :key="row.key">
              <div class="hm-day"><b>{{ row.label }}</b><i>{{ row.sub }}</i></div>
              <div v-for="c in row.cells" :key="c.hour" class="hcell" :style="cellStyle(c)" :title="c.title"></div>
            </template>
          </div>
          <div class="legend">
            <span>色深=该格条数</span>
            <span class="lg neg"></span><span>红边=负效价</span>
            <span class="lg pos"></span><span>黄边=正效价</span>
            <span class="dim">空格＝那天那小时没有任何记录，不补 0 也不涂成中性（没有样本≠情绪中性）</span>
          </div>
          <div v-if="bad.hours" class="err">{{ bad.hours }}</div>
        </div>

        <div class="panel wide" data-chart="word">
          <div class="ph">
            <span>高频话题词云 TOP20（已发布帖 join 重算，不读 topic.post_cnt）</span>
            <span class="dim">字号=该话题已发布帖数，颜色按词面哈希取固定色板，不用随机数</span>
          </div>
          <div ref="wordEl" class="chart"></div>
          <div v-if="bad.word" class="err">{{ bad.word }}</div>
        </div>

        <div class="panel" data-chart="grade">
          <div class="ph"><span>年级聚合柱状（user_profile JOIN user 且 u.deleted=0）</span></div>
          <div ref="gradeEl" class="chart"></div>
          <div v-if="bad.grade" class="err">{{ bad.grade }}</div>
        </div>

        <div class="panel" data-chart="ai">
          <div class="ph">
            <span>AI 用量与费用（FR8.2：费用口径就是 ai_call_log 的汇总，不另算）</span>
            <span class="dim">{{ costNote }}</span>
          </div>
          <div ref="aiEl" class="chart"></div>
          <div v-if="bad.ai" class="err">{{ bad.ai }}</div>
        </div>
      </section>

      <footer class="ft">
        <span>数据源：/api/admin/dashboard/*（stats · emotion-board · emotion-labels · day-hour-heatmap · hot-topics · grade-board · ai-usage）</span>
        <span>本轮刷新 {{ lastCost }}ms · 上次成功 {{ lastOkAt }} · 累计失败 {{ failCnt }} 次</span>
        <a class="back" href="/dashboard">返回工作台</a>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts/core'
import { CustomChart, BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
// 词云与用户端用同一份 vendored 副本（Apache-2.0，改动只有一行 import，理由见 src/vendor/VENDOR.md）。
// 直接引 npm 包会把 echarts 全量入口拖进这一块：A/B 实测 +521.11 kB（min）/ +170.31 kB（gzip）。
import wordCloudInstaller from '@/vendor/word-cloud.esm.js'
import {
  aiUsage, dashboardStats, dayHourHeatmap, emotionBoard, emotionLabels, gradeBoard, hotTopics
} from '@/api/admin'
import { fmtNum } from '@/utils/format'

echarts.use([
  CustomChart, BarChart, LineChart, PieChart,
  GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, CanvasRenderer, wordCloudInstaller
])

const DAY_MS = 86400000
const REFRESH_MS = 5000
const days = ref(30)
// 手册 §9.3 第 2 条把这块的窗口写死成「近 14 日」，所以它不跟着 days（其余面板取 30）一起动，
// 面板副标题里把「窗口 14 天、实际有记录 N 天」直接写出来，读图的人不用猜。
const TREND_DAYS = 14
// 热力窗口单独写死 7，不跟 days（其余面板取 30）走：这块是「星期几 × 小时」的二维网格，
// 取 7 天正好一列一周，30 天就是 720 格，在大屏一格里既放不下也读不出来。
// 后端把窗口夹到 90 天，真要拉长窗口去 /day-hour-heatmap?days= 传，不在这里改常量。
const HEAT_DAYS = 7

const trendEl = ref(null)
const emotionEl = ref(null)
const wordEl = ref(null)
const gradeEl = ref(null)
const aiEl = ref(null)
const charts = {}

const stats = ref(null)
const daily = ref([])
const labels = ref([])
const heat = ref(null)
const topics = ref([])
const grades = ref([])
const ai = ref([])
const bad = reactive({ stats: '', trend: '', emotion: '', hours: '', word: '', grade: '', ai: '' })

const clock = ref('')
const paused = ref(false)
const lastOkAt = ref('—')
const lastCost = ref(0)
const failCnt = ref(0)
let timer = null
let clockTimer = null
let resizeHandler = null
const vw = ref(window.innerWidth)
const vh = ref(window.innerHeight)

// 1920×1080 定版画布 + transform:scale 适配：大屏投影仪按等比铺满，
// 答辩用笔记本（1366×768 等）也不用改一帧代码，且不会像媒体查询那样出现第二套布局。
const stageStyle = computed(() => {
  const s = Math.min(vw.value / 1920, vh.value / 1080)
  return { transform: 'scale(' + s.toFixed(4) + ')', width: '1920px', height: '1080px' }
})

const beatText = computed(() => {
  if (paused.value) return '页面不可见 · 已暂停轮询'
  return '每 5 秒自动刷新'
})

const cards = computed(() => {
  const s = stats.value || {}
  const n = (v) => (v === undefined || v === null ? '—' : fmtNum(v))
  return [
    { key: 'dau', label: '今日活跃', value: n(s.dau), sub: '近 24h 有打卡/发帖/AI 对话的去重用户', tone: '' },
    { key: 'newUserCnt', label: '累计注册', value: n(s.newUserCnt), sub: 'user 表未删除账号', tone: '' },
    { key: 'postCnt', label: '社区树洞', value: n(s.postCnt), sub: '已发布帖子总数', tone: '' },
    { key: 'chatRoundCnt', label: 'AI 陪伴轮次', value: n(s.chatRoundCnt), sub: 'ai_message 中 role=user 的条数', tone: '' },
    { key: 'crisisCnt', label: '危机工单', value: n(s.crisisCnt), sub: '双通道 RiskScorer 判到 L2/L3 的总量', tone: 'warn' },
    { key: 'tokenCnt', label: 'AI 词元消耗', value: n(s.tokenCnt), sub: 'ai_call_log.prompt+completion 汇总', tone: '' },
    { key: 'cost', label: 'AI 费用', value: costText(s), sub: costNote.value, tone: '' },
    { key: 'avgValence', label: '平均情绪效价', value: valText(s), sub: '近 30 天 emotion_record.valence 均值', tone: valTone(s) },
    { key: 'auditPendingCnt', label: '待人工审核', value: n(s.auditPendingCnt), sub: 'audit_task.status=PENDING（不是空队列）', tone: s.auditPendingCnt > 0 ? 'warn' : 'ok' },
    { key: 'ticketPendingCnt', label: '未办结工单', value: n(s.ticketPendingCnt), sub: 'pending/claimed/doing 三态合计', tone: s.ticketPendingCnt > 0 ? 'warn' : 'ok' },
    { key: 'reportPendingCnt', label: '待处置举报', value: n(s.reportPendingCnt), sub: 'content_report.status=PENDING', tone: s.reportPendingCnt > 0 ? 'warn' : 'ok' },
    { key: 'overdueCnt', label: 'SLA 超时', value: n(s.overdueCnt), sub: 'sla_at 已过期且未办结', tone: s.overdueCnt > 0 ? 'bad' : 'ok' }
  ]
})

// 🔴 cost_cent 现在整库是 0：DeepSeek 计费回填链路还没接价格表，这是事实不是 bug。
// 如果大屏显示「费用 0 元」，答辩时会被理解成「AI 免费」，而 tokenCnt=10 万+ 才是真实用量。
// 所以取不到成本时，这块明确写「成本未回填」并让词元数当结论，不拿 0 冒充结论。
function costText (s) {
  if (s.costCent === undefined || s.costCent === null) return '—'
  const cent = Number(s.costCent)
  if (cent === 0) return '未回填'
  return '¥' + (cent / 100).toFixed(2)
}
const costNote = computed(() => {
  const cent = stats.value ? Number(stats.value.costCent) : null
  if (cent === 0) return 'cost_cent 全库为 0（单价表未接入），故以 tokenCnt 为用量结论'
  if (cent === null || cent === undefined) return '成本字段未返回'
  return 'cost_cent 汇总 = ' + cent + ' 分'
})
function valText (s) {
  if (s.avgValence === undefined || s.avgValence === null) return '—'
  const v = Number(s.avgValence)
  if (!v) return '0.00（样本不足）'
  return v.toFixed(2)
}
function valTone (s) {
  const v = Number(s && s.avgValence)
  if (!v) return ''
  return v < -0.2 ? 'bad' : v > 0.2 ? 'ok' : ''
}

// ---- 日 × 24 小时热力（U16-④）：接口给的是稀疏格，网格在这里补齐 ----
// 行的日期一律取自响应里的 fromDate/days，不由前端拿「今天」往前倒推：大屏 5 秒一轮，
// 只要在 23:59:58 跨了一次午夜，前端倒推的窗口就和后端 GROUP BY 的窗口错一天，
// 那种错位表现为「最后一行全空」，看着像数据缺失，实际是两套今天。
const WEEK_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
function dayKey (v) { return String(v == null ? '' : v).slice(0, 10) }
function splitDay (v) {
  const m = /^(\d{4})-(\d{1,2})-(\d{1,2})/.exec(dayKey(v))
  return m ? { y: Number(m[1]), mo: Number(m[2]), d: Number(m[3]) } : null
}
function shiftDay (day, delta) {
  const dt = new Date(Date.UTC(day.y, day.mo - 1, day.d + delta))
  return { y: dt.getUTCFullYear(), mo: dt.getUTCMonth() + 1, d: dt.getUTCDate() }
}
function isoOf (day) {
  return day.y + '-' + String(day.mo).padStart(2, '0') + '-' + String(day.d).padStart(2, '0')
}
function weekOf (day) {
  return WEEK_LABELS[new Date(Date.UTC(day.y, day.mo - 1, day.d)).getUTCDay()]
}

const heatRows = computed(() => {
  const board = heat.value || {}
  const cells = Array.isArray(board.cells) ? board.cells : []
  const idx = new Map()
  for (const r of cells) idx.set(dayKey(r.day) + '#' + Number(r.hour), r)
  const rows = []
  const from = splitDay(board.fromDate)
  const span = Math.max(1, Math.min(Number(board.days) || HEAT_DAYS, 90))
  if (from) {
    for (let i = 0; i < span; i++) {
      const d = shiftDay(from, i)
      rows.push({ key: isoOf(d), label: weekOf(d), sub: isoOf(d).slice(5) })
    }
  } else {
    for (const key of Array.from(new Set(cells.map((r) => dayKey(r.day)))).sort()) {
      rows.push({ key, label: key.slice(5), sub: '' })
    }
  }
  return rows.map((d) => ({
    key: d.key,
    label: d.label,
    sub: d.sub,
    cells: Array.from({ length: 24 }, (_, h) => {
      const r = idx.get(d.key + '#' + h)
      const cnt = r ? Number(r.cnt) : 0
      const val = r && r.avgValence !== null && r.avgValence !== undefined ? Number(r.avgValence) : null
      return {
        hour: h,
        cnt,
        val,
        title: d.key + ' ' + String(h).padStart(2, '0') + ':00 · ' + cnt + ' 条'
          + (val === null ? ' · 无效价' : ' · 平均效价 ' + val.toFixed(2))
      }
    })
  }))
})

const maxCnt = computed(() => heatRows.value.reduce(
  (a, r) => r.cells.reduce((m, c) => Math.max(m, c.cnt), a), 1))

const heatNote = computed(() => {
  const n = heatRows.value.length
  if (!n) return '等 /day-hour-heatmap 返回'
  return n + ' 天 × 24 小时 · 有记录 ' + ((heat.value && heat.value.cells) || []).length + ' 格'
})

function cellStyle (c) {
  if (!c.cnt) return { background: 'rgba(147,161,184,0.10)' }
  const alpha = Math.min(0.92, 0.22 + (c.cnt / Math.max(1, maxCnt.value)) * 0.62)
  if (c.val === null) return { background: 'rgba(127,167,196,' + alpha.toFixed(2) + ')' }
  if (c.val < -0.05) return { background: 'rgba(91,124,153,' + alpha.toFixed(2) + ')', boxShadow: 'inset 0 0 0 1px rgba(217,83,79,0.85)' }
  if (c.val > 0.05) return { background: 'rgba(127,179,166,' + alpha.toFixed(2) + ')', boxShadow: 'inset 0 0 0 1px rgba(242,193,78,0.9)' }
  return { background: 'rgba(147,161,184,' + alpha.toFixed(2) + ')' }
}

const trendNote = computed(() => {
  const n = daily.value.length
  if (!n) return '等 /emotion-board 返回'
  return '窗口 ' + TREND_DAYS + ' 天 · 其中 ' + n + ' 天有情绪记录 · 缺的日子不补 0'
})

function chart (key, el) {
  if (!el) return null
  if (!charts[key]) {
    charts[key] = echarts.init(el, null, { renderer: 'canvas' })
  } else if (charts[key].getDom() !== el) {
    charts[key].dispose()
    charts[key] = echarts.init(el, null, { renderer: 'canvas' })
  }
  return charts[key]
}

const WC_PALETTE = ['#F2C14E', '#7FB3A6', '#7FA7C4', '#E8B4A0', '#8E6BAF', '#7A8B3C', '#8A94A6', '#5B7C99']
function wcColor (word) {
  let h = 2166136261
  const w = String(word)
  for (let i = 0; i < w.length; i++) { h ^= w.codePointAt(i); h = (h * 16777619) >>> 0 }
  return WC_PALETTE[h % WC_PALETTE.length]
}

// ============================================================
// 手册 §9.3 要求的六张图在这里逐张对号：
//   #1 核心指标卡 -> cards（数字卡，不在本区）
//   #2 近 14 日活跃与情绪指数 双轴折线 -> drawTrend   （本次新增，接口此前在全仓 0 消费者）
//   #3 情绪分布 饼                 -> drawEmotion （本次新增；原先这里是词云，图型与手册不符）
//   #4 高频话题 词云               -> drawWord    （原先是 TOP10 横向柱状，本次与 #3 对调回手册口径）
//   #5 年级聚合 柱状 bar           -> drawGrade   （原先是环形饼图，本次改成 bar，GradeRow 注释本来就写柱状图）
//   #6 日 × 24 小时情绪热力        -> 模板里的 .hm DOM 网格（阶段 8 · U16-④ 补齐二维）
//      原先这里挂的是一维 /hour-heatmap：那条 SQL 只 GROUP BY HOUR，会把七天的凌晨两点压进同一格，
//      「工作日深夜塌陷、周末白天回升」和「整周都平稳」画出来是同一张图，而运营要的正是按星期几错开的节律。
//      现在读 /day-hour-heatmap（GROUP BY record_date, HOUR(created_at)），7 行 × 24 列。
//      仍然用 DOM 网格而不是 echarts HeatmapChart：为这一块引 heatmap + visualMap 两个组件要往
//      大屏的共享 bundle 里再加几十 KB，而 168 个 div 的渲染成本远低于一次 echarts 重绘。
// ============================================================

// 🔴 x 轴只画接口真返回的那些日期，不补零记录的日子。
// 后端 emotionDaily 是 GROUP BY record_date，那天没人打卡就不会有这一行；
// 而效价 0 在量表上的含义是「中性」，不是「没有人」。把缺日补成 0 会把
// 「那天没有样本」画成「那天全校情绪中性」——这是会被一眼问穿的假数据，所以宁可线断着。
function drawTrend () {
  const c = chart('trend', trendEl.value)
  if (!c) return
  const rows = daily.value
  if (!rows.length) { c.clear(); return }
  c.setOption({
    grid: { left: 58, right: 58, top: 42, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      formatter: (ps) => {
        const d = rows[ps[0].dataIndex]
        const out = [String(d.day)]
        for (const x of ps) {
          out.push(x.marker + x.seriesName + '：' + (x.value === null || x.value === undefined ? '无样本' : x.value))
        }
        out.push('当日情绪记录 ' + (d.recordCnt === null || d.recordCnt === undefined ? '—' : d.recordCnt) + ' 条')
        return out.join('<br/>')
      }
    },
    legend: { top: 6, textStyle: { color: '#93A1B8' } },
    xAxis: { type: 'category', data: rows.map((r) => r.day), axisLabel: { color: '#93A1B8' } },
    yAxis: [
      {
        type: 'value', name: '活跃人数', minInterval: 1,
        nameTextStyle: { color: '#93A1B8' }, axisLabel: { color: '#93A1B8' },
        splitLine: { lineStyle: { color: '#24304d' } }
      },
      {
        type: 'value', name: '效价', min: -1, max: 1, interval: 0.5,
        nameTextStyle: { color: '#93A1B8' }, axisLabel: { color: '#93A1B8' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '活跃人数', type: 'bar', barWidth: 22,
        itemStyle: { color: 'rgba(127,167,196,0.75)', borderRadius: [6, 6, 0, 0] },
        data: rows.map((r) => Number(r.activeCnt || 0))
      },
      {
        name: '平均效价', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 8,
        connectNulls: false,
        itemStyle: { color: '#F2C14E' }, lineStyle: { color: '#F2C14E', width: 2 },
        data: rows.map((r) => (r.avgValence === null || r.avgValence === undefined ? null : Number(r.avgValence))),
        markLine: {
          silent: true, symbol: 'none',
          lineStyle: { color: '#6f7f97', type: 'dashed' },
          label: { formatter: '效价 0（中性）', color: '#93A1B8', position: 'insideStartTop' },
          data: [{ yAxis: 0 }]
        }
      }
    ]
  }, true)
}

// 情绪标签的中文名与色板照抄用户端 frontend/src/components/EmotionPill.vue 与 EmotionView.vue，
// 两端不一致的话，同一个学生在用户端看到「喜悦」、管理员在大屏看到「joy」，答辩时会当场对不上。
// 🔴 只有 7 类：真源是后端 EmotionPrior.LABELS（CheckinRequest.java 明写七类，没有 surprise），
// 手册 §9.3 那句「8 类标签（含 neutral）」与真源不符 —— 这里以代码为准，不硬凑第 8 个扇区。
const EMOTION_NAMES = { joy: '喜悦', trust: '信任', anger: '愤怒', sadness: '难过', fear: '恐惧', disgust: '厌恶', neutral: '平静' }
const EMOTION_COLORS = { joy: '#F2C14E', trust: '#7FB3A6', anger: '#D9534F', sadness: '#5B7C99', fear: '#8E6BAF', disgust: '#7A8B3C', neutral: '#93A1B8' }
function emotionName (l) { return EMOTION_NAMES[l] || l }

function drawEmotion () {
  const c = chart('emotion', emotionEl.value)
  if (!c) return
  const rows = labels.value.filter((x) => Number(x.cnt) > 0)
  if (!rows.length) { c.clear(); return }
  c.setOption({
    tooltip: { trigger: 'item', formatter: (p) => p.name + '：' + p.value + ' 条（' + p.percent + '%）' },
    legend: { bottom: 0, textStyle: { color: '#93A1B8' }, type: 'scroll' },
    series: [{
      type: 'pie', radius: ['36%', '62%'], center: ['50%', '44%'],
      data: rows.map((x) => ({
        name: emotionName(x.label) + ' ' + x.label,
        value: Number(x.cnt),
        itemStyle: { color: EMOTION_COLORS[x.label] || '#7FA7C4' }
      })),
      label: { color: '#E8EEF7', formatter: '{d}%', fontSize: 11 },
      itemStyle: { borderColor: '#16203A', borderWidth: 2 }
    }]
  }, true)
}

function drawWord () {
  const c = chart('word', wordEl.value)
  if (!c) return
  const list = topics.value
  if (!list.length) { c.clear(); return }
  const max = Number(list[0].postCnt) || 1
  c.setOption({
    tooltip: { formatter: (x) => (x.name || '') + ' 发帖 ' + ((x.data && x.data.cnt) || x.value[1]) + ' 篇' },
    series: [{
      type: 'custom',
      renderItem: 'wordCloud',
      // 这三行是用户端情绪档案页真浏览器取证时踩过的坑，管理端照抄，别再退回旧形状：
      // ① 仓库里这份是 @echarts-x/custom-word-cloud 1.0.1，参数只从 itemPayload 里读；
      // ② registerCustomSeries 只注册渲染函数，不改默认坐标系，不写 view 会抛 xAxis "0" not found；
      // ③ 布局算的是像素，view 坐标系才拿得到容器宽高。
      coordinateSystem: 'view',
      itemPayload: {
        left: 0, top: 0, right: 0, bottom: 0,
        shape: 'circle', sizeRange: [14, 58], rotationRange: [0, 0], gridSize: 8, shrinkToFit: true
      },
      data: list.map((x) => ({
        name: '#' + x.name,
        value: [x.name, Math.max(1, Math.round((Number(x.postCnt) / max) * 100))],
        cnt: Number(x.postCnt),
        itemStyle: { color: wcColor(x.name) }
      }))
    }]
  }, true)
}

function drawGrade () {
  const c = chart('grade', gradeEl.value)
  if (!c) return
  const rows = grades.value
  if (!rows.length) { c.clear(); return }
  c.setOption({
    grid: { left: 46, right: 18, top: 26, bottom: 46 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: (ps) => ps[0].name + '：' + ps[0].value + ' 人' },
    xAxis: { type: 'category', data: rows.map((g) => gradeName(g.grade)), axisLabel: { color: '#93A1B8', interval: 0, rotate: 32 } },
    yAxis: {
      type: 'value', minInterval: 1, axisLabel: { color: '#93A1B8' },
      splitLine: { lineStyle: { color: '#24304d' } }
    },
    series: [{
      type: 'bar', barWidth: '54%',
      itemStyle: { color: '#7FB3A6', borderRadius: [6, 6, 0, 0] },
      label: { show: true, position: 'top', color: '#E8EEF7' },
      data: rows.map((g) => Number(g.cnt))
    }]
  }, true)
}
const GRADE_NAMES = { FRESHMAN: '大一', SOPHOMORE: '大二', JUNIOR: '大三', SENIOR: '大四', GRADUATE: '研究生', OTHER: '未填写' }
function gradeName (g) { return GRADE_NAMES[g] || g || '未知' }


function drawAi () {
  const c = chart('ai', aiEl.value)
  if (!c) return
  const rows = ai.value
  const daysAsc = [...new Set(rows.map((r) => r.day))].sort()
  const scenes = [...new Set(rows.map((r) => r.scene))]
  const sumOf = (day, scene, field) => rows
    .filter((r) => r.day === day && (scene === null || r.scene === scene))
    .reduce((a, r) => a + Number(r[field] || 0), 0)
  c.setOption({
    grid: { left: 74, right: 74, top: 40, bottom: 34 },
    tooltip: { trigger: 'axis' },
    legend: { top: 6, textStyle: { color: '#93A1B8' }, data: scenes.map(sceneText) },
    xAxis: { type: 'category', data: daysAsc, axisLabel: { color: '#93A1B8', formatter: (v) => String(v).slice(5) } },
    yAxis: [
      { type: 'value', name: '词元', nameTextStyle: { color: '#93A1B8' }, axisLabel: { color: '#93A1B8' }, splitLine: { lineStyle: { color: '#24304d' } } },
      { type: 'value', name: '失败次', nameTextStyle: { color: '#93A1B8' }, axisLabel: { color: '#93A1B8' }, splitLine: { show: false } }
    ],
    series: [
      ...scenes.map((sc) => ({
        name: sceneText(sc), type: 'line', smooth: true, symbolSize: 6,
        data: daysAsc.map((d) => sumOf(d, sc, 'tokens'))
      })),
      {
        name: '调用失败', type: 'bar', yAxisIndex: 1, barWidth: 10,
        itemStyle: { color: 'rgba(217,83,79,0.7)' },
        data: daysAsc.map((d) => sumOf(d, null, 'fail_cnt'))
      }
    ]
  }, true)
}
const SCENE_NAMES = { emotion: '情绪识别', chat: '陪伴对话', summary: '摘要', risk: '风险判断', reply: '回复建议' }
function sceneText (s) { return SCENE_NAMES[s] || s || '未知场景' }

function drawAll () {
  drawTrend(); drawEmotion(); drawWord(); drawGrade(); drawAi()
  for (const k of Object.keys(charts)) charts[k].resize()
}

async function load () {
  if (paused.value) return
  const started = Date.now()
  const jobs = [
    dashboardStats().then((d) => { stats.value = d; bad.stats = '' }, (e) => { bad.stats = e.message }),
    emotionBoard(TREND_DAYS).then((d) => { daily.value = (d && d.daily) || []; bad.trend = '' }, (e) => { bad.trend = '活跃与效价趋势失败：' + e.message }),
    emotionLabels(days.value).then((d) => { labels.value = d || []; bad.emotion = '' }, (e) => { bad.emotion = '情绪分布失败：' + e.message }),
    dayHourHeatmap(HEAT_DAYS).then((d) => { heat.value = d || null; bad.hours = '' }, (e) => { bad.hours = '热力数据失败：' + e.message }),
    hotTopics(20).then((d) => { topics.value = d || []; bad.word = '' }, (e) => { bad.word = '话题词云失败：' + e.message }),
    gradeBoard().then((d) => { grades.value = d || []; bad.grade = '' }, (e) => { bad.grade = '年级数据失败：' + e.message }),
    aiUsage(7).then((d) => { ai.value = d || []; bad.ai = '' }, (e) => { bad.ai = 'AI 用量数据失败：' + e.message })
  ]
  await Promise.all(jobs)
  lastCost.value = Date.now() - started
  const failed = jobs.filter((_, i) => [bad.stats, bad.trend, bad.emotion, bad.hours, bad.word, bad.grade, bad.ai][i]).length
  if (failed) failCnt.value += failed
  lastOkAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  await nextTick()
  drawAll()
}

function tickClock () {
  clock.value = new Date().toLocaleString('zh-CN', { hour12: false })
}
function onVisibility () {
  paused.value = document.hidden
  if (!paused.value) load()
}

onMounted(async () => {
  tickClock()
  clockTimer = setInterval(tickClock, 1000)
  document.addEventListener('visibilitychange', onVisibility)
  resizeHandler = () => { vw.value = window.innerWidth; vh.value = window.innerHeight; drawAll() }
  window.addEventListener('resize', resizeHandler)
  await load()
  timer = setInterval(load, REFRESH_MS)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (clockTimer) clearInterval(clockTimer)
  document.removeEventListener('visibilitychange', onVisibility)
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  for (const k of Object.keys(charts)) charts[k].dispose()
})
</script>

<style scoped>
.screen-wrap { position: fixed; inset: 0; background: #0b1220; overflow: hidden; }
.screen {
  /* box-sizing 必须写死：这一层是 1920 定版画布，padding 也算在 1920 里。不写的话实际占位 1972px，
     乘 scale 后右边缘溢出版口，被 .screen-wrap 的 overflow:hidden 切掉（23 点热力格 + 返回链接）。 */
  box-sizing: border-box;
  transform-origin: top left; position: absolute; left: 0; top: 0;
  display: flex; flex-direction: column; gap: 14px; padding: 20px 26px;
  background: radial-gradient(1200px 600px at 12% 0%, #17233f 0%, #0b1220 62%);
  color: #E8EEF7;
}
.hd { display: flex; align-items: flex-end; justify-content: space-between; }
.title { display: flex; align-items: center; gap: 14px; }
.mark { font-size: 40px; }
h1 { margin: 0; font-size: 30px; letter-spacing: 2px; }
.title p { margin: 4px 0 0; font-size: 14px; color: #93A1B8; }
.hd-right { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.clock { font-size: 22px; font-variant-numeric: tabular-nums; color: #F0876B; }
.beat { font-size: 13px; color: #7FB3A6; }
.beat.off { color: #93A1B8; }
.cards { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.card { background: rgba(22, 32, 58, 0.9); border: 1px solid #24304d; border-radius: 12px; padding: 12px 14px; }
.card .k { font-size: 14px; color: #93A1B8; }
.card .v { font-size: 32px; font-weight: 700; margin: 4px 0; font-variant-numeric: tabular-nums; }
.card .v.warn { color: #F2C14E; }
.card .v.bad { color: #D9534F; }
.card .v.ok { color: #7FB3A6; }
.card .s { font-size: 11px; color: #6f7f97; line-height: 1.5; }
.grid { flex: 1; display: grid; grid-template-columns: repeat(4, 1fr); grid-template-rows: 1fr 1fr; gap: 12px; }
.panel { background: rgba(22, 32, 58, 0.9); border: 1px solid #24304d; border-radius: 12px; padding: 10px 12px; display: flex; flex-direction: column; min-height: 0; }
.panel.wide { grid-column: span 2; }
.ph { display: flex; justify-content: space-between; align-items: baseline; font-size: 14px; font-weight: 700; margin-bottom: 6px; gap: 10px; }
.ph .dim { font-weight: 400; font-size: 11px; color: #6f7f97; }
.chart { flex: 1; min-height: 0; }
/* 7×24 热力：第一列是星期标签，行高由模板里的 gridTemplateRows 内联给出（跟着 heatRows.length 走）。 */
.hm { flex: 1; min-height: 0; display: grid; grid-template-columns: 46px repeat(24, minmax(0, 1fr)); gap: 3px; }
.hm-ruler { font-size: 9px; color: #6f7f97; text-align: center; align-self: end; line-height: 1; }
.hm-day { display: flex; flex-direction: column; align-items: flex-end; justify-content: center; line-height: 1.15; padding-right: 4px; }
.hm-day b { font-size: 11px; color: #C6D2E4; font-weight: 700; }
.hm-day i { font-style: normal; font-size: 9px; color: #6f7f97; }
.hcell { border-radius: 3px; min-height: 10px; }
.legend { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #93A1B8; margin-top: 6px; }
.legend .dim { color: #6f7f97; margin-left: 8px; }
.lg { width: 16px; height: 10px; border-radius: 2px; display: inline-block; }
.lg.neg { background: rgba(217, 83, 79, 0.8); }
.lg.mid { background: rgba(147, 161, 184, 0.6); }
.lg.pos { background: rgba(242, 193, 78, 0.8); }
.err { color: #D9534F; font-size: 12px; }
.ft { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: #6f7f97; }
.back { color: #7FA7C4; text-decoration: none; }
</style>