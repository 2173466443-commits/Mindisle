<template>
  <div class="audit">
    <div class="mi-card bar">
      <el-form inline class="form">
        <el-form-item label="状态">
          <el-select v-model="f.status" clearable placeholder="全部" style="width: 150px" @change="reload">
            <el-option v-for="s in AUDIT_STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="风险">
          <el-select v-model="f.riskLevel" clearable placeholder="全部" style="width: 110px" @change="reload">
            <el-option v-for="s in RISK_LEVELS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="只看超时">
          <el-switch v-model="f.overdueOnly" @change="reload" />
        </el-form-item>
        <el-form-item label="只看我认领的">
          <el-switch v-model="f.mine" @change="reload" />
        </el-form-item>
        <el-form-item>
          <el-button :loading="busy" @click="reload">刷新</el-button>
          <el-button :disabled="!sel.length" @click="claimBatch">批量认领（{{ sel.length }}）</el-button>
          <el-button type="success" :disabled="!sel.length" @click="openAdjudicate(true)">批量通过</el-button>
          <el-button type="danger" :disabled="!sel.length" @click="openAdjudicate(false)">批量驳回</el-button>
        </el-form-item>
      </el-form>
      <div class="ops">
        <el-button size="small" :loading="op.syncPosts" @click="doSyncPosts">同步待发审帖子</el-button>
        <el-button size="small" :loading="op.syncImages" @click="doSyncImages">同步图片通道</el-button>
        <el-button size="small" :loading="op.release" @click="doRelease">释放超时认领</el-button>
        <span class="dim">认领后 10 分钟不裁决会自动回队（AuditQueueService.CLAIM_TIMEOUT），最后一个是兜底按钮。</span>
      </div>
    </div>

    <el-alert v-if="notice" :title="notice" :type="noticeTone" show-icon :closable="true" class="alert" @close="notice = ''" />

    <el-row :gutter="14" class="body">
      <el-col :xs="24" :lg="16">
        <div class="mi-card">
          <el-table ref="tbl" :data="rows" size="small" row-key="rowKey" height="460"
                    highlight-current-row @current-change="onPick" @selection-change="onSel">
            <el-table-column type="selection" width="42" reserve-selection />
            <el-table-column label="任务" width="86">
              <template #default="{ row }">#{{ row.task.id }}<div class="dim2">{{ row.task.source }}</div></template>
            </el-table-column>
            <el-table-column label="内容" min-width="240">
              <template #default="{ row }">
                <div class="ttl">{{ row.post ? row.post.title : '（帖子已被删除或不是 post 对象）' }}</div>
                <div class="excerpt">
                  <template v-for="(seg, i) in segments(row)" :key="i">
                    <mark v-if="seg.hit">{{ seg.t }}</mark><span v-else>{{ seg.t }}</span>
                  </template>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="风险" width="86">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTone(row.task.riskLevel)">{{ row.task.riskLevel }}</el-tag>
                <div class="dim2">{{ score(row.task.riskScore) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="通道" width="86">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.task.channel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="116">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTone(row.task.status)">{{ row.task.status }}</el-tag>
                <div v-if="row.overdue" class="over">SLA 超时</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="176" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.task.status === 'PENDING'" size="small" @click.stop="claimOne(row)">认领</el-button>
                <template v-else-if="row.task.status === 'PROCESSING' && row.task.assigneeId === myId">
                  <el-button size="small" type="success" @click.stop="adjudicate(row, true, '')">通过</el-button>
                  <el-button size="small" type="danger" @click.stop="openReject(row)">驳回</el-button>
                </template>
                <span v-else-if="row.task.status === 'PROCESSING'" class="dim2">他人认领中</span>
                <span v-else class="dim2">已办结</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="pager">
            <span class="dim">共 {{ page.total }} 条 · 第 {{ page.page }} 页 · 每页 {{ page.size }}</span>
            <el-pagination small layout="prev, pager, next" :total="page.total" :page-size="page.size"
                           :current-page="page.page" @current-change="onPage" />
            <span class="kbd">快捷键：Y 通过 / N 驳回（焦点在输入框时不生效）</span>
          </div>
        </div>
      </el-col>

      <el-col :xs="24" :lg="8">
        <div class="mi-card side">
          <div class="rt">作者审核历史</div>
          <template v-if="picked">
            <p class="who">
              任务 #{{ picked.task.id }} · 作者
              <b>{{ picked.post ? (picked.post.authorNickname || ('#' + picked.post.authorId)) : '未知' }}</b>
              <span class="dim2">uid={{ picked.post ? picked.post.authorId : '—' }}</span>
            </p>
            <el-button size="small" :loading="side.loading" @click="loadAuthor">拉取该作者全部审核任务</el-button>
            <el-table v-if="side.rows.length" :data="side.rows" size="small" class="tbl">
              <el-table-column prop="id" label="#" width="56" />
              <el-table-column prop="status" label="状态" width="110">
                <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
              </el-table-column>
              <el-table-column prop="targetId" label="对象" width="70" />
              <el-table-column label="时间">
                <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
              </el-table-column>
            </el-table>
            <p v-else-if="side.error" class="err">{{ side.error }}</p>
            <p v-else-if="side.loading === false && !side.rows.length" class="dim">这位作者只有当前这一条审核任务。</p>
          </template>
          <p v-else class="dim">点左侧任意一行看作者历史（FR4.4：同作者反复违规要能看出来）。</p>

          <div class="rt sub">人均处理量</div>
          <el-table v-if="assignees.length" :data="assignees" size="small">
            <el-table-column prop="assigneeId" label="审核员" width="80" />
            <el-table-column prop="cnt" label="裁决" width="70" />
            <el-table-column prop="overdueCnt" label="超时" width="70" />
            <el-table-column label="平均">
              <template #default="{ row }">{{ row.avgSeconds }}s</template>
            </el-table-column>
          </el-table>
          <p v-else class="dim">还没有裁决记录。</p>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="dlg.show" :title="dlg.pass ? '批量通过' : '批量驳回'" width="520">
      <p class="dim">将裁决 {{ sel.length }} 条任务。驳回理由会写进 audit_record.reason、post_status_log.reason 与留痕 detail 三处，最长 500 字。</p>
      <el-input v-model="dlg.reason" type="textarea" :rows="3"
                :placeholder="dlg.pass ? '通过可以不填理由' : '驳回必填理由（空理由会被服务端 10001 拒）'" />
      <template #footer>
        <el-button @click="dlg.show = false">取消</el-button>
        <el-button :type="dlg.pass ? 'success' : 'danger'" :loading="dlg.busy" @click="submitBatch">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { AUDIT_STATUSES, RISK_LEVELS, auditAdjudicate, auditAssigneeStats, auditAuthorHistory, auditClaim, auditReleaseTimeout, auditSyncImages, auditSyncPosts, auditTasks } from '@/api/admin'
import { useAdminUserStore } from '@/stores/adminUser'
import { fmtTime, highlight, statusTone } from '@/utils/format'

const admin = useAdminUserStore()
const myId = computed(() => (admin.profile ? Number(admin.profile.id) : 0))

const f = reactive({ status: 'PENDING', riskLevel: '', overdueOnly: false, mine: false })
const rows = ref([])
const page = reactive({ total: 0, page: 1, size: 20 })
const busy = ref(false)
const sel = ref([])
const tbl = ref(null)
const picked = ref(null)
const notice = ref('')
const noticeTone = ref('success')
const op = reactive({ syncPosts: false, syncImages: false, release: false })
const assignees = ref([])
const side = reactive({ loading: false, rows: [], error: '' })
const dlg = reactive({ show: false, busy: false, pass: true, reason: '', targets: [] })

function say (text, tone) { notice.value = text; noticeTone.value = tone || 'success' }

async function reload () {
  busy.value = true
  const params = { page: page.page, size: page.size }
  if (f.status) params.status = f.status
  if (f.riskLevel) params.riskLevel = f.riskLevel
  if (f.overdueOnly) params.overdueOnly = true
  if (f.mine && myId.value) params.assigneeId = myId.value
  try {
    const data = await auditTasks(params)
    // TaskView 是嵌套视图：行上的 id 在 task 里，正文在 post 里，别按平铺取。
    rows.value = (data.list || []).map((v, i) => ({ ...v, rowKey: (v.task && v.task.id) || 'r' + i }))
    page.total = data.total
    if (tbl.value) tbl.value.clearSelection()
    sel.value = []
  } catch (e) {
    rows.value = []
    page.total = 0
    say('队列读取失败：' + (e.message || String(e)) + '（筛选值必须来自下拉，服务端对非法值直接 10001）', 'error')
  }
  busy.value = false
  loadAssignees()
}

async function loadAssignees () {
  try { assignees.value = await auditAssigneeStats() } catch (e) { assignees.value = [] }
}

function onPage (p) { page.page = p; reload() }
function onSel (v) { sel.value = v }
function onPick (row) { picked.value = row || null; side.rows = []; side.error = '' }

function segments (row) {
  const text = row.post ? (row.post.content || row.post.title || '') : ''
  return highlight(text, row.hitWords || [])
}
function score (v) {
  if (v === null || v === undefined) return '—'
  return Number(v).toFixed(2)
}

async function claimOne (row) {
  try {
    await auditClaim(row.task.id)
    say('已认领任务 #' + row.task.id + '，10 分钟内不裁决会自动回队')
    reload()
  } catch (e) {
    say('认领失败：' + (e.message || String(e)) + '（两人抢同一条时后到的必影响 0 行，这是 FR4.4 的证明）', 'error')
  }
}

async function claimBatch () {
  const ids = sel.value.map((r) => r.task.id)
  let done = 0
  let failed = ''
  for (const id of ids) {
    try { await auditClaim(id); done++ } catch (e) { failed = e.message || String(e); break }
  }
  say('批量认领：成功 ' + done + ' 条' + (failed ? '，在第 ' + (done + 1) + ' 条停下：' + failed : ''), failed ? 'warning' : 'success')
  reload()
}

function openAdjudicate (pass) {
  dlg.pass = pass
  dlg.reason = ''
  dlg.targets = sel.value.slice()
  dlg.show = true
}
function openReject (row) {
  dlg.pass = false
  dlg.reason = ''
  dlg.targets = [row]
  dlg.show = true
}

async function submitBatch () {
  dlg.busy = true
  const out = await adjudicateAll(dlg.targets, dlg.pass, dlg.reason)
  dlg.busy = false
  dlg.show = false
  say(out, out.indexOf('失败') >= 0 || out.indexOf('被拒') >= 0 ? 'warning' : 'success')
  reload()
}

async function adjudicateAll (targets, pass, reason) {
  let okCnt = 0
  const errs = []
  for (const row of targets) {
    try {
      await auditAdjudicate({ taskId: row.task.id, pass, reason: reason || '' })
      okCnt++
    } catch (e) {
      errs.push('#' + row.task.id + ' ' + (e.message || String(e)))
    }
  }
  if (!errs.length) return '裁决完成：' + (pass ? '通过' : '驳回') + ' ' + okCnt + ' 条，每条都写了 audit_record + post_status_log + admin_op_log。'
  return '裁决 ' + okCnt + ' 条成功，' + errs.length + ' 条失败：' + errs.slice(0, 3).join('；')
}

async function adjudicate (row, pass, reason) {
  const msg = await adjudicateAll([row], pass, reason)
  say(msg, msg.indexOf('失败') >= 0 ? 'warning' : 'success')
  reload()
}

async function loadAuthor () {
  const uid = picked.value && picked.value.post ? picked.value.post.authorId : null
  if (!uid) { side.error = '当前任务没有可识别的作者'; return }
  side.loading = true
  side.rows = []
  side.error = ''
  try {
    side.rows = await auditAuthorHistory(uid)
  } catch (e) {
    side.error = e.message || String(e)
  }
  side.loading = false
}

async function doSyncPosts () {
  op.syncPosts = true
  try {
    const s = await auditSyncPosts()
    say('同步帖子：扫描 ' + s.scanned + ' 条，新建任务 ' + s.created + ' 条，重复跳过 ' + s.duplicated + ' 条')
    reload()
  } catch (e) { say('同步失败：' + (e.message || String(e)), 'error') }
  op.syncPosts = false
}
async function doSyncImages () {
  op.syncImages = true
  try {
    const s = await auditSyncImages()
    say('同步图片：扫描 ' + s.scanned + '，新建 ' + s.created + '，重复 ' + s.duplicated)
    reload()
  } catch (e) { say('图片通道同步失败：' + (e.message || String(e)), 'error') }
  op.syncImages = false
}
async function doRelease () {
  op.release = true
  try {
    const n = await auditReleaseTimeout()
    say('释放超时认领：' + n + ' 条回到 PENDING')
    reload()
  } catch (e) { say('释放失败：' + (e.message || String(e)), 'error') }
  op.release = false
}

// 键盘裁决：焦点在输入框/文本域/下拉搜索里时必须不响应，否则审核员一边写驳回理由一边按 y 会把队列点穿。
function onKey (ev) {
  if (dlg.show) return
  const t = ev.target
  const tag = t && t.tagName ? t.tagName.toLowerCase() : ''
  if (tag === 'input' || tag === 'textarea' || tag === 'select' || (t && t.isContentEditable)) return
  if (ev.ctrlKey || ev.metaKey || ev.altKey) return
  const row = picked.value
  if (!row || row.task.status !== 'PROCESSING' || Number(row.task.assigneeId) !== myId.value) return
  const key = (ev.key || '').toLowerCase()
  if (key === 'y') { ev.preventDefault(); adjudicate(row, true, '') }
  else if (key === 'n') { ev.preventDefault(); openReject(row) }
}

onMounted(() => {
  reload()
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => { window.removeEventListener('keydown', onKey) })
</script>

<style scoped>
.bar { padding: 12px 16px 8px; }
.form :deep(.el-form-item) { margin-bottom: 8px; }
.ops { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding-bottom: 6px; }
.alert { margin-top: 12px; }
.body { margin-top: 14px; }
.ttl { font-weight: 700; font-size: 13px; }
.excerpt { font-size: 12px; color: var(--mi-text-dim); line-height: 1.6; }
.excerpt :deep(mark) { background: rgba(217, 83, 79, 0.28); color: #ffd9d6; border-radius: 3px; padding: 0 2px; }
.dim2 { font-size: 11px; color: var(--mi-text-dim); }
.over { font-size: 11px; color: #D9534F; }
.pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.kbd { font-size: 11px; color: var(--mi-text-dim); margin-left: auto; }
.rt { font-size: 14px; font-weight: 700; margin-bottom: 8px; }
.rt.sub { margin-top: 16px; }
.side .who { font-size: 13px; margin: 0 0 8px; }
.tbl { margin-top: 8px; }
.err { color: #D9534F; font-size: 12px; }
</style>