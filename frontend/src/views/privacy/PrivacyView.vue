<template>
  <div class="page">
    <div class="topbar">
      <div>
        <h1 class="h1">隐私中心</h1>
        <p class="dim">
          「我的数据」这一侧的四个动作：看清楚系统里存了什么、把全部数据带走、撤回某一项授权、
          以及带着 30 天冷静期离开。授权开关本身在账户中心（那边是逐项 switch，这一页只管口径与导出/注销）。
        </p>
      </div>
      <div class="ops">
        <el-button size="small" :loading="busy.page" @click="loadAll">刷新</el-button>
        <el-button size="small" @click="goAccount">账户中心</el-button>
      </div>
    </div>

    <StageNotice :code="codes.page" api-name="GET /api/privacy/summary" />

    <section class="mi-card blk">
      <h2 class="h2">1 · 账号本体与当前状态</h2>
      <p class="dim">
        下面这些字段就是导出包里 account 那一节的原文：界面与包共用后端同一个白名单类
        PrivacyViews.AccountFacts，不会出现「界面上写着有、包里其实没有」。password 不是「导出时记得排除」，
        而是这个白名单类里根本没有这一列，任何路径都带不出去。
      </p>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="用户 id">{{ dash(acc.id) }}</el-descriptions-item>
        <el-descriptions-item label="账号状态">{{ dash(sum.accountStatus) }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ dash(acc.username) }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ dash(acc.nickname) }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ dash(acc.email) }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ dash(acc.role) }}</el-descriptions-item>
        <el-descriptions-item label="对话风格">{{ dash(acc.aiStyle) }}</el-descriptions-item>
        <el-descriptions-item label="注册来源">{{ dash(acc.regSource) }}</el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ fmtDateTime(acc.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="最近登录">{{ fmtDateTime(acc.lastLoginAt) }}</el-descriptions-item>
        <el-descriptions-item label="同意隐私协议">{{ fmtDateTime(acc.agreePrivacyAt) }}</el-descriptions-item>
        <el-descriptions-item label="概览生成于">{{ fmtDateTime(sum.generatedAt) }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="sum.cooling"
        class="alert"
        type="warning"
        show-icon
        :closable="false"
        :title="'账号在冷静期里，' + dash(sum.coolingRemainDays) + ' 天后进入清除批次'"
        :description="'提交注销于 ' + fmtDateTime(sum.deactivateAt) + '，计划在 ' + fmtDateTime(sum.purgeAt) + ' 的夜间批次物理清除。这期间任意一次登录，或点第 4 节的「撤回注销」，都会立刻恢复为 ACTIVE。'"
      />
      <el-alert v-else class="alert" type="success" show-icon :closable="false" title="账号状态正常" description="没有待清除的注销申请。" />
    </section>

    <section class="mi-card blk">
      <h2 class="h2">2 · 逐域条数对账（FR1.5「可查看、可核对」）</h2>
      <p class="dim">
        清单来自后端 PrivacyDomains 注册表：一张存个人信息的表必须在那里登记，否则它既不会出现在这份清单里，
        也不会出现在导出包中。「可携带权」能落地的前提是先说得全 —— 到底在哪些表里存了你。
      </p>
      <div class="row2">
        <span class="dim">接口合计 {{ dash(sum.totalRows) }} 条 · 列出 {{ listed.length }} 个域 · 列出的加起来 {{ listedRows }} 条</span>
        <el-switch v-model="onlyNonZero" size="small" active-text="只看有条数的域" />
      </div>
      <p v-if="reconDiff" class="warn">{{ reconDiff }}</p>
      <el-table :data="listed" size="small" max-height="330" class="tbl">
        <el-table-column prop="table" label="表名" min-width="200" />
        <el-table-column prop="rows" label="行数" width="90" align="right" />
        <el-table-column label="是否截断" width="150">
          <template #default="s">
            <el-tag v-if="s.row.truncated" type="warning" size="small">超出单域上限，包内少几行</el-tag>
            <span v-else class="dim">完整</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="mi-card blk">
      <h2 class="h2">3 · 导出我的全部个人信息（FR1.6）</h2>
      <p class="dim">
        导出是「排队 → 后台跑 → 成功才给口令」四步，不是一个同步接口：全量可能要读几十万行，
        做成同步就会在网关那边先超时。点完按钮之后等状态自己变成 SUCCESS，链接才会真的可点。
      </p>
      <div class="row2">
        <el-radio-group v-model="format" size="small">
          <el-radio-button value="json">JSON（带字段名）</el-radio-button>
          <el-radio-button value="csv">CSV（表格软件直接打开）</el-radio-button>
        </el-radio-group>
        <el-button size="small" type="primary" :loading="busy.export" @click="submitExportTask">提交导出任务</el-button>
        <el-button size="small" :disabled="!canDownload" :loading="busy.download" @click="doDownload">下载到本机</el-button>
        <span class="dim">{{ statusText }}</span>
      </div>
      <StageNotice :code="codes.export" api-name="GET /api/privacy/export" />
      <p v-if="exportExpired" class="warn">这条产物已经过期（{{ fmtDateTime(latest.expireAt) }}），下载会失败，请重新提交一次导出。</p>
      <el-descriptions v-if="latest" :column="3" border size="small" class="dsc">
        <el-descriptions-item label="任务 id">{{ dash(latest.id) }}</el-descriptions-item>
        <el-descriptions-item label="格式">{{ dash(latest.format) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ dash(latest.status) }}</el-descriptions-item>
        <el-descriptions-item label="大小">{{ kb(latest.fileBytes) }}</el-descriptions-item>
        <el-descriptions-item label="行数">{{ dash(latest.rowCountSummary) }}</el-descriptions-item>
        <el-descriptions-item label="提交于">{{ fmtDateTime(latest.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="链接有效至">{{ fmtDateTime(latest.expireAt) }}</el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="2">{{ dash(latest.errorText) }}</el-descriptions-item>
      </el-descriptions>
      <p v-else class="dim">还没有导出记录。</p>
      <div v-if="history.length" class="his">
        <p class="h3">最近 {{ history.length }} 次导出</p>
        <el-table :data="history" size="small" max-height="220">
          <el-table-column prop="id" label="id" width="70" />
          <el-table-column prop="format" label="格式" width="80" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="大小" width="100">
            <template #default="s">{{ kb(s.row.fileBytes) }}</template>
          </el-table-column>
          <el-table-column prop="rowCountSummary" label="行数" min-width="150" />
          <el-table-column label="提交于" width="150">
            <template #default="s">{{ fmtDateTime(s.row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <p class="foot dim">
        口令只出现在 downloadPath 这一处，后端响应体里没有 filePath，也没有 token 字段本身；
        产物过期时间由 expireAt 决定，到期之后同一条链接一律 404（不区分「不存在」与「不是你的」，避免旁路探测）。
      </p>
    </section>

    <section class="mi-card blk">
      <h2 class="h2">4 · 注销账号（30 天冷静期，不是点一下就没了）</h2>
      <p class="dim">
        后端在这一步只打冷静期标记，一行数据都不删；真正的物理清除由每天 03:30 的到期批次执行。
        这是「删除权」与「可反悔」之间的折中：给了反悔的时间，也给了批次一个确定的执行点。
        重复提交是幂等的，不会把到期时间往后推。
      </p>
      <div class="row2">
        <el-button v-if="!sum.cooling" size="small" type="danger" :loading="busy.deactivate" @click="clickDeactivate">
          {{ armed ? '再点一次确认注销' : '提交注销申请' }}
        </el-button>
        <el-button v-else size="small" type="primary" :loading="busy.restore" @click="clickRestore">撤回注销，恢复账号</el-button>
        <span v-if="actionMsg" class="dim">{{ actionMsg }}</span>
      </div>
      <el-descriptions v-if="actView" :column="3" border size="small" class="dsc">
        <el-descriptions-item label="状态">{{ dash(actView.status) }}</el-descriptions-item>
        <el-descriptions-item label="冷静期天数">{{ dash(actView.coolingDays) }}</el-descriptions-item>
        <el-descriptions-item label="剩余天数">{{ dash(actView.coolingRemainDays) }}</el-descriptions-item>
        <el-descriptions-item label="提交于">{{ fmtDateTime(actView.deactivateAt) }}</el-descriptions-item>
        <el-descriptions-item label="计划清除">{{ fmtDateTime(actView.purgeAt) }}</el-descriptions-item>
        <el-descriptions-item label="在冷静期">{{ actView.cooling ? '是' : '否' }}</el-descriptions-item>
      </el-descriptions>
      <p v-if="actView && actView.message" class="dim">{{ actView.message }}</p>
      <p class="foot dim">
        手册只写了「冷静期内登录即自动撤回」，这里多出一条 POST /api/privacy/restore：
        只靠登录撤回在界面上无法验收 —— 用户点「撤回注销」时不该被踢出去重登一次；
        没有这条端点，冒烟只能证明「进得了冷静期」，证不出「出得了冷静期」。偏差已记 dev-log。
      </p>
    </section>

    <section class="mi-card blk">
      <h2 class="h2">5 · 撤回某一项授权（NFR8）</h2>
      <p class="dim">
        开关在账户中心，这一节讲两个口径：① 撤回是「新增一条 WITHDRAW 流水」而不是删掉历史行，
        举证时授权与撤回两段都看得见（个保法第 15 条可撤回、第 29 条单独同意）；
        ② TERMS 与 PRIVACY 两项后端有闸门，撤回会回 10001 —— 它们是「用这个产品」的前提，
        做成可关的开关就等于允许产品里存在「不同意但仍在用」的状态，那种情形该走的动作是注销。
      </p>
      <div class="row2">
        <el-select v-model="consentType" size="small" class="sel">
          <el-option v-for="t in WITHDRAWABLE" :key="t.type" :label="t.name" :value="t.type" />
        </el-select>
        <el-button size="small" :loading="busy.consent" @click="clickWithdraw">撤回这一项</el-button>
        <span v-if="consentMsg" class="dim">{{ consentMsg }}</span>
      </div>
    </section>

    <section v-if="user.isAdmin" class="mi-card blk">
      <h2 class="h2">6 · 到期清除批次（仅管理员 · 真的会删数据）</h2>
      <p class="warn">
        这一条调的就是定时任务那一个 run()，与每天夜里 03:30 跑的是同一段代码，所以它会真删。
        演示请先只放一个测试账号进冷静期，或把 mindisle.privacy.cooling-days 配成 1 天。
        没有 dry-run 是刻意的：「假装清除」要另写一套只读归属判定，而那套代码不会随真清除一起更新，本身就是假保险。
      </p>
      <div class="row2">
        <el-input-number v-model="retentionLimit" size="small" :min="0" :max="200" />
        <el-button size="small" type="danger" :loading="busy.retention" @click="clickRetention">立即跑一批</el-button>
      </div>
      <el-descriptions v-if="retention" :column="3" border size="small" class="dsc">
        <el-descriptions-item label="本批到期">{{ dash(retention.due) }}</el-descriptions-item>
        <el-descriptions-item label="已清除">{{ dash(retention.purged) }}</el-descriptions-item>
        <el-descriptions-item label="失败">{{ dash(retention.failed) }}</el-descriptions-item>
        <el-descriptions-item label="被批次上限截断">{{ retention.truncated ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ dash(retention.costMillis) }} ms</el-descriptions-item>
        <el-descriptions-item label="批次时点">{{ fmtDateTime(retention.now) }}</el-descriptions-item>
      </el-descriptions>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import StageNotice from '@/components/StageNotice.vue'
import {
  summary,
  submitExport,
  latestExport,
  exportHistory,
  deactivate,
  restore,
  withdrawConsent,
  runRetention,
  downloadExport
} from '@/api/privacy'
import { fmtDateTime, toDate } from '@/utils/format'

// 接口口径抄自 web/PrivacyController.java 与 privacy/PrivacyViews.java，字段名一个都不改：
// Summary(generatedAt, account, accountStatus, cooling, deactivateAt, purgeAt,
//   coolingRemainDays, coolingDays, totalRows, domains[DomainCount(table, rows, truncated)])
// ExportTaskView(id, format, status, fileBytes, rowCountSummary, downloadReady, downloadPath,
//   expireAt, createdAt, errorText) —— 注意它不含 filePath 也不含 token，token 已经拼在 downloadPath 里。
// 后端 Jackson 的 default-property-inclusion 是 non_null：null 字段会整个从 JSON 里消失，
// 所以这里读的所有键都当成「可能不存在」来写，不假设它在。
const user = useUserStore()
const router = useRouter()

const WITHDRAWABLE = [
  { type: 'SENSITIVE_INFO', name: '敏感个人信息单独同意' },
  { type: 'EMOTION_SHARE', name: '情绪状态匿名展示' },
  { type: 'CRISIS_CONTACT', name: '危机情形下的主动联系' }
]

const busy = reactive({ page: false, export: false, download: false, deactivate: false, restore: false, consent: false, retention: false })
const codes = reactive({ page: null, export: null })
const sum = ref({})
const latest = ref(null)
const history = ref([])
const format = ref('json')
const onlyNonZero = ref(true)
const actView = ref(null)
const retention = ref(null)
const retentionLimit = ref(0)
const consentType = ref('SENSITIVE_INFO')
const consentMsg = ref('')
const actionMsg = ref('')
const armed = ref(false)

let armTimer = null
let pollTimer = null
let pollTicks = 0
const POLL_MS = 2000
const POLL_MAX = 90

const acc = computed(() => sum.value.account || {})
const domains = computed(() => (Array.isArray(sum.value.domains) ? sum.value.domains : []))
const listed = computed(() => (onlyNonZero.value ? domains.value.filter((d) => Number(d.rows) > 0) : domains.value))
const listedRows = computed(() => listed.value.reduce((n, d) => n + (Number(d.rows) || 0), 0))
const reconDiff = computed(() => {
  const total = Number(sum.value.totalRows)
  if (!Number.isFinite(total)) return ''
  if (total === listedRows.value) return ''
  return '对不上：接口说合计 ' + total + ' 条，逐域加起来 ' + listedRows.value + ' 条。这条不该发生，请把 traceId 交给后端排查。'
})
const expireMoment = computed(() => toDate(latest.value && latest.value.expireAt))
const exportExpired = computed(() => {
  const t = latest.value
  if (!t || !t.downloadReady || !expireMoment.value) return false
  return expireMoment.value.getTime() <= Date.now()
})
const canDownload = computed(() => !!latest.value && latest.value.downloadReady === true && !exportExpired.value)
const statusText = computed(() => {
  const t = latest.value
  if (!t) return '还没有导出记录'
  if (t.status === 'SUCCESS') return exportExpired.value ? '产物已过期，重新导出即可' : '已生成，可以下载'
  if (t.status === 'FAILED') return '失败：' + (t.errorText || '后端没给原因')
  return '任务在后台生成中（' + (t.status || 'PENDING') + '）…'
})

function dash(v) {
  return v === null || v === undefined || v === '' ? '-' : String(v)
}

function kb(bytes) {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n <= 0) return '-'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

function goAccount() {
  router.push({ name: 'me' })
}

async function loadSummary() {
  busy.page = true
  codes.page = null
  try {
    const data = await summary()
    sum.value = data || {}
  } catch (e) {
    codes.page = e && e.code ? e.code : 'network'
  } finally {
    busy.page = false
  }
}

async function loadLatest() {
  try {
    latest.value = (await latestExport()) || null
  } catch (e) {
    /* silent：这一格有「还没有导出记录」的降级态，不弹红条 */
  }
}

async function loadHistory() {
  try {
    const rows = await exportHistory(20)
    history.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    history.value = []
  }
}

async function loadAll() {
  await loadSummary()
  await loadLatest()
  await loadHistory()
  if (latest.value && !latest.value.downloadReady && latest.value.status !== 'FAILED') startPolling()
}

// 轮询而不是长连接：导出任务的真相在 privacy_export_task 表里，进程重启也还得看得见；
// 换成 SSE 就把「任务状态」做成了只在一条连接里存在的临时物。
function startPolling() {
  stopPolling()
  pollTicks = 0
  pollTimer = setInterval(async () => {
    pollTicks += 1
    if (pollTicks > POLL_MAX) {
      stopPolling()
      ElMessage.info('后台还在生成，稍后回来刷新这页就能看到链接')
      return
    }
    await loadLatest()
    const t = latest.value
    if (t && (t.downloadReady || t.status === 'FAILED')) {
      stopPolling()
      await loadHistory()
      if (t.downloadReady) ElMessage.success('导出包已生成，可以下载到本机')
      else ElMessage.error('导出失败：' + (t.errorText || '后端没给原因'))
    }
  }, POLL_MS)
}

function stopPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
}

async function submitExportTask() {
  busy.export = true
  codes.export = null
  try {
    latest.value = await submitExport(format.value)
    ElMessage.success('导出任务已排队')
    startPolling()
  } catch (e) {
    codes.export = e && e.code ? e.code : 'network'
  } finally {
    busy.export = false
  }
}

async function doDownload() {
  if (!canDownload.value) return
  busy.download = true
  try {
    const r = await downloadExport(latest.value)
    ElMessage.success('已保存到浏览器下载目录：' + r.fileName + '（' + kb(r.bytes) + '）')
  } catch (e) {
    ElMessage.error(e && e.message ? e.message : '下载失败')
    await loadLatest()
  } finally {
    busy.download = false
  }
}

// 注销是不可逆方向的动作，这里用「两下」而不是弹层：第一次点只是把按钮换成「再点一次确认注销」，
// 5 秒不点就自动解除。和 ChatView 里删会话是同一套口径，不再为一条按钮引一层 ElMessageBox。
function clickDeactivate() {
  if (!armed.value) {
    armed.value = true
    actionMsg.value = '这一步不会删任何数据，只是让账号进入 30 天冷静期。再点一次就提交。'
    if (armTimer) clearTimeout(armTimer)
    armTimer = setTimeout(() => {
      armed.value = false
      actionMsg.value = ''
    }, 5000)
    return
  }
  armed.value = false
  doDeactivate()
}

async function doDeactivate() {
  busy.deactivate = true
  try {
    actView.value = await deactivate()
    actionMsg.value = actView.value && actView.value.message ? actView.value.message : '已提交注销申请'
    ElMessage.warning('已进入冷静期，登录或点「撤回注销」都能恢复')
    await loadSummary()
  } catch (e) {
    actionMsg.value = e && e.message ? e.message : '提交失败'
  } finally {
    busy.deactivate = false
  }
}

async function clickRestore() {
  busy.restore = true
  try {
    actView.value = await restore()
    actionMsg.value = actView.value && actView.value.message ? actView.value.message : '已撤回注销'
    ElMessage.success('账号已恢复正常')
    await loadSummary()
  } catch (e) {
    // 10001 = 不在冷静期（要么早就恢复了，要么已经被清掉）
    actionMsg.value = e && e.message ? e.message : '撤回失败'
  } finally {
    busy.restore = false
  }
}

async function clickWithdraw() {
  if (!consentType.value) return
  busy.consent = true
  try {
    const row = await withdrawConsent(consentType.value)
    consentMsg.value = '已追加一条 WITHDRAW 流水（' + (row && row.createdAt ? fmtDateTime(row.createdAt) : '刚刚') + '），账户中心的开关会同步变化。'
    ElMessage.success('已撤回这项授权')
    await loadSummary()
  } catch (e) {
    consentMsg.value = e && e.message ? e.message : '撤回失败'
  } finally {
    busy.consent = false
  }
}

async function clickRetention() {
  busy.retention = true
  try {
    retention.value = await runRetention(retentionLimit.value || undefined)
    await loadSummary()
  } catch (e) {
    // 非管理员在这条方法级闸门上是 10003，不是 404 —— 页面隐藏按钮是体验，后端拦才是安全边界
    ElMessage.error(e && e.message ? e.message : '清除批次失败')
  } finally {
    busy.retention = false
  }
}

onMounted(loadAll)
onUnmounted(() => {
  stopPolling()
  if (armTimer) clearTimeout(armTimer)
})
</script>

<style scoped>
/* 与账户中心同一套骨架：page / topbar / blk / h2 / dim / warn 都是各页自己的，
   公共的只有 mi-card 与主题令牌 —— 刻意不做「一个大 while 页面共用一份样式」。 */
.page { max-width: 1020px; margin: 0 auto; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h1 { margin: 0; font-size: 21px; }
.h2 { margin: 0 0 6px; font-size: 16px; }
.h3 { margin: 14px 0 6px; font-size: 13px; color: var(--mi-mist); }
.dim { color: var(--mi-text-dim); font-size: 12.5px; margin: 6px 0 0; line-height: 1.8; }
.warn { color: var(--mi-danger-text); font-size: 12.5px; line-height: 1.8; margin: 8px 0 0; }
.ops { display: flex; gap: 4px; flex-shrink: 0; flex-wrap: wrap; }
.blk { margin-top: 14px; }
.row2 { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin: 10px 0 12px; }
.row2 .dim { margin: 0; }
.alert { margin-top: 12px; }
.tbl { margin-top: 6px; }
.dsc { margin-top: 10px; }
.his { margin-top: 12px; }
.sel { width: 240px; }
.foot { margin: 12px 0 0; font-size: 12px; line-height: 1.8; }
</style>
