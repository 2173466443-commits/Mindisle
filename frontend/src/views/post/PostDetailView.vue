<template>
  <div class="detail">
    <div v-if="loading" class="mi-card">
      <el-skeleton :rows="5" animated />
    </div>

    <div v-else-if="notFound" class="mi-card gone">
      <h2>{{ goneTitle }}</h2>
      <p class="gone-desc">{{ goneDesc }}</p>
      <p class="gone-note">后端口径：不存在、已删除、以及「存在但你没权限看」都统一返 30001，
        所以这里不能写成「内容不存在」——那样等于把私密帖的存在性泄露给任何人。</p>
      <el-button type="primary" @click="$router.push({ name: 'feed' })">回广场</el-button>
    </div>

    <div v-else-if="!post" class="mi-card">
      <stage-notice :code="errorCode || 'network'" stage="3" api-name="GET /api/posts/{id}"
                    extra="详情接口没取到数据。原因见上：多半是后端未启动或数据库不可用，不是这条帖子被删了。" />
      <el-button @click="load">重新加载</el-button>
    </div>

    <article v-else class="mi-card body">
      <header class="head">
        <el-tag :type="typeTag" size="small" effect="dark">{{ typeLabel }}</el-tag>
        <span class="who">{{ post.displayName || '屿友' }}</span>
        <el-tag v-if="post.anonymous" size="small" type="info">匿名</el-tag>
        <el-tag v-if="post.visibility === 'private'" size="small" type="info">仅自己可见</el-tag>
        <span class="dot">·</span>
        <span class="time">{{ shownAt }}</span>
        <span v-if="destroyLine" class="destroy">{{ destroyLine }}</span>
      </header>

      <h1 class="title">{{ post.title }}</h1>

      <el-alert v-if="post.auditTip" type="warning" :closable="false" show-icon class="tip" :title="post.auditTip" />

      <div class="content">{{ post.content }}</div>

      <div v-if="post.images && post.images.length" class="imgs">
        <el-image v-for="(img, i) in post.images" :key="i" :src="img.url" :preview-src-list="previewList"
                  :initial-index="i" fit="cover" class="img" preview-teleported hide-on-click-modal />
      </div>

      <div v-if="post.topics && post.topics.length" class="topics">
        <span v-for="t in post.topics" :key="t" class="topic"># {{ t }}</span>
      </div>

      <crisis-card v-if="post.hotline" :hotline="post.hotline" />

      <footer class="meta">
        <span class="stat">浏览 {{ fmtCount(post.viewCnt) }}</span>
        <span class="stat">评论 {{ fmtCount(post.commentCnt) }}</span>
        <span class="dim">发布于 {{ fmtDateTime(post.publishedAt) }}</span>
        <span v-if="post.createdAt && post.publishedAt !== post.createdAt" class="dim">
          创建于 {{ fmtDateTime(post.createdAt) }}
        </span>
      </footer>

      <!-- 点赞/收藏已经接上真接口（T3.6），这条互动条就是它的落点：
           详情页是「一个人反复进出同一帖」的地方，所以按钮态一律用后端回执初始化，不做本地记忆。
           相似帖推荐（FR5.6 / 阶段 6）确实还没有接口，这里留空而不是摆假数据。 -->
      <div class="acts">
        <el-button class="act" :class="{ 'act-on': post.liked }" :type="post.liked ? 'primary' : 'default'"
                   size="small" round :plain="!post.liked" :disabled="isBusy(post, 'like')"
                   @click="act(post, 'like')">
          {{ post.liked ? '已赞' : '赞' }} {{ fmtCount(post.likeCnt) }}
        </el-button>
        <el-button class="act" :class="{ 'act-on': post.collected }" :type="post.collected ? 'primary' : 'default'"
                   size="small" round :plain="!post.collected" :disabled="isBusy(post, 'collect')"
                   @click="act(post, 'collect')">
          {{ post.collected ? '已收藏' : '收藏' }} {{ fmtCount(post.collectCnt) }}
        </el-button>
        <!-- 举报入口（T3.11 · FR4.7）。自己的帖不给这个按钮：后端那句「不能举报自己的内容」是硬闸，
             前端只是不给人一个注定吃 400 的按钮。匿名帖的回执不带 authorId，所以那种情况判不出来，
             也无需判 —— 兜底在后端，界面这里宁可多给一次点击。 -->
        <el-button v-if="!isMyPost" class="act act-report" size="small" round plain
                   @click="openReport">举报</el-button>
        <span class="acts-note">计数由后端按真实互动记录重算，与广场卡片上看到的应是同一个数。</span>
      </div>

      <!-- 回执就地显示、不弹 toast：tip 里那句「目前有 N 个人举报过它」是要让人读完的话，
           一闪而过的浮层会把读完的机会拿走。hotline 非空即「必须挂求助卡片」，全站同一契约。 -->
      <div v-if="reportTip" class="report-receipt">
        <el-alert type="success" :closable="false" show-icon :title="reportTip" />
        <crisis-card v-if="reportHotline" :hotline="reportHotline"
                     text="你替 TA 担心，也别忘了自己：这个电话 24 小时有人接" />
      </div>
    </article>

    <!-- 评论区（任务 T3.7 · U4）：单独一张卡。未登录、加载失败、空列表三种状态由组件自己画，
         详情页不参与——同一句「看不到评论」在三种情况下的成因完全不同，混在父页面里判就容易判错。 -->
    <comment-section v-if="post" :post-id="postId" :published-count="post.commentCnt" @published="onCommentPublished" />

    <!-- 举报弹层（T3.11）。用弹层而不是常驻表单：举报是填完就走的低频动作，
         挂在正文下面会让「看帖」这件事一直被一个空框子打断。 -->
    <el-dialog v-model="reportOpen" title="举报这条内容" width="560px" :close-on-click-modal="false">
      <p class="dlg-note">
        举报会立刻生成一张待审工单交给管理员；<b>它不会自动删掉这条内容</b>，
        被够多人独立举报到阈值才会转入人工审核并暂时隐藏。选准理由比把描述写长更有用。
      </p>
      <el-radio-group v-model="reportReason" class="reasons">
        <el-radio v-for="r in POST_REPORT_REASONS" :key="r.value" :value="r.value" class="reason">
          {{ r.label }}<span class="reason-desc">{{ r.desc }}</span>
        </el-radio>
      </el-radio-group>
      <!-- 求助入口写在弹层里，是因为这一步可能就是整条链路上唯一被看见的机会：
           号码由后端配置给（这里不写死），文案也不复述号码，见 CrisisCard。 -->
      <el-alert v-if="reportReason === 'self-harm'" type="warning" :closable="false" show-icon
                class="reason-warn" title="如果 TA 现在就有危险，别只靠举报"
                description="把求助入口里的号码转给 TA，或者陪 TA 一起打。举报能让管理员看到这条内容，但接不住此刻的电话。" />
      <el-input v-model="reportDescValue" class="desc" type="textarea" :rows="3" resize="none"
                placeholder="可选：说清楚哪里不对（≤200 字，只有审核的管理员看得到）" />
      <div class="desc-line">
        <span class="len" :class="{ over: descOver }">{{ descLen }} / {{ REPORT_DESC_MAX }}</span>
      </div>
      <div class="evi">
        <el-upload :show-file-list="false" :http-request="uploadEvidence"
                   accept="image/png,image/jpeg,image/gif" multiple>
          <el-button size="small" :loading="evidenceBusy" :disabled="evidence.length >= REPORT_EVIDENCE_MAX">
            添加截图
          </el-button>
        </el-upload>
        <span class="evi-hint">jpg / png / gif，最多 {{ REPORT_EVIDENCE_MAX }} 张；截图里先把你自己的信息遮住</span>
      </div>
      <div v-if="evidence.length" class="evi-imgs">
        <div v-for="(u, i) in evidence" :key="u" class="evi-thumb">
          <el-image :src="u" fit="cover" class="evi-img" :preview-src-list="evidence"
                    :initial-index="i" preview-teleported hide-on-click-modal />
          <button class="evi-rm" title="移除这张" @click="removeEvidence(i)">×</button>
        </div>
      </div>
      <template #footer>
        <el-button @click="reportOpen = false">取消</el-button>
        <el-button type="primary" :loading="reporting" :disabled="!canReport" @click="submitReport">
          提交举报
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  postDetail,
  reportPost,
  POST_REPORT_REASONS,
  REPORT_DESC_MAX,
  REPORT_EVIDENCE_MAX
} from '@/api/post'
import { uploadImage } from '@/api/file'
import { me } from '@/api/user'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import { usePostInteract } from '@/composables/usePostInteract'
import { CODE } from '@/api/errorCode'
import { fromNow, countdown, fmtCount, fmtDateTime } from '@/utils/format'
import CrisisCard from '@/components/CrisisCard.vue'
import CommentSection from '@/components/CommentSection.vue'
import StageNotice from '@/components/StageNotice.vue'

// U4 详情页。每打开一次就是后端一次真实计数（缓存累加 + 每 5 分钟回写），
// 所以这里绝不做「本地 +1」的假乐观更新——两边各加一次，数字就会凭空翻倍。
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
// 当前登录者的 id：profile 只在登录成功、资料页和这里按需补拉时被填，游客与刷新后可能为 null
const myId = computed(() =>
  userStore.profile && userStore.profile.id != null ? String(userStore.profile.id) : ''
)
const { isBusy, toggle } = usePostInteract()
// post 是本页自己持有的响应式对象（不是 props），改它的字段就是改界面
const act = function (it, kind) { return toggle(it, kind) }
const post = ref(null)
const loading = ref(false)
const notFound = ref(false)
const errorCode = ref(null)

const typeLabel = computed(() => ({ hole: '树洞', help: '求助' }[post.value?.type] || '分享'))
const typeTag = computed(() => {
  const t = post.value?.type
  return t === 'hole' ? 'warning' : t === 'help' ? 'danger' : ''
})
const shownAt = computed(() => (post.value ? fromNow(post.value.publishedAt) || '尚未发布' : ''))
const destroyLine = computed(() => {
  if (!post.value || !post.value.autoDestroyAt) return ''
  const line = countdown(post.value.autoDestroyAt)
  return line ? '树洞 ' + line : ''
})
const previewList = computed(() => (post.value?.images || []).map((x) => x.url))
// 评论组件只认一个数字 id：路由参数是字符串，直接传进去会让 el-input 之外的地方出现「140」与 140 两种键。
const postId = computed(() => Number(route.params.id) || 0)
// 页脚的「评论 N」跟着回执走：口径与后端 post.comment_cnt 相同（只数已发布，含楼中楼回复），
// 所以一条被机审转人工的评论不会让这个数变化——它确实还没进 comment_cnt。
// 同一个数还通过 published-count 传给评论区当标题：屏幕上两处「评论 N」必须是同一个口径，
// 待审那几条由组件自己在规则文案里单独说明（Gate3 截图 06 实测到「页脚 19 / 标题 20」对不上）。
function onCommentPublished() {
  if (!post.value) return
  post.value.commentCnt = (Number(post.value.commentCnt) || 0) + 1
}
const goneTitle = computed(() => '这条内容你现在看不到')
const goneDesc = computed(() => '它可能还没发布、只对自己可见、已经到期销毁，或者本来就不存在。')

async function load() {
  const id = Number(route.params.id)
  if (!Number.isFinite(id) || id <= 0) {
    notFound.value = true
    post.value = null
    return
  }
  loading.value = true
  // 上一条帖的举报回执不能跟着人跑到下一条帖下面：换帖即清（含弹层，防止带着旧 id 提交）
  reportTip.value = ''
  reportHotline.value = ''
  reportOpen.value = false
  notFound.value = false
  errorCode.value = null
  try {
    post.value = await postDetail(id)
  } catch (e) {
    post.value = null
    if (Number(e.code) === CODE.POST_NOT_FOUND) notFound.value = true
    else errorCode.value = e.code || 'network'
  } finally {
    loading.value = false
  }
}

// ---------------- 举报（任务 T3.11 · FR4.7、FR4.4、BR6、手册 §6.2 U4） ----------------
const reportOpen = ref(false)
const reporting = ref(false)
const reportReason = ref('')
const reportDescValue = ref('')
const evidence = ref([])
const evidenceBusy = ref(false)
const reportTip = ref('')
const reportHotline = ref('')

// 换帖就重拉一次：路由参数变了，页面必须跟着换。
//
// 这一行必须留在它要碰的所有状态都声明完之后（这里就是上面那七个举报 ref）。
// { immediate: true } 是在 setup 里当场就把回调跑一遍的，而 load() 第一件事就是清
// reportTip/reportHotline/reportOpen —— 放在 ref 声明之前会撞上 TDZ 抛 ReferenceError。
// 最坑的是它不炸页面：错误被 Vue 的 callWithErrorHandling 吞成一条 console.error，
// setup 照常完成、组件照常渲染，只是这一次 load 再也没走到 fetch，
// 于是「直接打开一条帖子」看到的是永远的空态；而从别的帖子切过来因为复用了实例、
// 状态早已初始化好，一切正常 —— 也就是只有首屏坏，第二个页面看不出问题。
watch(() => route.params.id, load, { immediate: true })

/**
 * 「这是不是我自己的帖」。两条判据，缺一条都会漏：
 * ① visibility 为 private 且拿到了回执 —— 别人对这条帖拿到的是 404/30001，
 *    所以「看得见 private」本身就是「我是作者」的充分证据；
 * ② authorId 与当前登录者相同 —— 匿名帖的回执刻意不带 authorId（数据最小化），
 *    所以这一条对匿名帖恒为假，此时按钮照样显示，由后端那句 400 兜底。
 */
const isMyPost = computed(() => {
  const p = post.value
  if (!p) return false
  if (p.visibility === 'private') return true
  return !!myId.value && p.authorId != null && String(p.authorId) === myId.value
})

const descLen = computed(() => Array.from(reportDescValue.value || '').length)
const descOver = computed(() => descLen.value > REPORT_DESC_MAX)
const canReport = computed(() => !!reportReason.value && !descOver.value)

/**
 * 打开举报弹层。未登录不发请求也不弹错误：后端这条路会返 401/10002，
 * 但「你得先登录」不该以故障面孔出现，所以和点赞、评论区一样直接带去登录页并带 redirect。
 *
 * <p>顺手补一次 /users/me：userStore.profile 只在登录成功和资料页里被填过，刷新页面就没了，
 * 而这里的 isMyPost 需要「我是谁」。补不到也不影响主流程——那只是少了一条前端预判。</p>
 */
async function openReport() {
  reportTip.value = ''
  reportHotline.value = ''
  if (!userStore.isLogged) {
    router.replace({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  if (!userStore.profile) {
    try {
      userStore.setProfile(await me())
    } catch (e) {
      // 拿不到自己的 id 就退回「显示按钮、由后端判」，不该因此把举报入口藏掉
    }
  }
  reportReason.value = ''
  reportDescValue.value = ''
  evidence.value = []
  reportOpen.value = true
}

/** 证据截图复用 T3.1 那条上传口：后端重编码去 EXIF，返回的就是 /uploads/ 下的相对地址，
 * 恰好是举报接口唯一收的形状（外链、data:、目录穿越一律 10001）。 */
async function uploadEvidence(options) {
  if (evidence.value.length >= REPORT_EVIDENCE_MAX) {
    ElMessage.warning('截图最多 ' + REPORT_EVIDENCE_MAX + ' 张')
    return
  }
  evidenceBusy.value = true
  try {
    const data = await uploadImage(options.file)
    if (data && data.url && !evidence.value.includes(data.url)) evidence.value.push(data.url)
  } catch (e) {
    if (e.response && e.response.status === 413) ElMessage.error('单张图片不能超过 5MB')
    else ElMessage.error(e.message || '截图上传失败')
  } finally {
    evidenceBusy.value = false
  }
}

function removeEvidence(i) {
  evidence.value.splice(i, 1)
}

/**
 * 提交举报。后端恒返 200：重复举报、已达阈值转人审，都是成功响应里的字段，
 * 所以这里没有「业务失败」要 catch，只有真正的 4xx（理由不合法、账号被停用、这条你看不到了）。
 * 空描述传 null 而不是空串：与后端「空白等于没写，存 null」同一口径，报表才分得清二者。
 */
async function submitReport() {
  const p = post.value
  if (!p || !reportReason.value || reporting.value) return
  const text = (reportDescValue.value || '').trim()
  reporting.value = true
  try {
    const view = await reportPost(p.id, {
      reason: reportReason.value,
      description: text || null,
      evidenceUrls: evidence.value.length ? evidence.value.slice() : null
    })
    reportOpen.value = false
    // 人数、阈值、是否已转人审一律以回执为准，前端一个数字都不自己加
    reportTip.value = (view && view.tip) || '收到了，我们会看这条内容。'
    reportHotline.value = (view && view.hotline) || ''
  } catch (e) {
    if (Number(e && e.code) === CODE.POST_NOT_FOUND) {
      // 举报中途这条帖被删/下架到看不见了：关掉弹层并重走一次的可见性判定
      reportOpen.value = false
      load()
    }
    // 其余失败已由 http 层弹成一句人话，这里不再翻译一遍
  } finally {
    reporting.value = false
  }
}
</script>

<style scoped>
.detail { display: flex; flex-direction: column; gap: 18px; }
.body { max-width: 820px; }
.head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.who { font-size: 13px; font-weight: 700; color: var(--mi-mist); }
.dot, .time { font-size: 12px; color: var(--mi-text-dim); }
.destroy { font-size: 12px; color: var(--mi-primary); }
.title { margin: 14px 0 10px; font-size: 22px; line-height: 1.5; }
.content { font-size: 15px; line-height: 2; white-space: pre-wrap; word-break: break-word; color: var(--mi-text); }
.imgs { display: flex; gap: 10px; margin-top: 16px; flex-wrap: wrap; }
.img { width: 168px; height: 168px; border-radius: 10px; border: 1px solid var(--mi-border); }
.topics { display: flex; gap: 12px; margin-top: 14px; flex-wrap: wrap; }
.topic { font-size: 13px; color: var(--mi-mist); }
.tip { margin-bottom: 10px; }
.meta { display: flex; gap: 16px; align-items: center; margin-top: 18px; padding-top: 12px; border-top: 1px dashed var(--mi-border); font-size: 12px; color: var(--mi-text-dim); flex-wrap: wrap; }
.meta .dim { color: var(--mi-text-dim); opacity: 0.8; }
.acts { display: flex; align-items: center; gap: 12px; margin-top: 16px; flex-wrap: wrap; }
.acts .act { font-size: 13px; }
.acts .act.act-on { font-weight: 700; }
.acts-note { font-size: 12px; color: var(--mi-text-dim); }
.meta .stat { white-space: nowrap; }
.gone { max-width: 620px; }
.gone-desc { font-size: 14px; color: var(--mi-text); }
.gone-note { font-size: 12px; color: var(--mi-text-dim); line-height: 1.8; }
.acts .act-report { color: var(--mi-text-dim); }
.acts .act-report:hover { color: var(--mi-primary); }
.report-receipt { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.dlg-note { margin: 0 0 12px; font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); }
/* 2026-09-23 真浏览器截图（docs/gate/阶段3/07）抓出来的布局 bug：
   EP 的 .el-radio-group 自带 align-items:center。下面这条把它的排版方向改成 column 之后,
   交叉轴就变成了水平轴 —— 于是五个举报理由被整体推到弹层中间，单选圈和文字之间空出一大截。
   jsdom 不跑布局，这一类问题只有真量一次 getBoundingClientRect 才现形，故显式写回 flex-start。 */
.reasons { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; }
.reason { height: auto; margin-right: 0; align-items: flex-start; }
.reason-desc { margin-left: 8px; font-size: 12px; color: var(--mi-text-dim); }
.reason-warn { margin-top: 10px; }
.desc { margin-top: 12px; }
.desc-line { display: flex; justify-content: flex-end; margin-top: 4px; }
.len { font-size: 12px; color: var(--mi-text-dim); }
.len.over { color: var(--mi-primary); font-weight: 700; }
.evi { display: flex; align-items: center; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
.evi-hint { font-size: 12px; color: var(--mi-text-dim); }
.evi-imgs { display: flex; gap: 10px; margin-top: 12px; }
.evi-thumb { position: relative; }
.evi-img { width: 92px; height: 92px; border-radius: 8px; border: 1px solid var(--mi-border); }
.evi-rm { position: absolute; top: -6px; right: -6px; width: 20px; height: 20px; padding: 0; border: none; border-radius: 50%; background: var(--mi-primary); color: #1b1206; line-height: 18px; font-size: 14px; cursor: pointer; }
</style>
