<template>
  <div class="dash">
    <div class="hd">
      <h2 class="h">运营工作台</h2>
      <div class="tools">
        <span class="dim">上次刷新 {{ lastAt }} · 数据源 /api/admin/dashboard/stats + 四个 pending-count</span>
        <el-button size="small" :loading="busy" @click="loadAll">重新拉取</el-button>
        <el-button size="small" type="primary" plain @click="router.push('/screen')">运行大屏</el-button>
      </div>
    </div>

    <el-alert v-if="fatal" :title="fatal" type="error" show-icon :closable="false" class="alert" />

    <el-row :gutter="14">
      <el-col v-for="t in todos" :key="t.key" :xs="24" :sm="12" :lg="6">
        <div class="mi-card todo" :class="t.tone" @click="router.push(t.to)">
          <div class="label">{{ t.label }}</div>
          <div class="value">{{ t.value }}<span class="unit">{{ t.unit }}</span></div>
          <div class="desc">{{ t.desc }}</div>
          <div class="go">{{ t.cta }} →</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="14" class="row">
      <el-col :xs="24" :lg="14">
        <div class="mi-card">
          <div class="rt">
            <span>SLA 已超时工单（按最久未处理排序，取前 8 条）</span>
            <el-tag size="small" :type="overdue.length ? 'danger' : 'success'">
              超时 {{ board ? fmtNum(board.overdueCnt) : '—' }} 条 / 最久 {{ worstText }}
            </el-tag>
          </div>
          <el-table v-if="overdue.length" :data="overdue" size="small" @row-click="goTicket">
            <el-table-column prop="id" label="工单" width="70" />
            <el-table-column prop="level" label="级别" width="70">
              <template #default="{ row }"><el-tag size="small" :type="statusTone(row.level)">{{ row.level }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="slaAt" label="SLA 截止" width="130">
              <template #default="{ row }">{{ fmtTime(row.slaAt) }}</template>
            </el-table-column>
            <el-table-column prop="evidenceText" label="证据摘录" show-overflow-tooltip />
          </el-table>
          <p v-else-if="!bad.overdue" class="dim">当前没有超时工单。</p>
          <p v-else class="err">{{ bad.overdue }}</p>
        </div>
      </el-col>

      <el-col :xs="24" :lg="10">
        <div class="mi-card">
          <div class="rt"><span>审核队列分布（audit_task 五态）</span></div>
          <el-table v-if="auditCounts.length" :data="auditCounts" size="small">
            <el-table-column prop="status" label="状态" width="140">
              <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="cnt" label="条数" width="90">
              <template #default="{ row }">{{ fmtNum(row.cnt) }}</template>
            </el-table-column>
            <el-table-column label="占队列">
              <template #default="{ row }">
                <el-progress :percentage="pct(row.cnt)" :stroke-width="10" :show-text="false" />
                <span class="dim">{{ pct(row.cnt) }}%</span>
              </template>
            </el-table-column>
          </el-table>
          <p v-else-if="!bad.audit" class="dim">审核队列计数为空——这不代表「已清空」，PENDING=0 时才可以说清空。</p>
          <p v-else class="err">{{ bad.audit }}</p>

          <div class="rt sub">人均处理量（audit/assignee-stats）</div>
          <el-table v-if="assignees.length" :data="assignees" size="small">
            <el-table-column prop="assigneeId" label="审核员" width="80" />
            <el-table-column prop="cnt" label="已裁决" width="80" />
            <el-table-column label="平均耗时">
              <template #default="{ row }">{{ avgSec(row.avgSeconds) }}</template>
            </el-table-column>
            <el-table-column prop="overdueCnt" label="超时" width="60" />
          </el-table>
          <p v-else-if="!bad.assignee" class="dim">还没有人裁决过任何一条任务。</p>
          <p v-else class="err">{{ bad.assignee }}</p>
        </div>
      </el-col>
    </el-row>

    <div class="mi-card row">
      <div class="rt"><span>今日工单流水（alert_ticket，按 created_at / close_at 落在今天）</span></div>
      <el-descriptions :column="5" border size="small">
        <el-descriptions-item label="今日新建">{{ board ? fmtNum(board.todayCreatedCnt) : '—' }}</el-descriptions-item>
        <el-descriptions-item label="今日办结">{{ board ? fmtNum(board.todayClosedCnt) : '—' }}</el-descriptions-item>
        <el-descriptions-item label="未办结">{{ board ? fmtNum(openTicketCnt) : '—' }}</el-descriptions-item>
        <el-descriptions-item label="其中未认领">{{ pendingCnt === null ? '—' : fmtNum(pendingCnt) }}</el-descriptions-item>
        <el-descriptions-item label="SLA 口径（实时读 sys_config）">
          L3 → {{ slaText.L3 }} · L2 → {{ slaText.L2 }}
        </el-descriptions-item>
      </el-descriptions>
      <p class="dim note">
        「今日办结 0」和「累计未办结 184」同时成立是正常的：危机工单的终态必须由人写 handle_note 才能落，
        系统不会自动关闭。这一屏的意义就是让「积压」以数字而不是感觉呈现出来。
      </p>
    </div>

    <div class="mi-card row" data-block="rec-ops">
      <div class="rt">
        <span>推荐链路健康度与离线重算（A9 · /api/admin/rec/* · 任务 7.9/7.15）</span>
        <div class="tools">
          <el-tag size="small" :type="recTag.tone">{{ recTag.text }}</el-tag>
          <el-button size="small" :loading="recBusy" @click="loadRec">拉取状态</el-button>
          <el-button size="small" type="warning" plain :loading="rebuilding" @click="doRebuild">立即重算一轮</el-button>
        </div>
      </div>
      <p v-if="bad.rec" class="err">{{ bad.rec }}</p>
      <el-descriptions v-if="rec" :column="4" border size="small">
        <el-descriptions-item label="缓存行 cacheRows">{{ fmtNum(rec.cacheRows) }}</el-descriptions-item>
        <el-descriptions-item label="相似对 similarityRows">{{ fmtNum(rec.similarityRows) }}</el-descriptions-item>
        <el-descriptions-item label="相似批次时刻">{{ fmtTime(rec.similarityBatchAt) }}</el-descriptions-item>
        <el-descriptions-item label="缓存模式">{{ rec.cacheMode || '—' }}</el-descriptions-item>
        <el-descriptions-item label="上次作业完成">{{ fmtTime(rec.lastFinishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="结果 TTL">{{ rec.resultTtlMinutes == null ? '—' : rec.resultTtlMinutes + ' 分钟' }}</el-descriptions-item>
        <el-descriptions-item label="作业占用">{{ rec.running ? '是（重入锁被占）' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="上次失败">{{ rec.lastFailure || '无' }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="recChannels.length" class="chips">
        <span class="dim">上一轮各召回通道写入行数：</span>
        <el-tag v-for="c in recChannels" :key="c.name" size="small" type="info">{{ c.name }} {{ fmtNum(c.cnt) }}</el-tag>
      </div>
      <p v-if="recResult" class="dim note">
        本次重算 {{ recResult.mode }}：候选池 质量分 {{ fmtNum(recResult.qualityRows) }} · 话题 {{ fmtNum(recResult.topicRows) }} ·
        相似位 {{ fmtNum(recResult.similarityRows) }}（{{ fmtNum(recResult.itemsWithNeighbors) }} 个物品有邻居）→ 写缓存
        {{ fmtNum(recResult.resultRows) }} 行 / 覆盖 {{ fmtNum(recResult.users) }} 人，calc_ms {{ fmtNum(recResult.calcMs) }}。
        同一份摘要同步打在后端 application.log（D7「离线作业日志可查」验收物就是这行）。
      </p>
      <p class="dim note">
        为什么这块不写 admin_op_log：立即重算是幂等纯计算，不碰任何内容也不改任何用户状态，理由写在
        AdminRecController 类注释里；被并发占用时后端回 10010/429，这里照实显示这句话，不自动重试、不假装成功。
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  auditAssigneeStats, auditStatusCounts, appealPendingCount, dashboardStats,
recRebuild, recStatus, reportPendingCount, ticketBoard, ticketOverdue, ticketSla, tickets
} from '@/api/admin'
import { fmtNum, fmtTime, statusTone } from '@/utils/format'

const router = useRouter()
const stats = ref(null)
const board = ref(null)
const overdue = ref([])
const auditCounts = ref([])
const assignees = ref([])
const appealCnt = ref(null)
const reportCnt = ref(null)
const pendingCnt = ref(null)
const rec = ref(null)
const recBusy = ref(false)
const rebuilding = ref(false)
const recResult = ref(null)
const slaText = reactive({ L3: '—', L2: '—' })
const bad = reactive({ stats: '', board: '', overdue: '', audit: '', assignee: '', appeal: '', report: '', sla: '', pending: '', rec: '' })
const busy = ref(false)
const lastAt = ref('—')

const fatal = computed(() => (bad.stats ? '看板主接口失败：' + bad.stats : ''))

const openTicketCnt = computed(() => {
  const rows = board.value && board.value.statusCounts
  if (!Array.isArray(rows)) return null
  return rows.filter((r) => ['pending', 'claimed', 'doing'].includes(r.status))
    .reduce((a, r) => a + Number(r.cnt || 0), 0)
})

const worstText = computed(() => {
  if (!board.value || board.value.worstMinutes === null || board.value.worstMinutes === undefined) return '—'
  const m = Number(board.value.worstMinutes)
  if (!m) return '无'
  const h = Math.floor(m / 60)
  return h >= 24 ? Math.floor(h / 24) + ' 天 ' + (h % 24) + ' 小时' : h + ' 小时 ' + (m % 60) + ' 分'
})

const todos = computed(() => {
  const s = stats.value || {}
  const n = (v, err) => (err ? '读取失败' : (v === undefined || v === null ? '—' : fmtNum(v)))
  return [
    {
      key: 'audit', label: '待人工审核', value: n(s.auditPendingCnt, bad.stats), unit: '条', tone: 'warn',
      desc: 'audit_task.status=PENDING。灰名单命中、举报、图片通道都汇到这里；173 条不是「队列坏了」，是真实积压。',
      cta: '去审核队列', to: '/audit'
    },
    {
      key: 'ticket', label: '未办结危机工单', value: n(s.ticketPendingCnt, bad.stats), unit: '条', tone: 'danger',
      desc: 'pending / claimed / doing 三态合计。L3 默认 30 分钟 SLA，超时数在右侧单独列。',
      cta: '去工单台', to: '/tickets'
    },
    {
      key: 'report', label: '待处置举报', value: n(reportCnt.value, bad.report), unit: '条', tone: 'warn',
      desc: 'content_report.status=PENDING，处置动作在内容管理里，办结会写 REPORT_HANDLE 留痕。',
      cta: '去内容管理', to: '/content'
    },
    {
      key: 'appeal', label: '待裁定申诉', value: n(appealCnt.value, bad.appeal), unit: '条', tone: 'info',
      desc: '帖子被下架后作者只有一次申诉机会，驳回会回到「进入 APPEALING 之前的那个状态」。',
      cta: '去申诉裁定', to: '/content?tab=appeals'
    },
    {
      key: 'overdue', label: 'SLA 超时', value: n(s.overdueCnt, bad.stats), unit: '条', tone: 'danger',
      desc: 'sla_at 已过期且未办结。这是 FR8.2 里最不能看错的一列。',
      cta: '查看超时清单', to: '/tickets?overdueOnly=1'
    },
    {
      key: 'crisis', label: '累计危机触发', value: n(s.crisisCnt, bad.stats), unit: '次', tone: 'info',
      desc: '双通道 RiskScorer（词典规则 + LLM 语义）两路取高判到 L2/L3 的次数。',
      cta: '查看工单', to: '/tickets'
    },
    {
      key: 'dau', label: '今日活跃', value: n(s.dau, bad.stats), unit: '人', tone: '',
      desc: '近 24h 有打卡、发帖或 AI 对话的去重用户。',
      cta: '查看用户', to: '/users'
    },
    {
      key: 'ai', label: 'AI 词元消耗', value: n(s.tokenCnt, bad.stats), unit: 'tokens', tone: '',
      desc: 'ai_call_log 汇总；cost_cent 未接单价表时为 0，界面上不拿 0 元当结论（见大屏）。',
      cta: '看用量报表', to: '/logs'
    }
  ]
})

const auditTotal = computed(() => auditCounts.value.reduce((a, r) => a + Number(r.cnt || 0), 0))
function pct (c) { return auditTotal.value ? Math.round((Number(c) / auditTotal.value) * 100) : 0 }
function avgSec (v) {
  const s = Number(v || 0)
  if (!s) return '—'
  return s < 60 ? s + ' 秒' : Math.floor(s / 60) + ' 分 ' + (s % 60) + ' 秒'
}


// A9 推荐运维读数（任务 7.9 / 7.15 · 手册 §10.2）。
// 这块的判据不是「能不能多显示一堆数」，而是「cacheRows=0 时必须一眼看出来」：
// 缓存 0 行意味着每个人都在走热度兜底，个性化等于没生效（D1 会挂），定时轮也可能是压根没跑起来。
// 所以标签三色：读数失败 danger / 缓存 0 行 warning / 正常 success，中间态绝不显示成绿色。
const recChannels = computed(() => {
  const share = rec.value && rec.value.lastSummaryChannelShare
  if (!share || typeof share !== 'object') return []
  return Object.keys(share).map((k) => ({ name: k, cnt: Number(share[k] || 0) }))
})

const recTag = computed(() => {
  if (bad.rec) return { tone: 'danger', text: '读数失败' }
  const r = rec.value
  if (!r) return { tone: 'info', text: '未读取' }
  if (r.lastFailure) return { tone: 'danger', text: '上次作业失败' }
  if (!Number(r.cacheRows)) return { tone: 'warning', text: '缓存 0 行 · 个性化未生效' }
  return { tone: 'success', text: '缓存在线 ' + fmtNum(r.cacheRows) + ' 行' }
})

async function loadRec () {
  recBusy.value = true
  await track('rec', recStatus(), (d) => { rec.value = d })
  recBusy.value = false
}

// rebuild 在后端是同步接口（刻意不做异步任务表 + 进度轮询，理由写在 AdminRecController 类注释），
// 所以这里只有「转圈 → 拿摘要」两段，绝不画一条假的进度百分比。
// 成功后回读一次 status：页面上那组 cacheRows / 批次时刻必须是这一轮之后的真值，
// 不然「点了重算、显示的还是重算前的行量」这种画面会在答辩时被问住。
async function doRebuild () {
  rebuilding.value = true
  recResult.value = null
  bad.rec = ''
  try {
    recResult.value = await recRebuild()
    await loadRec()
  } catch (e) {
    recResult.value = null
    bad.rec = (e && e.message) || String(e)
  } finally {
    rebuilding.value = false
  }
}
function track (key, p, setter) {
  return p.then((d) => { setter(d); bad[key] = '' }, (e) => { bad[key] = e.message || String(e) })
}

async function loadAll () {
  busy.value = true
  await Promise.all([
    track('stats', dashboardStats(), (d) => { stats.value = d }),
    track('board', ticketBoard(), (d) => { board.value = d }),
    track('overdue', ticketOverdue(8), (d) => { overdue.value = d || [] }),
    track('audit', auditStatusCounts(), (d) => { auditCounts.value = d || [] }),
    track('assignee', auditAssigneeStats(), (d) => { assignees.value = d || [] }),
    track('appeal', appealPendingCount(), (d) => { appealCnt.value = d }),
    track('report', reportPendingCount(), (d) => { reportCnt.value = d }),
    track('sla', ticketSla('L3'), (d) => { slaText.L3 = fmtTime(d) }),
    track('sla', ticketSla('L2'), (d) => { slaText.L2 = fmtTime(d) }),
    // 未认领数没有专门接口，用列表的 total 说同一件事：status=pending 的总数（size=1 只为取 total）。
    track('pending', tickets({ status: 'pending', page: 1, size: 1 }), (d) => { pendingCnt.value = d && typeof d.total === 'number' ? d.total : null })
  ])
  lastAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  busy.value = false
}

function goTicket (row) { router.push('/tickets?ticketId=' + row.id) }

let timer = null
onMounted(() => {
  loadAll()
  loadRec()
  timer = setInterval(() => { loadAll(); loadRec() }, 60000)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.hd { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }
.h { margin: 0; font-size: 18px; }
.tools { display: flex; align-items: center; gap: 10px; }
.alert { margin-bottom: 12px; }
.row { margin-top: 14px; }
.todo { cursor: pointer; transition: transform .12s, border-color .12s; height: 100%; }
.todo:hover { transform: translateY(-2px); border-color: var(--mi-primary); }
.todo .label { font-size: 13px; color: var(--mi-text-dim); }
.todo .value { font-size: 28px; font-weight: 700; margin: 6px 0 4px; color: var(--mi-text); }
.todo .unit { font-size: 12px; color: var(--mi-text-dim); margin-left: 6px; }
.todo .desc { font-size: 11px; color: var(--mi-text-dim); line-height: 1.6; min-height: 44px; }
.todo .go { font-size: 12px; color: var(--mi-primary); margin-top: 6px; }
.todo.danger .value { color: #D9534F; }
.todo.warn .value { color: #F2C14E; }
.rt { display: flex; align-items: center; justify-content: space-between; font-size: 14px; font-weight: 700; margin-bottom: 10px; }
.rt.sub { margin-top: 14px; font-size: 13px; }
.dim { color: var(--mi-text-dim); font-size: 12px; }
.err { color: #D9534F; font-size: 12px; }
.chips { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 10px 0 0; }
.note { margin: 10px 0 0; line-height: 1.7; }
</style>