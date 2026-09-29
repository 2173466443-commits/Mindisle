<template>
  <div class="content">
    <el-tabs v-model="tab" class="mi-card tabs" @tab-change="onTab">
      <!-- ============================ 帖子 ============================ -->
      <el-tab-pane name="posts">
        <template #label>帖子 <el-badge v-if="posts.length" :value="page.total" type="info" class="bdg" /></template>
        <div class="bar">
          <el-form inline @submit.prevent>
            <el-form-item label="关键词">
              <el-input v-model="pf.keyword" maxlength="64" clearable placeholder="标题或正文，模糊匹配"
                        style="width: 220px" @keyup.enter="reloadPosts" />
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="pf.status" clearable placeholder="全部" style="width: 170px" @change="reloadPosts">
                <el-option v-for="s in POST_STATUSES" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="作者 ID">
              <el-input v-model="pf.userId" clearable placeholder="精确到一个人" style="width: 120px" @keyup.enter="reloadPosts" />
            </el-form-item>
            <el-form-item>
              <el-button :loading="pBusy" @click="reloadPosts">查询</el-button>
              <el-button @click="resetPosts">清空</el-button>
            </el-form-item>
          </el-form>
          <p class="dim">
            可筛状态只有 {{ POST_STATUSES.join(' / ') }}：DRAFT 是作者草稿、DELETED 走注销冷静期链路，
            管理台刻意检索不到它们（否则「恢复」一笔就等于绕过用户自己的注销流程）。
            按作者 ID 精确筛是必需的：目标贴往往不在第 1 页。
          </p>
        </div>
        <el-alert v-if="notice" :title="notice" :type="noticeTone" show-icon :closable="true" class="alert" @close="notice = ''" />
        <el-table :data="posts" size="small" height="400" empty-text="读不到列表，原因见上方提示" @row-click="openChain">
          <el-table-column prop="id" label="#" width="66" />
          <el-table-column label="标题 / 正文" min-width="240">
            <template #default="{ row }">
              <div class="tt">{{ row.title || '（无题）' }}</div>
              <div class="dim2 ellipsis">{{ row.content }}</div>
            </template>
          </el-table-column>
          <el-table-column label="作者" width="120">
            <template #default="{ row }">
              {{ row.isAnonymous ? '匿名树洞' : 'uid=' + row.userId }}
              <div class="dim2">{{ row.isAnonymous ? 'anon' : '' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="140">
            <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="风险" width="80">
            <template #default="{ row }"><el-tag size="small" :type="statusTone(row.riskLevel)" effect="plain">{{ row.riskLevel }}</el-tag></template>
          </el-table-column>
          <el-table-column label="互动" width="130">
            <template #default="{ row }">赞 {{ row.likeCnt }} · 评 {{ row.commentCnt }} · 举 {{ row.reportCnt }}</template>
          </el-table-column>
          <el-table-column label="标记" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.isTop" size="small" type="warning" effect="plain">置顶</el-tag>
              <el-tag v-if="row.isFeatured" size="small" type="success" effect="plain">加精</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="120">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <span class="dim">共 {{ page.total }} 帖 · 第 {{ page.page }} 页（点一行看完整流转链）</span>
          <el-pagination small layout="prev, pager, next, sizes" :total="page.total" :page-size="page.size"
                         :current-page="page.page" :page-sizes="[10,20,50]" @current-change="onPostPage" @size-change="onPostSize" />
        </div>
      </el-tab-pane>

      <!-- ============================ 举报 ============================ -->
      <el-tab-pane name="reports">
        <template #label>举报 <el-badge v-if="reportPending !== null" :value="reportPending" type="danger" class="bdg" /></template>
        <div class="bar">
          <el-form inline @submit.prevent>
            <el-form-item label="状态">
              <el-select v-model="rf.status" clearable placeholder="全部" style="width: 150px" @change="reloadReports">
                <el-option v-for="s in REPORT_STATUSES" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item label="对象类型">
              <el-select v-model="rf.targetType" clearable placeholder="全部" style="width: 140px" @change="reloadReports">
                <el-option v-for="s in REPORT_TARGET_TYPES" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item><el-button :loading="rBusy" @click="reloadReports">刷新</el-button></el-form-item>
          </el-form>
          <p class="dim">
            队列里能筛出 comment 举报，但本期<strong>办结只对 post 开放</strong>：评论折叠还没有处置入口，
            服务端会回 10001「本期只开放帖子举报处置」。采纳举报＝顺带下架被举报帖，
            回执原文就是这里填写的处置说明。举报行先落地再改帖状态，是为了不留下
            「内容已经没了却还在等人处理」的僵尸行。
          </p>
        </div>
        <el-table :data="reports" size="small" height="400" empty-text="读不到列表，原因见上方提示" @row-click="openReportDlg">
          <el-table-column prop="report.id" label="#" width="66" />
          <el-table-column label="被举报内容" min-width="220">
            <template #default="{ row }">
              {{ row.postTitle || ('#' + row.report.targetId) }}
              <div class="dim2">{{ row.report.targetType }} · {{ row.description }}</div>
            </template>
          </el-table-column>
          <el-table-column label="举报人" width="140">
            <template #default="{ row }">{{ row.reporterNickname || '—' }}<div class="dim2">uid={{ row.report.reporterId }}</div></template>
          </el-table-column>
          <el-table-column label="理由" width="120" prop="report.reason" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><el-tag size="small" :type="statusTone(row.report.status)">{{ row.report.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="时间" width="120">
            <template #default="{ row }">{{ fmtTime(row.report.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="row.report.status === 'PENDING'" size="small" type="primary" @click.stop="openReportDlg(row)">处置</el-button>
              <span v-else class="dim2">已办结</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <span class="dim">共 {{ rPage.total }} 条 · 待处置 {{ reportPending === null ? '—' : reportPending }} 条</span>
          <el-pagination small layout="prev, pager, next" :total="rPage.total" :page-size="rPage.size"
                         :current-page="rPage.page" @current-change="onReportPage" />
        </div>
      </el-tab-pane>

      <!-- ============================ 申诉裁定 ============================ -->
      <el-tab-pane name="appeals">
        <template #label>申诉裁定 <el-badge v-if="appealPending !== null" :value="appealPending" type="warning" class="bdg" /></template>
        <div class="bar">
          <el-form inline @submit.prevent>
            <el-form-item label="状态">
              <el-select v-model="af.status" clearable placeholder="全部" style="width: 150px" @change="reloadAppeals">
                <el-option v-for="s in APPEAL_STATUSES" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item><el-button :loading="aBusy" @click="reloadAppeals">刷新</el-button></el-form-item>
          </el-form>
          <p class="dim">
            申诉是<strong>一次性</strong>的：同一篇帖只有第一次申诉会被受理，之后再提会被拒；
            裁定也只有一次机会。采纳＝APPEALING → PUBLISHED；驳回＝回到「进入 APPEALING 之前的那个状态」
            （由 post_status_log 反查，查不到才回落 REJECTED）。裁定说明会作为回执原文发给申诉人。
          </p>
        </div>
        <el-table :data="appeals" size="small" height="400" empty-text="读不到列表，原因见上方提示" @row-click="openAppealDlg">
          <el-table-column prop="appeal.id" label="#" width="66" />
          <el-table-column label="帖子" min-width="200">
            <template #default="{ row }">
              {{ row.postTitle || ('post #' + row.appeal.postId) }}
              <div class="dim2">帖当前状态：{{ row.postStatus }}</div>
            </template>
          </el-table-column>
          <el-table-column label="申诉人" width="110">
            <template #default="{ row }">uid={{ row.appeal.userId }}</template>
          </el-table-column>
          <el-table-column label="申诉理由" min-width="200" show-overflow-tooltip prop="appeal.reason" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><el-tag size="small" :type="statusTone(row.appeal.status)">{{ row.appeal.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="提交时间" width="120">
            <template #default="{ row }">{{ fmtTime(row.appeal.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="row.appeal.status === 'PENDING'" size="small" type="primary" @click.stop="openAppealDlg(row)">裁定</el-button>
              <span v-else class="dim2">{{ row.appeal.status }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <span class="dim">共 {{ aPage.total }} 条 · 待裁定 {{ appealPending === null ? '—' : appealPending }} 条</span>
          <el-pagination small layout="prev, pager, next" :total="aPage.total" :page-size="aPage.size"
                         :current-page="aPage.page" @current-change="onAppealPage" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 流转链抽屉 -->
    <el-drawer v-model="chainDlg" size="700px" :title="'内容流转链 · 帖子 #' + curPostId">
      <div v-if="chainBusy" class="dim">正在读取流转链…</div>
      <p v-else-if="chainErr" class="err">流转链读取失败：{{ chainErr }}</p>
      <template v-else-if="chain">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="标题">{{ chain.post.title || '（无题）' }}</el-descriptions-item>
          <el-descriptions-item label="作者">{{ chain.authorNickname || '—' }}（uid={{ chain.post.userId }}）</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag size="small" :type="statusTone(chain.post.status)">{{ chain.post.status }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="风险"><el-tag size="small" :type="statusTone(chain.post.riskLevel)" effect="plain">{{ chain.post.riskLevel }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="情绪">{{ chain.post.emotionPrimary || '—' }} / {{ Number(chain.post.emotionScore || 0).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="匿名">{{ chain.post.isAnonymous ? '是（alias #' + chain.post.aliasId + '）' : '否' }}</el-descriptions-item>
        </el-descriptions>
        <div class="mi-card inner evidence">{{ chain.post.content }}</div>

        <div class="acts">
          <el-button type="danger" plain :disabled="chain.post.status === 'TAKEDOWN'" @click="openPostAction('takedown')">下架</el-button>
          <el-button type="success" plain :disabled="chain.post.status !== 'TAKEDOWN'" @click="openPostAction('restore')">恢复可见</el-button>
          <el-button plain @click="toggleFlag('top', !chain.post.isTop)">{{ chain.post.isTop ? '取消置顶' : '置顶' }}</el-button>
          <el-button plain @click="toggleFlag('feature', !chain.post.isFeatured)">{{ chain.post.isFeatured ? '取消加精' : '加精' }}</el-button>
          <el-button v-if="chain.post.isAnonymous" size="small" type="warning" plain @click="goReveal">按这篇去解匿</el-button>
        </div>
        <p class="dim">
          「恢复可见」只允许 TAKEDOWN → PUBLISHED：人审判定违规（REJECTED）的帖子必须走申诉队列，
          管理员在这里点一下就越过了作者唯一的那一次申诉机会。置顶/加精不改状态、也不写状态流转日志，
          只留 POST_TOP / POST_FEATURE 审计。
        </p>

        <h4 class="sec">状态流转（post_status_log · {{ (chain.statusLogs || []).length }} 条）</h4>
        <el-timeline v-if="(chain.statusLogs || []).length">
          <el-timeline-item v-for="s in chain.statusLogs" :key="s.id" :timestamp="fmtTime(s.createdAt, true)" placement="top">
            <b>{{ s.fromStatus || '—' }} → {{ s.toStatus }}</b>
            <div class="dim2">操作人 uid={{ s.operatorId || '系统' }} · {{ s.reason }}</div>
          </el-timeline-item>
        </el-timeline>
        <p v-else class="dim">没有状态流转记录：这条帖子从发布起没被改过状态。</p>

        <h4 class="sec">审核留痕（audit_record · {{ (chain.auditRecords || []).length }} 条）</h4>
        <el-table :data="chain.auditRecords || []" size="small" max-height="200" empty-text="无审核留痕">
          <el-table-column prop="id" label="#" width="56" />
          <el-table-column label="通道" width="90" prop="channel" />
          <el-table-column label="结论" width="130" prop="decision" />
          <el-table-column label="风险分" width="80">
            <template #default="{ row }">{{ Number(row.riskScore || 0).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="词库/模型版本" width="160">
            <template #default="{ row }">{{ row.wordlibVersion || '—' }} / {{ row.modelVersion || '—' }}</template>
          </el-table-column>
          <el-table-column label="命中词" min-width="120" show-overflow-tooltip prop="hitWords" />
        </el-table>

        <h4 class="sec">举报（{{ (chain.reports || []).length }} 条）与申诉（{{ (chain.appeals || []).length }} 条）</h4>
        <div class="two">
          <el-table :data="chain.reports || []" size="small" max-height="160" empty-text="无举报">
            <el-table-column prop="id" label="#" width="50" />
            <el-table-column prop="reason" label="理由" width="100" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column label="时间">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <el-table :data="chain.appeals || []" size="small" max-height="160" empty-text="无申诉">
            <el-table-column prop="id" label="#" width="50" />
            <el-table-column prop="reason" label="理由" min-width="120" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }"><el-tag size="small" :type="statusTone(row.status)">{{ row.status }}</el-tag></template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-drawer>

    <!-- 下架 / 恢复 -->
    <el-dialog v-model="actDlg.show" :title="actDlg.kind === 'takedown' ? '下架帖子 #' + curPostId : '恢复可见 · 帖子 #' + curPostId" width="480px">
      <el-input v-model="actDlg.reason" type="textarea" :rows="3" maxlength="500" show-word-limit
                placeholder="理由必填：它同时是通知文案、状态日志与审计正文" />
      <p class="dim">
        {{ actDlg.kind === 'takedown'
          ? '下架会立即对读者不可见，并给作者发通知；作者随后有一次申诉机会。'
          : '只有已下架的帖子能恢复。若状态已被他人改动，本次会拿到「帖子已经处于目标状态」或直接被 CAS 挡下。' }}
      </p>
      <template #footer>
        <el-button @click="actDlg.show = false">取消</el-button>
        <el-button :type="actDlg.kind === 'takedown' ? 'danger' : 'success'" :loading="actDlg.busy" @click="submitPostAction">
          确认{{ actDlg.kind === 'takedown' ? '下架' : '恢复' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 举报处置 -->
    <el-dialog v-model="repDlg.show" title="处置举报" width="500px">
      <p class="rep">举报 #{{ repDlg.id }} · 对象 {{ repDlg.targetType }} #{{ repDlg.targetId }} · 理由 {{ repDlg.reason }}</p>
      <el-radio-group v-model="repDlg.accepted" class="rg">
        <el-radio :value="true">采纳（并下架被举报帖）</el-radio>
        <el-radio :value="false">不采纳（内容保留）</el-radio>
      </el-radio-group>
      <el-input v-model="repDlg.note" type="textarea" :rows="3" maxlength="500" show-word-limit
                placeholder="处置说明必填，回执原文发给举报人" />
      <template #footer>
        <el-button @click="repDlg.show = false">取消</el-button>
        <el-button type="primary" :loading="repDlg.busy" @click="submitReport">提交处置</el-button>
      </template>
    </el-dialog>

    <!-- 申诉裁定 -->
    <el-dialog v-model="aplDlg.show" title="裁定申诉" width="500px">
      <p class="rep">申诉 #{{ aplDlg.id }} · 帖子 #{{ aplDlg.postId }} · 申诉人 uid={{ aplDlg.userId }}</p>
      <div class="mi-card inner quote">申诉理由：{{ aplDlg.reason }}</div>
      <el-radio-group v-model="aplDlg.accepted" class="rg">
        <el-radio :value="true">采纳（帖子恢复 PUBLISHED）</el-radio>
        <el-radio :value="false">驳回（回到申诉前状态）</el-radio>
      </el-radio-group>
      <el-input v-model="aplDlg.note" type="textarea" :rows="3" maxlength="500" show-word-limit
                placeholder="裁定说明必填，回执原文发给申诉人" />
      <p class="dim">裁定只有这一次机会：办结后同一申诉再提交会拿到「已经被裁定过了」。</p>
      <template #footer>
        <el-button @click="aplDlg.show = false">取消</el-button>
        <el-button type="primary" :loading="aplDlg.busy" @click="submitAppeal">提交裁定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  APPEAL_STATUSES, POST_STATUSES, REPORT_STATUSES, REPORT_TARGET_TYPES,
  appealAdjudicate, appealPage, appealPendingCount, contentChain, postFlag, postPage,
  postRestore, postTakedown, reportHandle, reportPage, reportPendingCount
} from '@/api/admin'
import { errText, fmtTime, statusTone } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const tab = ref('posts')
const notice = ref('')
const noticeTone = ref('success')
function say (text, tone) { notice.value = text; noticeTone.value = tone || 'success' }

/* ------------------------------- 帖子 ------------------------------- */
const posts = ref([])
const pf = reactive({ keyword: '', status: '', userId: '' })
const page = reactive({ total: 0, page: 1, size: 20 })
const pBusy = ref(false)
const chain = ref(null)
const chainDlg = ref(false)
const chainBusy = ref(false)
const chainErr = ref('')
const curPostId = ref(0)
const actDlg = reactive({ show: false, busy: false, kind: 'takedown', reason: '' })

async function reloadPosts () {
  pBusy.value = true
  const params = { page: page.page, size: page.size }
  if (pf.keyword.trim()) params.keyword = pf.keyword.trim()
  if (pf.status) params.status = pf.status
  if (String(pf.userId).trim()) params.userId = Number(String(pf.userId).trim())
  try {
    const data = await postPage(params)
    posts.value = data.list || []
    page.total = Number(data.total || 0)
    if (!posts.value.length) say('当前条件下确实没有帖子（不是读取失败）')
  } catch (e) {
    posts.value = []
    page.total = 0
    say('帖子列表读取失败：' + errText(e), 'error')
  } finally { pBusy.value = false }
}
function resetPosts () { pf.keyword = ''; pf.status = ''; pf.userId = ''; page.page = 1; reloadPosts() }
function onPostPage (p) { page.page = p; reloadPosts() }
function onPostSize (s) { page.size = s; page.page = 1; reloadPosts() }

async function openChain (row) {
  const id = typeof row === 'number' ? row : row.id
  curPostId.value = id
  chainDlg.value = true
  chain.value = null
  chainErr.value = ''
  chainBusy.value = true
  try {
    chain.value = await contentChain(id)
  } catch (e) {
    chainErr.value = errText(e)
  } finally { chainBusy.value = false }
}

function openPostAction (kind) { actDlg.kind = kind; actDlg.reason = ''; actDlg.show = true }

async function submitPostAction () {
  if (!actDlg.reason.trim()) { say('理由不能为空：它是通知、状态日志与审计的共同来源', 'warning'); return }
  actDlg.busy = true
  const payload = { postId: curPostId.value, reason: actDlg.reason.trim() }
  try {
    const out = actDlg.kind === 'takedown' ? await postTakedown(payload) : await postRestore(payload)
    actDlg.show = false
    say('帖子 #' + out.postId + '：' + out.fromStatus + ' → ' + out.toStatus +
      (out.changed ? '，状态已改写并写 post_status_log' : '，状态未变（可能已被他人改动）') + '，审计行 #' + out.opLogId)
    await openChain(curPostId.value)
    await reloadPosts()
  } catch (e) {
    say('处置失败：' + errText(e), 'error')
  }
  actDlg.busy = false
}

async function toggleFlag (flag, on) {
  try {
    const out = await postFlag(curPostId.value, flag, on)
    say((flag === 'top' ? '置顶' : '加精') + (out.on ? ' 已开启' : ' 已取消') + ' 帖子 #' + out.postId +
      '（不改状态、不写状态日志，只留 POST_' + (flag === 'top' ? 'TOP' : 'FEATURE') + ' 审计 #' + out.opLogId + '）')
    await openChain(curPostId.value)
    await reloadPosts()
  } catch (e) { say('标记失败：' + errText(e), 'error') }
}

function goReveal () {
  router.push('/users?revealPostId=' + curPostId.value)
}

/* ------------------------------- 举报 ------------------------------- */
const reports = ref([])
const rf = reactive({ status: 'PENDING', targetType: '' })
const rPage = reactive({ total: 0, page: 1, size: 20 })
const rBusy = ref(false)
const reportPending = ref(null)
const repDlg = reactive({ show: false, busy: false, id: 0, targetType: '', targetId: 0, reason: '', accepted: true, note: '' })

async function reloadReports () {
  rBusy.value = true
  const params = { page: rPage.page, size: rPage.size }
  if (rf.status) params.status = rf.status
  if (rf.targetType) params.targetType = rf.targetType
  try {
    const data = await reportPage(params)
    reports.value = data.list || []
    rPage.total = Number(data.total || 0)
  } catch (e) {
    reports.value = []
    rPage.total = 0
    say('举报列表读取失败：' + errText(e), 'error')
  }
  try { reportPending.value = await reportPendingCount() } catch (e) { reportPending.value = null }
  rBusy.value = false
}
function onReportPage (p) { rPage.page = p; reloadReports() }

function openReportDlg (row) {
  const r = row.report || row
  repDlg.id = r.id
  repDlg.targetType = r.targetType
  repDlg.targetId = r.targetId
  repDlg.reason = r.reason
  repDlg.accepted = true
  repDlg.note = ''
  repDlg.show = true
}

async function submitReport () {
  if (!repDlg.note.trim()) { say('处置说明不能为空：它就是发给举报人的回执原文', 'warning'); return }
  repDlg.busy = true
  try {
    const out = await reportHandle({ reportId: repDlg.id, accepted: repDlg.accepted, note: repDlg.note.trim() })
    repDlg.show = false
    say('举报 #' + repDlg.id + ' 已办结为 ' + out.report.status +
      (out.postChanged ? '，帖子 #' + out.report.postId + ' 状态改为 ' + out.postStatus : '，帖子状态未改') +
      '，审计行 #' + out.opLogId)
    await reloadReports()
  } catch (e) {
    say('处置失败：' + errText(e), 'error')
    await reloadReports()
  }
  repDlg.busy = false
}

/* ------------------------------- 申诉 ------------------------------- */
const appeals = ref([])
const af = reactive({ status: 'PENDING' })
const aPage = reactive({ total: 0, page: 1, size: 20 })
const aBusy = ref(false)
const appealPending = ref(null)
const aplDlg = reactive({ show: false, busy: false, id: 0, postId: 0, userId: 0, reason: '', accepted: false, note: '' })

async function reloadAppeals () {
  aBusy.value = true
  const params = { page: aPage.page, size: aPage.size }
  if (af.status) params.status = af.status
  try {
    const data = await appealPage(params)
    appeals.value = data.list || []
    aPage.total = Number(data.total || 0)
  } catch (e) {
    appeals.value = []
    aPage.total = 0
    say('申诉列表读取失败：' + errText(e), 'error')
  }
  try { appealPending.value = await appealPendingCount() } catch (e) { appealPending.value = null }
  aBusy.value = false
}
function onAppealPage (p) { aPage.page = p; reloadAppeals() }

function openAppealDlg (row) {
  const a = row.appeal || row
  aplDlg.id = a.id
  aplDlg.postId = a.postId
  aplDlg.userId = a.userId
  aplDlg.reason = a.reason
  aplDlg.accepted = false
  aplDlg.note = ''
  aplDlg.show = true
}

async function submitAppeal () {
  if (!aplDlg.note.trim()) { say('裁定说明不能为空', 'warning'); return }
  aplDlg.busy = true
  try {
    const out = await appealAdjudicate({ appealId: aplDlg.id, accepted: aplDlg.accepted, note: aplDlg.note.trim() })
    aplDlg.show = false
    say('申诉 #' + out.appealId + ' 已裁定为 ' + out.appealStatus + '，帖子 #' + out.postId +
      ' 现为 ' + out.postStatus + (out.postChanged ? '（状态已改写）' : '（状态未变）') + '，审计行 #' + out.opLogId)
    await reloadAppeals()
  } catch (e) {
    say('裁定失败：' + errText(e), 'error')
    await reloadAppeals()
  }
  aplDlg.busy = false
}

function onTab (name) {
  if (name === 'reports' && !reports.value.length) reloadReports()
  if (name === 'appeals' && !appeals.value.length) reloadAppeals()
}

onMounted(() => {
  if (route.query.tab) tab.value = String(route.query.tab)
  if (route.query.status) pf.status = String(route.query.status)
  if (route.query.userId) pf.userId = String(route.query.userId)
  reloadPosts()
  if (tab.value === 'reports') reloadReports()
  if (tab.value === 'appeals') reloadAppeals()
  appealPendingCount().then((v) => { appealPending.value = v }).catch(() => {})
  reportPendingCount().then((v) => { reportPending.value = v }).catch(() => {})
})
</script>

<style scoped>
.tabs { padding: 6px 16px 12px; }
.bdg { margin-left: 6px; }
.bar { padding: 4px 0 0; }
.alert { margin: 10px 0; }
.pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.dim { font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); margin: 6px 0 0; }
.dim2 { font-size: 11px; color: var(--mi-text-dim); }
.err { color: #D9534F; font-size: 13px; }
.tt { font-size: 13px; font-weight: 600; }
.ellipsis { max-width: 340px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sec { margin: 20px 0 8px; font-size: 14px; font-weight: 700; }
.inner { margin: 12px 0; padding: 12px; }
.evidence { font-size: 13px; line-height: 1.9; white-space: pre-wrap; max-height: 180px; overflow: auto; }
.quote { font-size: 13px; line-height: 1.8; }
.acts { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 14px; }
.two { display: flex; gap: 12px; flex-wrap: wrap; }
.two > * { flex: 1; min-width: 260px; }
.rep { margin: 0 0 10px; font-size: 13px; }
.rg { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; margin: 10px 0; }
</style>