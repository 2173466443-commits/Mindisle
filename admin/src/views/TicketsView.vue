<template>
  <div class="tickets">
    <div class="mi-card strip">
      <div v-for="b in boardCards" :key="b.k" class="bcard">
        <div class="bk">{{ b.k }}</div>
        <div class="bv" :class="b.tone">{{ b.v }}</div>
      </div>
      <div class="statustags">
        <el-tag v-for="s in (board && board.statusCounts) || []" :key="s.status" size="small"
                :type="statusTone(s.status)" class="stag" @click="quick(s.status)">
          {{ s.status }} · {{ fmtNum(s.cnt) }}
        </el-tag>
      </div>
    </div>

    <div class="mi-card bar">
      <el-form inline>
        <el-form-item label="状态">
          <el-select v-model="f.status" clearable placeholder="全部" style="width: 150px" @change="reload">
            <el-option v-for="s in TICKET_STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="f.level" clearable placeholder="全部" style="width: 110px" @change="reload">
            <el-option v-for="s in TICKET_LEVELS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="只看超时"><el-switch v-model="f.overdueOnly" @change="reload" /></el-form-item>
        <el-form-item label="只看我认领的"><el-switch v-model="f.mine" @change="reload" /></el-form-item>
        <el-form-item>
          <el-button :loading="busy" @click="reload">刷新</el-button>
          <el-button @click="exportCsv" :loading="exp">导出 CSV</el-button>
          <el-button @click="checkSla">验证 SLA 参数</el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert v-if="notice" :title="notice" :type="noticeTone" show-icon :closable="true" class="alert" @close="notice = ''" />

    <div class="mi-card list">
      <el-table :data="rows" size="small" height="440" @row-click="openDetail">
        <el-table-column prop="ticket.id" label="工单" width="70" />
        <el-table-column label="级别" width="72">
          <template #default="{ row }"><el-tag size="small" :type="statusTone(row.ticket.level)">{{ row.ticket.level }}</el-tag></template>
        </el-table-column>
        <el-table-column label="用户" width="150">
          <template #default="{ row }">{{ row.nickname || '—' }}<div class="dim2">uid={{ row.ticket.userId }}</div></template>
        </el-table-column>
        <el-table-column label="触发词" width="180">
          <template #default="{ row }">
            <el-tag v-for="w in words(row.ticket.triggerWords)" :key="w" size="small" type="danger" effect="plain" class="wtag">{{ w }}</el-tag>
            <span v-if="!words(row.ticket.triggerWords).length" class="dim2">语义通道触发</span>
          </template>
        </el-table-column>
        <el-table-column label="证据" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ticket.evidenceText }}</template>
        </el-table-column>
        <el-table-column label="风险分" width="80">
          <template #default="{ row }">{{ Number(row.ticket.riskScore || 0).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag size="small" :type="statusTone(row.ticket.status)">{{ row.ticket.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="SLA" width="130">
          <template #default="{ row }">
            <span :class="'sla-' + fmtSla(row.slaMinutesLeft).tone">{{ fmtSla(row.slaMinutesLeft).text }}</span>
            <div class="dim2">{{ fmtTime(row.ticket.slaAt) }}</div>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <span class="dim">共 {{ page.total }} 条 · L2/L3 才进工单台（L1 只提醒），第 {{ page.page }} 页</span>
        <el-pagination small layout="prev, pager, next" :total="page.total" :page-size="page.size" :current-page="page.page" @current-change="onPage" />
      </div>
    </div>

    <el-drawer v-model="drawer" :title="'危机工单 #' + (cur ? cur.id : '')" size="620px">
      <template v-if="cur">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="级别"><el-tag size="small" :type="statusTone(cur.level)">{{ cur.level }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag size="small" :type="statusTone(cur.status)">{{ cur.status }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="来源">{{ cur.sourceType }} #{{ cur.sourceId }}</el-descriptions-item>
          <el-descriptions-item label="风险分">{{ Number(cur.riskScore || 0).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="建单">{{ fmtTime(cur.createdAt, true) }}</el-descriptions-item>
          <el-descriptions-item label="SLA">{{ fmtTime(cur.slaAt, true) }}</el-descriptions-item>
          <el-descriptions-item label="认领人">{{ cur.assigneeId || '未认领' }}</el-descriptions-item>
          <el-descriptions-item label="认领时间">{{ fmtTime(cur.claimAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="sec">证据原文（触发词高亮）</div>
        <div class="evidence">
          <template v-for="(seg, i) in evSegs" :key="i">
            <mark v-if="seg.hit">{{ seg.t }}</mark><span v-else>{{ seg.t }}</span>
          </template>
        </div>

        <div class="sec">处置动作</div>
        <div class="acts">
          <el-button v-if="cur.status === 'pending'" type="primary" @click="doAction('claim')">认领</el-button>
          <el-button v-if="cur.status === 'claimed'" type="primary" @click="doAction('start')">开始处置</el-button>
          <el-button v-if="TICKET_OPEN_STATUSES.includes(cur.status)" type="success" @click="openClose">办结</el-button>
          <span class="dim">状态机：pending → claimed → doing → closed / false_positive；
            <b>终态不可逆</b>——库里的 closed 工单再点认领会拿到「工单已办结，不可重复处置」，这是设计而不是缺陷：
            处置记录一旦被覆盖，论文里「谁在几点处理了哪条危机」就无从证明。</span>
        </div>

        <div class="sec">该用户危机时间线（/tickets/timeline/:userId，最多 10 条）</div>
        <el-table v-if="timeline.length" :data="timeline" size="small">
          <el-table-column prop="id" label="工单" width="66" />
          <el-table-column prop="level" label="级别" width="64" />
          <el-table-column prop="status" label="状态" width="104">
            <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="建单"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
          <el-table-column label="回访"><template #default="{ row }">{{ fmtTime(row.followupAt) }}</template></el-table-column>
        </el-table>
        <p v-else-if="tlErr" class="err">{{ tlErr }}</p>
        <p v-else class="dim">这是该用户的第一条工单——重复触发的识别只能靠这里，不能靠人脑记住。</p>
        <el-button size="small" class="opener" @click="router.push('/users?keyword=&userId=' + cur.userId)">查看该用户档案</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="closer.show" title="办结工单" width="560">
      <el-form label-width="96px">
        <el-form-item label="终态">
          <el-radio-group v-model="closer.toStatus">
            <el-radio value="closed">已处置（closed）</el-radio>
            <el-radio value="false_positive">误报（false_positive）</el-radio>
            <el-radio value="expired">超时失效（expired）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处置记录">
          <el-input v-model="closer.note" type="textarea" :rows="4" placeholder="必填：联系了谁、给了什么卡片、下一步是什么（最长 1000 字）" />
        </el-form-item>
        <el-form-item label="回访时间">
          <el-date-picker v-model="closer.followup" type="datetime" placeholder="可空：安排一次回访" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
      </el-form>
      <p class="dim">办结之后这条工单不能再改：认领与办结的 SQL 都带 status IN (open) 条件，写终态时影响 0 行就报错。</p>
      <template #footer>
        <el-button @click="closer.show = false">取消</el-button>
        <el-button type="primary" :loading="closer.busy" @click="submitClose">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  TICKET_LEVELS, TICKET_OPEN_STATUSES, TICKET_STATUSES, exportTicketsCsv, saveBlob,
  ticketBoard, ticketClaim, ticketClose, ticketDetail, ticketSla, ticketStart, ticketTimeline, tickets
} from '@/api/admin'
import { useAdminUserStore } from '@/stores/adminUser'
import { fmtNum, fmtSla, fmtTime, highlight, statusTone } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const admin = useAdminUserStore()

const board = ref(null)
const rows = ref([])
const page = reactive({ total: 0, page: 1, size: 20 })
const f = reactive({ status: '', level: '', overdueOnly: false, mine: false })
const busy = ref(false)
const exp = ref(false)
const notice = ref('')
const noticeTone = ref('success')
const drawer = ref(false)
const cur = ref(null)
const timeline = ref([])
const tlErr = ref('')
const closer = reactive({ show: false, busy: false, toStatus: 'closed', note: '', followup: '' })

const myId = computed(() => (admin.profile ? Number(admin.profile.id) : 0))

const boardCards = computed(() => {
  const b = board.value || {}
  const worst = b.worstMinutes === undefined ? '—' : (Number(b.worstMinutes) >= 60 ? Math.floor(Number(b.worstMinutes) / 60) + ' 小时' : Number(b.worstMinutes) + ' 分')
  return [
    { k: '超时工单', v: b.overdueCnt === undefined ? '—' : fmtNum(b.overdueCnt), tone: Number(b.overdueCnt) > 0 ? 'bad' : 'ok' },
    { k: '最长拖延', v: worst, tone: 'bad' },
    { k: '今日新建', v: b.todayCreatedCnt === undefined ? '—' : fmtNum(b.todayCreatedCnt), tone: '' },
    { k: '今日办结', v: b.todayClosedCnt === undefined ? '—' : fmtNum(b.todayClosedCnt), tone: Number(b.todayClosedCnt) > 0 ? 'ok' : '' }
  ]
})

const evSegs = computed(() => (cur.value ? highlight(cur.value.evidenceText, words(cur.value.triggerWords)) : []))

function words (csv) {
  if (!csv) return []
  return String(csv).split(',').map((x) => x.trim()).filter(Boolean)
}
function say (t, tone) { notice.value = t; noticeTone.value = tone || 'success' }

async function reload () {
  busy.value = true
  const params = { page: page.page, size: page.size }
  if (f.status) params.status = f.status
  if (f.level) params.level = f.level
  if (f.overdueOnly) params.overdueOnly = true
  if (f.mine && myId.value) params.assigneeId = myId.value
  try {
    const data = await tickets(params)
    rows.value = data.list || []
    page.total = data.total
  } catch (e) {
    rows.value = []
    say('列表读取失败：' + (e.message || String(e)), 'error')
  }
  try { board.value = await ticketBoard() } catch (e) { board.value = null }
  busy.value = false
}
function onPage (p) { page.page = p; reload() }
function quick (status) { f.status = status; reload() }

async function openDetail (row) {
  const id = row && row.ticket ? row.ticket.id : row
  drawer.value = true
  cur.value = null
  timeline.value = []
  tlErr.value = ''
  try {
    const t = await ticketDetail(id)
    cur.value = t
    try { timeline.value = await ticketTimeline(t.userId, 10) } catch (e) { tlErr.value = '时间线读取失败：' + (e.message || String(e)) }
  } catch (e) {
    drawer.value = false
    say('详情读取失败：' + (e.message || String(e)), 'error')
  }
}

async function doAction (kind) {
  const id = cur.value.id
  try {
    const t = kind === 'claim' ? await ticketClaim(id) : await ticketStart(id)
    cur.value = t
    say((kind === 'claim' ? '已认领 #' : '已开始处置 #') + id)
    reload()
  } catch (e) {
    say((e.message || String(e)) + '（认领是原子的：别人抢先就影响 0 行）', 'warning')
    reload()
  }
}

function openClose () {
  closer.toStatus = 'closed'
  closer.note = ''
  closer.followup = ''
  closer.show = true
}

async function submitClose () {
  if (!closer.note.trim()) { say('处置记录不能为空：危机处置必须留下「谁做了什么」的文字证据', 'warning'); return }
  closer.busy = true
  try {
    const payload = { ticketId: cur.value.id, toStatus: closer.toStatus, note: closer.note }
    if (closer.followup) payload.followupAt = closer.followup
    const out = await ticketClose(payload)
    say('工单 #' + cur.value.id + ' 已办结为 ' + (out.toStatus || closer.toStatus) +
      '，触发词命中计数 +' + (out.hitWordsBumped || 0) + '（终态不可逆）')
    closer.show = false
    drawer.value = false
    reload()
  } catch (e) {
    say('办结失败：' + (e.message || String(e)), 'error')
  }
  closer.busy = false
}

async function exportCsv () {
  exp.value = true
  try {
    const { blob, filename } = await exportTicketsCsv(30)
    saveBlob(blob, filename)
    say('已导出 ' + filename + '（UTF-8 BOM + CRLF，首列若以 = + - @ 开头会被加单引号防公式注入）')
  } catch (e) {
    say('导出失败：' + (e.message || String(e)), 'error')
  }
  exp.value = false
}

// 答辩现场的「调一下就变」：A8 改 risk.sla_l3_minutes 之后回这里点一下，
// 拿到的 SLA 时刻跟着变，就证明参数是真的在生效而不是写进库就完事。
async function checkSla () {
  try {
    const l3 = await ticketSla('L3')
    const l2 = await ticketSla('L2')
    say('当前 SLA 口径下的截止时刻：L3 → ' + fmtTime(l3, true) + '，L2 → ' + fmtTime(l2, true) + '（由 sys_config 的 risk.sla_* 实时算出）')
  } catch (e) { say('读 SLA 失败：' + (e.message || String(e)), 'error') }
}

onMounted(() => {
  if (route.query.overdueOnly === '1') f.overdueOnly = true
  reload()
  if (route.query.ticketId) openDetail(Number(route.query.ticketId))
})
</script>

<style scoped>
.strip { display: flex; align-items: center; gap: 26px; flex-wrap: wrap; padding: 14px 18px; }
.bcard { min-width: 120px; }
.bk { font-size: 12px; color: var(--mi-text-dim); }
.bv { font-size: 26px; font-weight: 700; }
.bv.bad { color: #D9534F; }
.bv.ok { color: #7FB3A6; }
.statustags { display: flex; gap: 8px; flex-wrap: wrap; margin-left: auto; }
.stag { cursor: pointer; }
.bar { margin-top: 12px; padding: 10px 16px 0; }
.alert { margin-top: 12px; }
.list { margin-top: 12px; }
.pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.dim { color: var(--mi-text-dim); font-size: 12px; }
.dim2 { font-size: 11px; color: var(--mi-text-dim); }
.err { color: #D9534F; font-size: 12px; }
.wtag { margin: 0 4px 2px 0; }
.sla-danger { color: #D9534F; font-weight: 700; }
.sla-warning { color: #F2C14E; }
.sla-success { color: #7FB3A6; }
.sec { margin: 18px 0 8px; font-size: 14px; font-weight: 700; }
.evidence { background: var(--mi-bg); border: 1px solid var(--mi-border); border-radius: 10px; padding: 12px; font-size: 13px; line-height: 1.8; white-space: pre-wrap; }
.evidence :deep(mark) { background: rgba(217, 83, 79, 0.3); color: #ffd9d6; border-radius: 3px; padding: 0 2px; }
.acts { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.acts .dim { line-height: 1.7; }
.opener { margin-top: 10px; }
</style>