<template>
  <div class="users">
    <!-- A6 用户管理：检索 -> 档案 -> 处置（禁言/封禁/恢复）-> 高敏感操作（解匿） -->
    <div class="mi-card bar">
      <el-form inline @submit.prevent>
        <el-form-item label="关键词">
          <el-input v-model="f.keyword" maxlength="64" show-word-limit clearable
                    placeholder="昵称或用户名，模糊匹配" style="width: 240px" @keyup.enter="reload" />
        </el-form-item>
        <el-form-item label="账号状态">
          <el-select v-model="f.status" clearable placeholder="全部" style="width: 150px" @change="reload">
            <el-option v-for="s in USER_STATUSES" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button :loading="busy" @click="reload">查询</el-button>
          <el-button @click="reset">清空</el-button>
        </el-form-item>
      </el-form>
      <p class="dim">
        关键词上限 64 字、状态四值白名单都由服务端校验（<code>UserManageService.KEYWORD_MAX / STATUSES</code>）：
        非法筛选值当场 10001，而不是返回一张空表。列表页「筛错了但看起来一切正常」比报错更难被发现，所以宁可报错。
        出参里没有 password / email / avatar —— 查询只 select 展示列，前端也就无从泄露。
      </p>
    </div>

    <el-alert v-if="notice" :title="notice" :type="noticeTone" show-icon :closable="true" class="alert" @close="notice = ''" />

    <div class="mi-card head">
      <span class="k">全站累计解匿</span>
      <span class="v">{{ revealCnt === null ? '—' : fmtNum(revealCnt) }}</span>
      <span class="dim">FR8.4 要求「解匿相关的一切尝试 100% 留痕」，包含被拒绝的尝试。点右侧到日志页核对。</span>
      <el-button size="small" text @click="goRevealLogs">查看 REVEAL_ANONYMOUS 留痕</el-button>
    </div>

    <div class="mi-card list">
      <el-table :data="rows" size="small" height="420" empty-text="读不到列表，原因见上方提示" @row-click="openDetail">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="昵称" min-width="150">
          <template #default="{ row }">{{ row.nickname || '—' }}<div class="dim2">{{ row.username }}</div></template>
        </el-table-column>
        <el-table-column label="角色" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.role === 'USER' ? 'info' : 'warning'">{{ row.role }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="禁言至" width="130">
          <template #default="{ row }">{{ row.muteUntil ? fmtTime(row.muteUntil) : '—' }}</template>
        </el-table-column>
        <el-table-column label="最近登录" width="130">
          <template #default="{ row }">{{ fmtTime(row.lastLoginAt) }}</template>
        </el-table-column>
        <el-table-column label="注册来源" width="120" prop="regSource" />
        <el-table-column label="注册时间" width="130">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <span class="dim">共 {{ page.total }} 人 · 第 {{ page.page }} 页（点任意一行看档案与处置）</span>
        <el-pagination small layout="prev, pager, next, sizes" :total="page.total"
                       :page-size="page.size" :current-page="page.page" :page-sizes="[10,20,50]"
                       @current-change="onPage" @size-change="onSize" />
      </div>
    </div>

    <!-- 档案抽屉 -->
    <el-drawer v-model="drawer" size="660px" :title="'用户档案 #' + uid">
      <div v-if="detailBusy" class="dim">正在读取档案…</div>
      <p v-else-if="detailErr" class="err">档案读取失败：{{ detailErr }}</p>
      <template v-else-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="昵称">{{ detail.user.nickname || '—' }}</el-descriptions-item>
          <el-descriptions-item label="用户名">{{ detail.user.username }}</el-descriptions-item>
          <el-descriptions-item label="角色">{{ detail.user.role }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag size="small" :type="statusTone(detail.user.status)">{{ detail.user.status }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="禁言到期">{{ detail.user.muteUntil ? fmtTime(detail.user.muteUntil, true) : '未禁言' }}</el-descriptions-item>
          <el-descriptions-item label="注销冷静期至">{{ detail.user.deactivateAt ? fmtTime(detail.user.deactivateAt, true) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="物理清除时点">{{ detail.user.purgeAt ? fmtTime(detail.user.purgeAt, true) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="同意隐私政策">{{ detail.user.agreePrivacyAt ? fmtTime(detail.user.agreePrivacyAt, true) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="公开帖数">{{ fmtNum(detail.publicPostCnt) }}</el-descriptions-item>
          <el-descriptions-item label="累计获赞">{{ fmtNum(detail.receivedLikeCnt) }}</el-descriptions-item>
          <el-descriptions-item label="AI 风格">{{ detail.user.aiStyle || '—' }}</el-descriptions-item>
          <el-descriptions-item label="最近 IP">{{ detail.user.lastLoginIp || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="acts">
          <el-button type="warning" plain :disabled="detail.user.status === 'DELETED'" @click="openMute">禁言</el-button>
          <el-button type="danger" plain :disabled="detail.user.status === 'BANNED' || detail.user.status === 'DELETED'" @click="openPunish('ban')">封禁</el-button>
          <el-button type="success" plain :disabled="!['MUTED', 'BANNED'].includes(detail.user.status)" @click="openPunish('restore')">恢复</el-button>
          <span class="dim">理由必填，它会写进 admin_op_log 与当事人通知；禁言时长只有 1 / 7 / 30 天三个合法值。</span>
        </div>

        <h4 class="sec">危机工单历史（该用户相关的 alert_ticket，返回 {{ (detail.tickets || []).length }} 条）</h4>
        <el-table :data="detail.tickets || []" size="small" max-height="180" empty-text="该用户没有危机工单记录">
          <el-table-column prop="id" label="#" width="60" />
          <el-table-column label="级别" width="70">
            <template #default="{ row }"><el-tag size="small" :type="statusTone(row.level)">{{ row.level }}</el-tag></template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="建单" width="120">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="证据" min-width="180" show-overflow-tooltip prop="evidenceText" />
        </el-table>

        <h4 class="sec">匿名别名（{{ (detail.aliases || []).length }} 个）</h4>
        <el-table :data="detail.aliases || []" size="small" max-height="200" empty-text="该用户没有使用过匿名身份">
          <el-table-column prop="id" label="alias" width="70" />
          <el-table-column prop="aliasName" label="别名" min-width="140" />
          <el-table-column prop="scene" label="场景" width="90" />
          <el-table-column label="创建" width="120">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="解匿留痕" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.revealedLogId" size="small" type="danger">已解匿 #{{ row.revealedLogId }}</el-tag>
              <span v-else class="dim2">未解匿</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button v-if="isSuper" size="small" type="danger" @click="openReveal(row)">解匿</el-button>
              <el-button v-else size="small" text @click="openReveal(row)">接口自检（预期 10003）</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="dim">
          {{ isSuper
            ? '解匿会把「这个匿名别名背后是谁」写进日志正文，属高敏感操作：必须先有书面依据，操作完成后界面保留服务端原文提示。'
            : '当前角色不是 SUPER，界面上不显示「解匿」按钮，只留一个「接口自检」入口：权限的真相在后端那一句判断里，不在按钮藏没藏上。点它会拿到 10003，并留下一行 DENIED —— 这正是 FR8.4 要的「连被拒的尝试也留痕」。' }}
        </p>
      </template>
    </el-drawer>

    <!-- 禁言 -->
    <el-dialog v-model="muteDlg.show" title="禁言该用户" width="480px">
      <el-form label-width="90px">
        <el-form-item label="时长">
          <el-radio-group v-model="muteDlg.days">
            <el-radio-button v-for="d in MUTE_DAYS" :key="d" :value="d">{{ d }} 天</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="muteDlg.reason" type="textarea" :rows="3" maxlength="500" show-word-limit
                    placeholder="必填。会写进审计留痕并发给当事人" />
        </el-form-item>
      </el-form>
      <p class="dim">禁言只挡写操作（发帖 / 评论 / 私信），登录与浏览仍然可用——阶段 5 修过一次「闸门收紧到登录都不行」导致这条规则整条不可达。</p>
      <template #footer>
        <el-button @click="muteDlg.show = false">取消</el-button>
        <el-button type="warning" :loading="muteDlg.busy" @click="submitMute">确认禁言</el-button>
      </template>
    </el-dialog>

    <!-- 封禁 / 恢复 -->
    <el-dialog v-model="punishDlg.show" :title="punishDlg.kind === 'ban' ? '封禁账号' : '恢复账号'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="理由">
          <el-input v-model="punishDlg.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="必填" />
        </el-form-item>
      </el-form>
      <p class="dim">
        {{ punishDlg.kind === 'ban'
          ? '封禁会切断登录。用户自己发起的注销走的是另一条链路（30 天冷静期 + 到期物理清除），不要用封禁代替注销。'
          : '恢复只针对 MUTED / BANNED；对 ACTIVE 账号调用会拿到 10001。状态是并发写竞争的字段，若他人已改动会提示「账号状态已被变更」。' }}
      </p>
      <template #footer>
        <el-button @click="punishDlg.show = false">取消</el-button>
        <el-button :type="punishDlg.kind === 'ban' ? 'danger' : 'success'" :loading="punishDlg.busy" @click="submitPunish">
          {{ punishDlg.kind === 'ban' ? '确认封禁' : '确认恢复' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 解匿 -->
    <el-dialog v-model="revealDlg.show" title="匿名身份还原（解匿）" width="520px">
      <el-alert type="error" :closable="false" show-icon
                title="这一步会把匿名作者的真实身份写进日志正文，只有学校书面申请或涉及生命安全的依据才允许执行。" />
      <p class="alias">别名：<b>{{ revealDlg.aliasName }}</b>（alias #{{ revealDlg.aliasId }}）</p>
      <el-input v-model="revealDlg.reason" type="textarea" :rows="3" maxlength="500" show-word-limit
                placeholder="书面依据 / 事件编号，必填，一个字符都不能少" />
      <el-alert v-if="revealDlg.denied" class="denied" type="warning" :closable="false" show-icon>
        <template #title>后端拒绝：{{ revealDlg.denied }}</template>
        <div>
          <p class="dim">这次拒绝不是「什么都没发生」。<code>admin_op_log</code> 里已经多了一行
            <code>REVEAL_ANONYMOUS / DENIED</code>——先判资格、再判参数、最后才查记录存在与否，
            所以越权试探无论目标存不存在都会留痕。</p>
          <el-button size="small" type="warning" plain @click="goDeniedLog">去日志页看这条 DENIED</el-button>
        </div>
      </el-alert>
      <el-alert v-if="revealDlg.done" class="denied" type="error" :closable="false" show-icon>
        <template #title>已解匿：{{ revealDlg.done }}</template>
        <p class="dim">{{ revealDlg.notice }}</p>
      </el-alert>
      <template #footer>
        <el-button @click="revealDlg.show = false">关闭</el-button>
        <el-button :type="isSuper ? 'danger' : 'warning'" :loading="revealDlg.busy" @click="submitReveal">
          {{ isSuper ? '确认解匿' : '仍然发起（自检越权路径）' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  MUTE_DAYS, USER_STATUSES, userAliasOfPost, userBan, userDetail, userMute,
  userPage, userRestore, userRevealAnonymous, userRevealCount
} from '@/api/admin'
import { errText, fmtNum, fmtTime, statusTone } from '@/utils/format'
import { useAdminUserStore } from '@/stores/adminUser'

const route = useRoute()
const router = useRouter()
const admin = useAdminUserStore()
const isSuper = computed(() => admin.role === 'SUPER')

const notice = ref('')
const noticeTone = ref('success')
function say (text, tone) { notice.value = text; noticeTone.value = tone || 'success' }

const f = reactive({ keyword: '', status: '' })
const rows = ref([])
const page = reactive({ total: 0, page: 1, size: 20 })
const busy = ref(false)
const revealCnt = ref(null)

const drawer = ref(false)
const uid = ref(0)
const detail = ref(null)
const detailBusy = ref(false)
const detailErr = ref('')

const muteDlg = reactive({ show: false, busy: false, days: 7, reason: '' })
const punishDlg = reactive({ show: false, busy: false, kind: 'ban', reason: '' })
const revealDlg = reactive({
  show: false, busy: false, aliasId: 0, aliasName: '', reason: '', denied: '', done: '', notice: ''
})

async function reload () {
  busy.value = true
  const params = { page: page.page, size: page.size }
  if (f.keyword.trim()) params.keyword = f.keyword.trim()
  if (f.status) params.status = f.status
  try {
    const data = await userPage(params)
    rows.value = data.list || []
    page.total = Number(data.total || 0)
    if (!rows.value.length) {
      say('筛选结果确实为空（不是读取失败）：keyword=' + (params.keyword || '无') + '、status=' + (params.status || '全部'))
    }
  } catch (e) {
    rows.value = []
    page.total = 0
    say('列表读取失败：' + errText(e), 'error')
  }
  try { revealCnt.value = await userRevealCount() } catch (e) { revealCnt.value = null }
  busy.value = false
}

function reset () { f.keyword = ''; f.status = ''; page.page = 1; reload() }
function onPage (p) { page.page = p; reload() }
function onSize (s) { page.size = s; page.page = 1; reload() }

function goRevealLogs () { router.push('/logs?action=REVEAL_ANONYMOUS') }
function goDeniedLog () {
  const q = 'action=REVEAL_ANONYMOUS&result=DENIED'
  router.push('/logs?' + (admin.profile && admin.profile.id ? q + '&operatorId=' + admin.profile.id : q))
}

async function openDetail (row) {
  const id = typeof row === 'number' ? row : row.id
  uid.value = id
  drawer.value = true
  detail.value = null
  detailErr.value = ''
  detailBusy.value = true
  try {
    detail.value = await userDetail(id)
  } catch (e) {
    detailErr.value = errText(e)
  } finally {
    detailBusy.value = false
  }
}

async function afterPunish (msg, tone) {
  say(msg, tone)
  await openDetail(uid.value)
  await reload()
}

function openMute () { muteDlg.days = 7; muteDlg.reason = ''; muteDlg.show = true }

async function submitMute () {
  if (!muteDlg.reason.trim()) { say('禁言理由不能为空：处置必须能被追溯到人', 'warning'); return }
  muteDlg.busy = true
  try {
    const u = await userMute({ userId: uid.value, days: muteDlg.days, reason: muteDlg.reason.trim() })
    muteDlg.show = false
    await afterPunish('已禁言 ' + (u.nickname || u.username) + ' 共 ' + muteDlg.days + ' 天，到期 ' +
      fmtTime(u.muteUntil, true) + '（MuteExpiryJob 到期自动回收，不需要人工再点一次恢复）')
  } catch (e) {
    say('禁言失败：' + errText(e), 'error')
  }
  muteDlg.busy = false
}

function openPunish (kind) { punishDlg.kind = kind; punishDlg.reason = ''; punishDlg.show = true }

async function submitPunish () {
  if (!punishDlg.reason.trim()) { say('理由不能为空', 'warning'); return }
  punishDlg.busy = true
  const payload = { userId: uid.value, reason: punishDlg.reason.trim() }
  try {
    const u = punishDlg.kind === 'ban' ? await userBan(payload) : await userRestore(payload)
    punishDlg.show = false
    await afterPunish((punishDlg.kind === 'ban' ? '已封禁 #' : '已恢复 #') + u.id +
      '，当前状态 ' + u.status + '（BAN_USER / RESTORE_USER 动作码已留痕）')
  } catch (e) {
    say('处置失败：' + errText(e), 'error')
  }
  punishDlg.busy = false
}

function openReveal (row) {
  revealDlg.aliasId = row.id
  revealDlg.aliasName = row.aliasName
  revealDlg.reason = ''
  revealDlg.denied = ''
  revealDlg.done = ''
  revealDlg.notice = ''
  revealDlg.show = true
}

async function submitReveal () {
  if (!revealDlg.reason.trim()) { say('没有书面依据就不允许解匿，前端也不让你提交', 'warning'); return }
  revealDlg.busy = true
  revealDlg.denied = ''
  revealDlg.done = ''
  try {
    const r = await userRevealAnonymous({ aliasId: revealDlg.aliasId, reason: revealDlg.reason.trim() })
    revealDlg.done = (r.aliasName || revealDlg.aliasName) + ' → ' + (r.nickname || '（无昵称）') +
      '（user #' + r.userId + '），本次审计行 #' + r.opLogId
    revealDlg.notice = r.notice || ''
    await openDetail(uid.value)
    try { revealCnt.value = await userRevealCount() } catch (e) { revealCnt.value = null }
    say('解匿完成并留痕，审计行编号 #' + r.opLogId, 'warning')
  } catch (e) {
    // 10003 = 非 SUPER 越权；10001 = 理由为空。两种都必须能在界面上找到那行 DENIED。
    revealDlg.denied = errText(e)
    if (e.code !== 10003 && e.code !== 10001) say('解匿请求异常：' + errText(e), 'error')
  }
  revealDlg.busy = false
}

/** 从 A7 内容管理跳过来时带 postId：先把 postId 换成 aliasId，再直接开解匿弹窗。 */
async function revealFromPost (postId) {
  try {
    const aliasId = await userAliasOfPost(postId)
    if (!aliasId) { say('帖子 #' + postId + ' 不是匿名帖，没有可解匿的别名', 'info'); return }
    const hit = { id: Number(aliasId), aliasName: '别名 #' + aliasId }
    if (detail.value) {
      const known = (detail.value.aliases || []).find((a) => Number(a.id) === Number(aliasId))
      if (known) { hit.aliasName = known.aliasName }
    }
    openReveal(hit)
  } catch (e) {
    say('按帖子反查别名失败：' + errText(e), 'error')
  }
}

onMounted(() => {
  if (route.query.status) f.status = String(route.query.status)
  if (route.query.keyword) f.keyword = String(route.query.keyword)
  reload()
  if (route.query.userId) openDetail(Number(route.query.userId))
  if (route.query.revealPostId) revealFromPost(Number(route.query.revealPostId))
})

defineExpose({ revealFromPost })
</script>

<style scoped>
.bar { padding: 12px 16px 0; }
.head { display: flex; align-items: center; gap: 12px; margin-top: 12px; padding: 12px 18px; }
.head .k { font-size: 13px; color: var(--mi-text-dim); }
.head .v { font-size: 22px; font-weight: 700; color: #D9534F; }
.head .dim { margin-left: 6px; }
.alert { margin-top: 12px; }
.list { margin-top: 12px; }
.pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.dim { font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); margin: 6px 0 0; }
.dim2 { font-size: 11px; color: var(--mi-text-dim); }
.err { color: #D9534F; font-size: 13px; }
.sec { margin: 20px 0 8px; font-size: 14px; font-weight: 700; }
.acts { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 14px; }
.acts .dim { flex: 1; min-width: 240px; margin: 0; }
.alias { margin: 12px 0 8px; font-size: 13px; }
.denied { margin-top: 12px; }
code { background: rgba(0, 0, 0, 0.06); padding: 1px 5px; border-radius: 4px; font-size: 12px; }
</style>