<template>
  <div class="logs">
    <!-- A9 操作日志：谁、什么时候、对什么对象、干成了没有。需求 §12 规范 5 与 FR8.4 的唯一取证入口。 -->
    <div class="mi-card bar">
      <el-form inline @submit.prevent>
        <el-form-item label="操作人">
          <el-input v-model="f.operatorId" maxlength="10" style="width: 120px"
                    placeholder="operatorId" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item label="动作码">
          <el-select v-model="f.action" clearable filterable placeholder="全部" style="width: 210px" @change="reload">
            <el-option v-for="a in OP_ACTIONS" :key="a" :label="a + ' · ' + (ACTION_LABEL[a] || '—')" :value="a" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="f.result" clearable placeholder="全部" style="width: 130px" @change="reload">
            <el-option v-for="r in OP_RESULTS" :key="r" :label="r + ' · ' + RESULT_LABEL[r]" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker v-model="f.from" type="datetime" placeholder="起" style="width: 180px" @change="reload" />
          <el-date-picker v-model="f.to" type="datetime" placeholder="止" style="width: 180px" @change="reload" />
        </el-form-item>
        <el-form-item>
          <el-button :loading="busy" @click="reload">查询</el-button>
          <el-button @click="reset">清空</el-button>
          <el-button text :type="mine ? 'primary' : ''" @click="toggleMine">只看我的操作</el-button>
        </el-form-item>
      </el-form>
      <p class="dim">
        四个筛选位全部由服务端校验：<code>action</code> 不在 18 个动作码白名单内、<code>result</code> 不在
        SUCCESS/FAIL/DENIED 内，都是当场 10001，<b>而不是返回一张空表</b>——审计页「筛错了但看起来一切正常」
        是最难被发现的一类错误。时间区间出参是 <code>LocalDateTime</code>，必须送
        <code>yyyy-MM-ddTHH:mm:ss</code>，所以这里统一走 <code>toLocalIso()</code> 而不是直接丢 Date 对象。
        另有一条不对称是刻意的：<b>查日志不写日志</b>（否则一次排查能把表写满），<b>导出日志要写日志</b>
        （一次 CSV 导出等于把一批数据搬出平台，按 PIPL 是一次数据处理活动，谁导的、几天范围、多少行都得留下）。
      </p>
    </div>

    <!-- A6 深链落地的说明：这条链是「留痕写了 ≠ 留痕存在」的产品修复，必须把来源讲清楚。 -->
    <el-alert v-if="deepHint" class="alert" :closable="false" type="info" show-icon>
      <template #title>{{ deepHint }}</template>
    </el-alert>

    <el-alert v-if="notice" :title="notice" :type="noticeTone" show-icon closable class="alert" @close="notice = ''" />

    <div class="mi-card head">
      <span class="k">全站累计解匿</span>
      <span class="v">{{ revealTotal === null ? '—' : fmtNum(revealTotal) }}</span>
      <span class="dim">
        这条数只统计 <code>REVEAL_ANONYMOUS + SUCCESS</code>（SQL 见
        <code>AdminOpLogMapper.countReveals</code>），所以它比下面这张「最近十条」少——
        被拒绝的解匿尝试在这里计数为 0，但每一行 DENIED 都查得到。答辩报的是前者，取证看的是后者。
      </span>
      <el-button size="small" text @click="onlyReveal">只看解匿动作</el-button>
      <el-button size="small" text @click="onlyDenied">只看被拒绝的解匿</el-button>
    </div>

    <div class="mi-card reveal">
      <div class="sec-title">
        <span>最近十条解匿留痕</span>
        <el-button size="small" text type="primary" @click="goUsers">要解匿去 A6 档案页，这里只负责取证</el-button>
        <span class="dim">读自 <code>GET /api/admin/logs/reveals</code>，服务端固定取 page=1 size=10 的 REVEAL_ANONYMOUS</span>
      </div>
      <p v-if="revealErr" class="err">解匿留痕读取失败：{{ revealErr }}</p>
      <el-table v-else :data="revealRecent" size="small" max-height="200" empty-text="库里还没有解匿记录（一次都没解过才是正常的）">
        <el-table-column label="时刻" width="120">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="130">
          <template #default="{ row }">#{{ row.operatorId }} <el-tag size="small" :type="row.operatorRole === 'SUPER' ? 'danger' : 'warning'">{{ row.operatorRole }}</el-tag></template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }"><el-tag size="small" :type="statusTone(row.result)">{{ row.result }}</el-tag></template>
        </el-table-column>
        <el-table-column label="对象" width="120" prop="target" />
        <el-table-column label="理由与产出" min-width="260" show-overflow-tooltip prop="detail" />
      </el-table>
    </div>

    <div class="mi-card chart">
      <div class="sec-title">
        <span>动作码分布（最近 {{ statsDays }} 天）</span>
        <el-select v-model="statsDays" size="small" style="width: 110px" @change="loadStats">
          <el-option :value="7" label="7 天" /><el-option :value="30" label="30 天" /><el-option :value="90" label="90 天" />
        </el-select>
        <span class="dim">
          一次 GROUP BY 出全部，前端不再逐个动作发请求。
          注意接口出参的键名是 <code>{status, cnt}</code>——那是复用 <code>StatusCountRow</code> 留下的历史包袱，
          <b>status 里装的其实是动作码</b>，本页按实测形状取值，不写成 <code>row.action</code>。
        </span>
      </div>
      <p v-if="statsErr" class="err">统计读取失败：{{ statsErr }}</p>
      <div ref="chartEl" class="canvas"></div>
      <p v-if="!statsErr && !stats.length" class="dim">这个区间内没有任何管理端操作。</p>
    </div>

    <div class="mi-card list">
      <el-table :data="rows" size="small" height="360" v-loading="busy" empty-text="没有符合条件的留痕">
        <el-table-column prop="id" label="ID" width="72" />
        <el-table-column label="时刻" width="120">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="120">
          <template #default="{ row }">
            #{{ row.operatorId }}<span v-if="isMe(row)" class="me">（我）</span>
            <div class="dim2">{{ row.operatorRole }}</div>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="170">
          <template #default="{ row }">
            {{ row.action }}<div class="dim2">{{ ACTION_LABEL[row.action] || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="94">
          <template #default="{ row }"><el-tag size="small" :type="statusTone(row.result)">{{ row.result }}</el-tag></template>
        </el-table-column>
        <el-table-column label="对象" min-width="150">
          <template #default="{ row }">{{ row.target || '全局' }}<div class="dim2" v-if="row.targetId">#{{ row.targetId }}</div></template>
        </el-table-column>
        <el-table-column label="详情" min-width="300" show-overflow-tooltip prop="detail" />
        <el-table-column label="耗时" width="86">
          <template #default="{ row }">{{ row.costMs ? row.costMs + ' ms' : '—' }}</template>
        </el-table-column>
        <el-table-column label="来源" width="140">
          <template #default="{ row }">{{ row.ip || '—' }}<div class="dim2">{{ row.userAgent || '—' }}</div></template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <span class="dim">
          共 {{ page.total }} 条留痕 · 第 {{ page.page }} 页。
          <template v-if="!rows.length && !listErr">这个结果为 0 不代表「没发生过」：先看上方筛选是否写错，非法值已被服务端拒绝；合法值筛出 0 条才说明库里真的没有。</template>
          <template v-else>列表按 id 倒序（最新在前），后台要看总数所以走页码分页而不是游标。</template>
        </span>
        <el-pagination small layout="prev, pager, next, sizes" :total="page.total"
                       :page-size="page.size" :current-page="page.page" :page-sizes="[10, 20, 50]"
                       @current-change="onPage" @size-change="onSize" />
      </div>
    </div>

    <div class="mi-card export">
      <div class="sec-title">
        <span>报表导出（CSV · UTF-8 BOM · 防公式注入）</span>
        <el-select v-model="exportDays" size="small" style="width: 120px">
          <el-option :value="7" label="最近 7 天" /><el-option :value="30" label="最近 30 天" /><el-option :value="90" label="最近 90 天" />
        </el-select>
      </div>
      <div class="acts">
        <el-button :loading="expBusy === 'tickets'" @click="doExport('tickets')">危机工单</el-button>
        <el-button :loading="expBusy === 'ai'" @click="doExport('ai')">AI 用量与费用</el-button>
        <el-button :loading="expBusy === 'logs'" @click="doExport('logs')">当前筛选的操作日志</el-button>
        <span class="dim">
          日志导出跟随上面的筛选条件（含 {{ f.action || '全部动作' }} / {{ f.result || '全部结果' }}），
          最多 5000 行；<b>导出动作本身也会留下一条 EXPORT_CSV</b>，所以下完一次记得回来看这张表——
          它自己会多出一行，这是设计，不是 bug。
        </span>
      </div>
      <p class="dim">
        导出走的是带 Authorization 的同一条 axios 实例，不是 <code>&lt;a href&gt;</code>：裸链接不带令牌会拿到 401，
        浏览器仍然会「下载成功」，只是存下来的是一个打不开的 JSON 错误体。
        文件名从 <code>Content-Disposition</code> 取，取不到才本地兜底。
      </p>
    </div>

    <!-- 推荐重算台账（任务 U16-①）：rec_run_log 是唯一能说清「今天到底跑没跑成」的地方。 -->
    <div class="mi-card rechist">
      <div class="sec-title">
        <span>推荐重算台账</span>
        <el-button size="small" text :loading="runsBusy" @click="loadRuns">刷新</el-button>
        <el-button size="small" text type="primary" @click="goWorkbench">去 A3 工作台触发重算</el-button>
        <span class="dim">读自 <code>GET /api/admin/rec/runs</code>，数据源 <code>rec_run_log</code>：定时与手动各记一行，一次调用恰好一行</span>
      </div>
      <p v-if="runsErr" class="err">台账读取失败：{{ runsErr }}</p>
      <template v-else>
        <el-table :data="runs" size="small" max-height="260"
                  empty-text="台账为空。这张表是 2026-10-08 才建的，之前的重算没有逐次留痕——空表不等于「从没跑过」">
          <el-table-column label="开始时刻" width="150">
            <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="触发" width="72">
            <template #default="{ row }">
              <el-tag size="small" :type="row.triggerType === 'manual' ? 'warning' : 'info'">
                {{ row.triggerType === 'manual' ? '手动' : '定时' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="112">
            <template #default="{ row }">
              <el-tag size="small" :type="runTone(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="92">
            <template #default="{ row }">{{ row.durationMs == null ? '—' : row.durationMs + ' ms' }}</template>
          </el-table-column>
          <el-table-column label="模式" width="120">
            <template #default="{ row }">{{ row.mode || '—' }}</template>
          </el-table-column>
          <el-table-column label="行数（质量/话题/相似/结果）" min-width="190">
            <template #default="{ row }">
              {{ num(row.qualityRows) }} / {{ num(row.topicRows) }} / {{ num(row.similarityRows) }} / {{ num(row.resultRows) }}
              <div class="dim2" v-if="row.userCnt != null">覆盖 {{ fmtNum(row.userCnt) }} 人</div>
            </template>
          </el-table-column>
          <el-table-column label="通道占比 / 错误" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.errorText" class="err">{{ row.errorText }}</span>
              <span v-else class="dim2">{{ row.channelShare || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
        <p class="dim">
          共 {{ runsTotal == null ? '—' : runsTotal }} 次重算<template v-if="runsCounts">：
          <span v-for="(v, k) in runsCounts" :key="k">{{ k }} {{ v }} · </span></template>
          <template v-if="runsTruncated">本次只显示最近 {{ runs.length }} 行，还有更早的没显示（服务端上限 200 行）。</template>
          台账记的是<b>已经发生的事实</b>：跑完才落一行，所以它不能当进度条用，正在跑的那次要回 A3 看
          <code>/api/admin/rec/status</code>。成功与失败都写行是刻意的——一条定时任务连续三天空跑但没人知道，
          比它跑挂一次更难查；被并发挡掉的那次记 SKIPPED_BUSY，是为了让「今天没更新」有三种可区分的解释。
          另有一条口径要写明白：这张表<b>不参与注销与导出</b>（登记在隐私注册表 Link.NONE），
          它只有运行统计没有个体数据，把它接进用户数据导出反而是给导出添噪声。
        </p>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  OP_ACTIONS, OP_RESULTS,
  opLogPage, opLogActionStats, opLogReveals, recRuns,
  exportTicketsCsv, exportAiUsageCsv, exportOpLogsCsv, saveBlob
} from '@/api/admin'
import { errText, fmtNum, fmtTime, statusTone, toLocalIso } from '@/utils/format'
import { useAdminUserStore } from '@/stores/adminUser'

// 按需引入：与 A2 大屏同一套口径，避免把 echarts 全量入口拖进这一页。
echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

/** 动作码的中文注解。键只能来自 OP_ACTIONS，多余键不会有、缺键界面上显示「—」而不是编一个名字。 */
const ACTION_LABEL = {
  AUDIT_CLAIM: '认领审核任务', AUDIT_PASS: '审核通过', AUDIT_REJECT: '审核驳回',
  TICKET_HANDLE: '处置危机工单', APPEAL_HANDLE: '裁定申诉', REPORT_HANDLE: '办结举报',
  POST_TAKEDOWN: '帖子下架', POST_RESTORE: '帖子恢复', POST_TOP: '帖子置顶', POST_FEATURE: '帖子加精',
  MUTE_USER: '禁言用户', UNMUTE_USER: '解除禁言', BAN_USER: '封禁账号', RESTORE_USER: '恢复账号',
  REVEAL_ANONYMOUS: '解匿名身份', UPDATE_CONFIG: '修改系统参数', EXPORT_CSV: '导出 CSV 报表',
  READ_PM: '读取私信内容'
}
const RESULT_LABEL = { SUCCESS: '成功', FAIL: '执行失败', DENIED: '被拒绝' }

const route = useRoute()
const router = useRouter()
const admin = useAdminUserStore()

const f = reactive({ operatorId: '', action: '', result: '', from: null, to: null })
const page = reactive({ page: 1, size: 20, total: 0 })
const rows = ref([])
const busy = ref(false)
const listErr = ref('')
const notice = ref('')
const noticeTone = ref('info')
const deepHint = ref('')

const revealTotal = ref(null)
const revealRecent = ref([])
const revealErr = ref('')

const stats = ref([])
const statsDays = ref(30)

// 推荐重算台账（U16-①）。runsTotal/runsCounts/runsTruncated 三个数都来自服务端同一个响应，
// 不在前端自己数 runs.length 当总数：limit 截断时这两个数会不一样，
// 而把「共 3 次」和「显示 3 行」混为一谈正是台账最容易骗人的地方。
const runs = ref([])
const runsBusy = ref(false)
const runsErr = ref('')
const runsTotal = ref(null)
const runsCounts = ref(null)
const runsTruncated = ref(false)
const statsErr = ref('')
const chartEl = ref(null)
let chart = null

const exportDays = ref(30)
const expBusy = ref('')

const myId = computed(() => (admin.profile && admin.profile.id) || null)
const mine = computed(() => myId.value && String(f.operatorId) === String(myId.value))

function say (text, tone) {
  notice.value = text
  noticeTone.value = tone || 'info'
}
function isMe (row) { return myId.value && String(row.operatorId) === String(myId.value) }

/**
 * 读深链参数：A6 的「ADMIN 越权解匿被拒之后，去看那一行 DENIED」靠的就是这三个键。
 * 不在这里做白名单校验——校验交给服务端，越界的值会当场 10001，界面把原因显示出来，
 * 这既是「宁可报错不给空表」的产品口径，也顺手把那条规则演示了一次。
 */
function readQuery () {
  const q = route.query || {}
  const parts = []
  if (q.action) { f.action = String(q.action); parts.push('动作码 ' + f.action) }
  if (q.result) { f.result = String(q.result); parts.push('结果 ' + f.result) }
  if (q.operatorId) { f.operatorId = String(q.operatorId); parts.push('操作人 #' + f.operatorId) }
  if (parts.length) {
    deepHint.value = '从 A6 用户管理带条件跳入：' + parts.join('、')
      + '。这一条链的用途是核对「特权操作是否真的留痕」——按钮点了、接口拒了、库里那一行是否写进去了，'
      + '三个环节只有最后这一个能当证据。'
  } else {
    deepHint.value = ''
  }
}

function params () {
  const p = { page: page.page, size: page.size }
  if (f.operatorId) p.operatorId = f.operatorId
  if (f.action) p.action = f.action
  if (f.result) p.result = f.result
  const from = toLocalIso(f.from)
  const to = toLocalIso(f.to)
  if (from) p.from = from
  if (to) p.to = to
  return p
}

async function loadList () {
  busy.value = true
  listErr.value = ''
  try {
    const r = await opLogPage(params())
    rows.value = r.list || []
    page.total = r.total || 0
    page.page = r.page || page.page
    page.size = r.size || page.size
  } catch (e) {
    rows.value = []
    page.total = 0
    listErr.value = errText(e)
    say('留痕列表读取失败：' + listErr.value, 'error')
  }
  busy.value = false
}

async function loadReveals () {
  try {
    const r = await opLogReveals()
    revealTotal.value = r.total
    revealRecent.value = r.recent || []
    revealErr.value = ''
  } catch (e) {
    revealTotal.value = null
    revealRecent.value = []
    revealErr.value = errText(e)
  }
}

async function loadStats () {
  try {
    const d = await opLogActionStats(statsDays.value)
    stats.value = d || []
    statsErr.value = ''
  } catch (e) {
    stats.value = []
    statsErr.value = errText(e)
  }
  await nextTick()
  renderChart()
}

async function loadRuns () {
  runsBusy.value = true
  try {
    const r = await recRuns(20)
    runs.value = r.runs || []
    runsTotal.value = r.totalRuns == null ? null : Number(r.totalRuns)
    runsCounts.value = r.counts && Object.keys(r.counts).length ? r.counts : null
    runsTruncated.value = !!r.truncated
    runsErr.value = ''
  } catch (e) {
    runs.value = []
    runsTotal.value = null
    runsCounts.value = null
    runsTruncated.value = false
    runsErr.value = errText(e)
  }
  runsBusy.value = false
}

function runTone (s) {
  return s === 'SUCCESS' ? 'success' : s === 'FAILED' ? 'danger' : s === 'SKIPPED_BUSY' ? 'warning' : 'info'
}
function num (v) { return v == null ? '—' : fmtNum(v) }

function renderChart () {
  if (!chartEl.value) return
  if (!chart || chart.isDisposed()) chart = echarts.init(chartEl.value)
  const data = stats.value.slice().reverse()
  chart.setOption({
    grid: { left: 150, right: 24, top: 10, bottom: 24 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: '#eee6dc' } } },
    yAxis: {
      type: 'category',
      data: data.map((r) => r.status + (ACTION_LABEL[r.status] ? ' · ' + ACTION_LABEL[r.status] : '')),
      axisLabel: { fontSize: 11, color: '#6b6458' }
    },
    series: [{
      type: 'bar',
      data: data.map((r) => Number(r.cnt) || 0),
      barMaxWidth: 14,
      itemStyle: { color: '#f0876b', borderRadius: [0, 6, 6, 0] },
      label: { show: true, position: 'right', fontSize: 11, color: '#6b6458' }
    }]
  }, true)
}

function onResize () { if (chart && !chart.isDisposed()) chart.resize() }

async function reload () {
  page.page = 1
  await loadList()
}

function reset () {
  f.operatorId = ''
  f.action = ''
  f.result = ''
  f.from = null
  f.to = null
  deepHint.value = ''
  page.page = 1
  loadList()
}

function toggleMine () {
  f.operatorId = mine.value ? '' : String(myId.value || '')
  if (!myId.value) say('本地拿不到当前管理员 id，无法只看我的操作', 'warning')
  reload()
}

function onlyReveal () { f.action = 'REVEAL_ANONYMOUS'; f.result = ''; reload() }
function onlyDenied () { f.action = 'REVEAL_ANONYMOUS'; f.result = 'DENIED'; reload() }

function onPage (p) { page.page = p; loadList() }
function onSize (s) { page.size = s; page.page = 1; loadList() }

const TARGETS = {
  tickets: { name: '危机工单', call: () => exportTicketsCsv(exportDays.value) },
  ai: { name: 'AI 用量', call: () => exportAiUsageCsv(exportDays.value) },
  logs: { name: '操作日志', call: () => exportOpLogsCsv({ operatorId: f.operatorId || undefined, action: f.action || undefined, result: f.result || undefined, days: exportDays.value }) }
}

async function doExport (key) {
  const t = TARGETS[key]
  expBusy.value = key
  try {
    const { blob, filename } = await t.call()
    saveBlob(blob, filename)
    const kb = (blob.size / 1024).toFixed(1)
    say(t.name + '已导出：' + filename + '（' + kb + ' KB）。这次导出自己也写了一条 EXPORT_CSV 留痕，列表已刷新——往下翻就能看到「谁导了什么」。', 'success')
    await Promise.all([loadList(), loadReveals(), loadStats()])
  } catch (e) {
    say(t.name + '导出失败：' + errText(e), 'error')
  }
  expBusy.value = ''
}

function goUsers () { router.push('/users') }
// 重算按钮不在这一页：A3 工作台的推荐卡才有触发口（带 status 回显），这里只负责取证。
function goWorkbench () { router.push('/dashboard') }

onMounted(async () => {
  readQuery()
  await Promise.all([loadList(), loadReveals(), loadStats(), loadRuns()])
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart && !chart.isDisposed()) chart.dispose()
})
</script>

<style scoped>
.logs { display: flex; flex-direction: column; gap: 14px; }
.bar .dim { margin: 4px 0 0; }
.alert { margin: 0; }
.head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.head .k { font-size: 13px; color: var(--mi-text-dim); }
.head .v { font-size: 22px; font-weight: 700; color: var(--mi-primary); }
.head .dim { flex: 1 1 320px; }
.sec-title { display: flex; align-items: center; gap: 10px; font-size: 14px; font-weight: 700; margin-bottom: 10px; flex-wrap: wrap; }
.sec-title .dim { font-weight: 400; }
.reveal .sec-title span:first-child, .chart .sec-title span:first-child, .export .sec-title span:first-child, .rechist .sec-title span:first-child { color: var(--mi-primary); }
.canvas { width: 100%; height: 320px; }
.pager { display: flex; align-items: center; justify-content: space-between; margin-top: 10px; gap: 12px; }
.pager .dim { flex: 1; }
.me { color: var(--mi-primary); font-size: 11px; margin-left: 2px; }
.acts { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
.err { color: var(--el-color-danger); font-size: 12px; margin: 6px 0; }
.dim { font-size: 12px; color: var(--mi-text-dim); line-height: 1.7; }
.dim2 { font-size: 11px; color: var(--mi-text-dim); }
code { background: rgba(240, 135, 107, 0.1); padding: 0 3px; border-radius: 3px; }
</style>