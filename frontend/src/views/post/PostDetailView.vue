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
      <!-- 停留计时（任务 T4.17 · FR5.1）：一次「浏览」到底算不算，全靠这个数，而埋点恰恰是最容易
           「代码在、数据不在」的一类功能。把这行读数摆在正文下面，是为了让它自己能被当场验收 ——
           滚到底、停在 3 秒以上、再切走，界面上的话会跟着变，user_action 里也就跟着多一行。 -->
      <p class="dwell dim">{{ dwellLine }}</p>

      <!-- 点赞/收藏已经接上真接口（T3.6），这条互动条就是它的落点：
           详情页是「一个人反复进出同一帖」的地方，所以按钮态一律用后端回执初始化，不做本地记忆。
           相似帖（FR5.6）已于 2026-09-29 接上，但它不在这条互动条里 —— 正文下面另有一张「看了又看」卡。
           这里只放「对这一条的操作」，那张卡回答的是「接下来读什么」，两件事不混在一排按钮上。 -->
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

    <!-- 「看了又看」（任务 T7.16 的前端消费方 · 手册 §10.6 · 需求 FR5.6）。
         位置钉在正文之后、评论区之前：读完这条才决定「还要不要继续看」，这是它唯一会被看见的位置。
         卡片复用广场那张 PostCard（需求 D6「同一形状」），差别只有两处：
         ① 不给「不感兴趣」按钮 —— POST /api/feed/dislike 的 scene 后端写死 'feed'，
            从详情页点它会被记成「推荐流的负反馈」，那是把两件事记成一件事（详见 api/feed.js 注释）；
         ② 理由那一行说的是「两条内容的关系」（和这篇一样…），不是「因为你…」，手册 §10.6 第 3 条要求分开。
         整页返回、不分页：接口回的是 List<FeedItem>，不是 PageResult，所以这里用本地 ref 而不是 usePagedPosts。 -->
    <section v-if="post" class="mi-card similar">
      <div class="sec-head">
        <h2 class="sim-h">看了又看</h2>
        <div class="sec-ops">
          <span class="dim">{{ similarLine }}</span>
          <el-button class="btn-similar-reload" size="small" text :loading="similarLoading"
                     @click="loadSimilar(postId)">重新加载</el-button>
        </div>
      </div>
      <p class="dim sim-note">这几条不是「为你推荐」：它们和<b>你刚读完的这条</b>被同一批人连着读过（ItemCF 邻居），
        邻居凑不够时补同话题的热帖、再补质量分榜。所以理由说的是两条内容的关系，不去猜你的喜好。</p>
      <p v-if="!userStore.isLogged" class="dim">这一位要登录才加载：后端先要认出「你」，才能按你的可见性把
        私密帖、审核中的帖从相似位里剔掉（GET /api/posts/{id}/similar 未登录直接 10002，这里就不发这个注定失败的请求了）。</p>
      <stage-notice v-else-if="similarError" :code="similarError" stage="7"
                    api-name="GET /api/posts/{id}/similar" :extra="similarExtra" />
      <template v-else>
        <div v-for="row in similar" :key="row.id" class="sim-row">
          <post-card class="sim-card" :item="row" :dismissable="false">
            <template #reason>
              <div class="sim-why">
                <span class="sim-reason">{{ row.recReason || '后端这条没给理由（reason 为空），页面不替它编。' }}</span>
                <span class="sim-ch">{{ channelLabel(row.recChannel) }}</span>
              </div>
            </template>
          </post-card>
        </div>
        <p v-if="!similar.length && !similarLoading" class="dim">这一条暂时找不到能一起看的内容。
          新帖、冷门话题最容易这样：共读记录还没攒够，兜底池里同话题也没有别的已过审公开帖。</p>
      </template>
    </section>

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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  postDetail,
  reportReadProgress,
  VIEW_MIN_DURATION_MS,
  reportPost,
  similarPosts,
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
import PostCard from '@/components/PostCard.vue'
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
  resetDwell()
  // 上一条帖的举报回执不能跟着人跑到下一条帖下面：换帖即清（含弹层，防止带着旧 id 提交）
  reportTip.value = ''
  reportHotline.value = ''
  reportOpen.value = false
  notFound.value = false
  errorCode.value = null
  try {
    post.value = await postDetail(id)
    beginDwell()
    // 相似位跟着正文一起换，但不 await：它是详情页的旁支，不该把「正文出来」这件事再往后拖一个 RTT。
    loadSimilar(id)
  } catch (e) {
    post.value = null
    if (Number(e.code) === CODE.POST_NOT_FOUND) notFound.value = true
    else errorCode.value = e.code || 'network'
  } finally {
    loading.value = false
  }
}

// ---------------- 举报（任务 T3.11 · FR4.7、FR4.4、BR6、手册 §6.2 U4） ----------------
// ---------------- 停留时长上报（任务 T4.17 · 需求 FR5.1「阅读停留 ≥3s 才是一次 view」）----------------
// 三条口径，都是为了让「一次浏览」这件事别被污染：
// ① 起点不是组件 created，而是 load() 拿到内容那一刻 —— 从广场点进来、正文还在 fetch 的那两百毫秒不是阅读；
// ② 只累计「页面真的可见」的时间，切去别的标签页挂着两小时不是看了两小时，所以每次 hidden 结算一段、
//    回到 visible 重新起表（后端那条接口按人认，匿名的停留压根不发）；
// ③ 完读判据是滚到底，但不足一屏的帖子打开就是全部，那种情况不强求滚动 —— 否则短帖永远读不完。
// 界面上这行读数是刻意留的：埋点是最容易「代码在、数据不在」的一类功能，
// 让它当场可见，才有人能在答辩现场指着它说这条链路是通的。
let dwellSegStart = 0
let dwellAccumMs = 0
let dwellCompleted = false
let dwellLastFlushMs = -1
let dwellPostId = 0
let dwellTimer = null
const dwellTick = ref(0)

function settledDwellMs() {
  return dwellSegStart > 0 ? dwellAccumMs + (Date.now() - dwellSegStart) : dwellAccumMs
}

function resetDwell() {
  dwellSegStart = 0
  dwellAccumMs = 0
  dwellLastFlushMs = -1
  dwellCompleted = false
  dwellPostId = Number(route.params.id) || 0
}

// 🔴 这一次「判到底」绝不能同步跑在赋值的那一刻（Gate4 r2 在真浏览器里量到的产品 bug）。
// beginDwell() 是被 load() 紧挨着 post.value = await postDetail(id) 调用的，那一刻 Vue 还
// 没把正文排版上去，document.documentElement.scrollHeight 量到的是**加载骨架**的高度：
// 一千六百像素的长帖会被判成「不足一屏 ⇒ 打开即读到底」，页面刚出来 1 毫秒就发出
// completed=true，而后端 readThroughRecorded 照单收 —— 点开就退出的人拿满 2 分正样本，
// FR5.1 那条「完读」权重就此作废，阶段 7 的隐式召回也跟着被污染。
// 正解：等一次真正的布局完成（nextTick 出队 + 连续两帧 rAF）之后再判第一次。
function beginDwell() {
  dwellSegStart = Date.now()
  nextTick(function () {
    requestAnimationFrame(function () {
      requestAnimationFrame(markReadCompleted)
    })
  })
}

function checkReadBottom() {
  const de = document.documentElement
  // gap <= 24px 才算到底：留一点滚动惯性，也让「已经到底但差一两像素」不被判成没读完。
  // 内容不足一屏时 scrollHeight - innerHeight 为负，同样进这里 —— 短帖打开即读完。
  return de.scrollHeight - window.innerHeight - window.scrollY <= 24
}

// 到达底部是「一次性」事件：第一次到底就补报一条 completed=true（这一条之后就不再重复发，
// 免得每滚一下打一次接口），然后重新起表继续累计停留。
function markReadCompleted() {
  if (dwellCompleted || !checkReadBottom()) return
  dwellCompleted = true
  flushDwell()
  if (post.value) dwellSegStart = Date.now()
}

function settleDwell() {
  if (dwellSegStart > 0) {
    dwellAccumMs += Date.now() - dwellSegStart
    dwellSegStart = 0
  }
  return dwellAccumMs
}

// 每一次结算都发一条：同一个人同一天对同一条帖重复上报，后端 user_action.upsert 会合并成一行、
// 只把 duration_ms 取更长的这次（见 UserActionMapper#upsert），所以重复上报不会变成重复浏览。
// 阈值判据在服务端，这里只是「够不够」都不自己下结论 —— 前端不拿回执做加分动画。
function flushDwell() {
  const ms = settleDwell()
  if (!dwellPostId || ms <= 0 || ms === dwellLastFlushMs) return
  dwellLastFlushMs = ms
  reportReadProgress(dwellPostId, ms, dwellCompleted)
}

function onDwellScroll() {
  markReadCompleted()
}

// 短帖「打开就是全部」，那一刻的累计停留只有几十毫秒，完读那 2 分不该发给一次都没读够的打开；
// 所以到到底之后如果上一次上报还没跨过 3 秒，就在「刚跨过阈值」那一拍补报一次（只补这一次，
// 之后 dwellLastFlushMs 已 >= 阈值，条件自己关闭，不会变成每秒一条请求）。口径与后端
// /api/posts/{id}/read 一致：completed 也要先满 VIEW_MIN_DURATION_MS 才计 read_through。
function onDwellTick() {
  dwellTick.value += 1
  if (!dwellCompleted || dwellLastFlushMs >= VIEW_MIN_DURATION_MS) return
  if (settledDwellMs() >= VIEW_MIN_DURATION_MS) flushDwell()
}

function onDwellVisibility() {
  if (document.hidden) {
    flushDwell()
  } else if (post.value) {
    dwellSegStart = Date.now()
  }
}

const dwellLine = computed(() => {
  if (!post.value || !dwellPostId) return ''
  dwellTick.value
  const ms = settledDwellMs()
  const s = Math.floor(ms / 1000)
  const enough = ms >= VIEW_MIN_DURATION_MS
  const need = Math.ceil((VIEW_MIN_DURATION_MS - ms) / 1000)
  return '本页已读 ' + s + ' 秒 · ' + (enough ? '够一次浏览' : '还差 ' + need + ' 秒才算一次浏览') + ' · ' +
    (dwellCompleted ? '已读到底（完读计 2 分）' : '未读到底')
})

// 事件挂 window/document 而不是这个 div：滚动是文档级的，页面卸载是浏览器级的，
// 在组件根节点上监听 scroll 只会收到「这个元素自己滚了」，而它根本没滚。
onMounted(function () {
  window.addEventListener('pagehide', flushDwell)
  window.addEventListener('scroll', onDwellScroll, { passive: true })
  document.addEventListener('visibilitychange', onDwellVisibility)
  // 1s 心跳刷那行读数，并在「完读态 + 刚跨过 3 秒」那一拍补报一次；其余上报时机在 hidden / pagehide / 滚到底。
  dwellTimer = setInterval(onDwellTick, 1000)
})

// SPA 内部换页也走这里：组件卸载不会触发 pagehide，而「看完这条又点进下一条」是站里最常见的动线。
onBeforeUnmount(flushDwell)

const reportOpen = ref(false)
const reporting = ref(false)
const reportReason = ref('')
const reportDescValue = ref('')
const evidence = ref([])
const evidenceBusy = ref(false)
const reportTip = ref('')
const reportHotline = ref('')

// ---------------- 相似位「看了又看」（任务 T7.16 前端消费方 · 手册 §10.6 · 需求 FR5.6） ----------------
// 这一整块必须待在 watch 之前：那条 watch 带 { immediate: true }，是在 setup 里当场就跑一遍的，
// 而它一路会走到 loadSimilar() 去读写下面这几个 ref。放后面就是 TDZ ——
// 上面那条「举报七个 ref」的注释记的正是同一件事怎么把首屏悄悄打成永久空态的。

/**
 * 相似位一屏几条。后端夹在 1..12、默认 6（SimilarPostService#clampSize），这里就取默认的 6：
 * 详情页的主体是正文与评论区，相似位一超过 6 条就会把评论输入框顶到两屏之外，
 * 而「看完想说一句」恰恰是这条页面上最高频的下一步。
 */
const SIMILAR_SIZE = 6
const similar = ref([])
const similarLoading = ref(false)
/** null 表示上一次成功；否则是后端码或 'network'（手册 §5.8 第 1 条：不许静默失败）。 */
const similarError = ref(null)

/**
 * FeedItem = {post:{…}, reason, recallChannel, score} 压成 PostCard 认的扁平形状。
 * 与首页 recommendPage 里那一步是同一件事：卡片的点击跳转、点赞回执都按顶层 id 找对象，
 * 不压平的话 row.id 为 undefined，点卡片会跳到 /post/undefined。
 */
function flattenFeedItem(row) {
  const p = (row && row.post) || {}
  return Object.assign({}, p, {
    id: p.id,
    recReason: row ? row.reason : null,
    recChannel: row ? row.recallChannel : null,
    recScore: row ? row.score : null
  })
}

// 只列后端真会给的三条通道（相似位走不到 usercf/explore/emotion）；其余一律「未标注通道」，不替后端编。
const SIMILAR_CHANNELS = { itemcf: '共读相似', content: '同话题', hot: '热读补位' }
function channelLabel(ch) {
  return SIMILAR_CHANNELS[ch] || '未标注通道'
}

const similarLine = computed(() => {
  if (!userStore.isLogged) return ''
  if (similarLoading.value) return '加载中…'
  if (similarError.value) return '这次没取到'
  return similar.value.length ? SIMILAR_SIZE + ' 条以内 · 已取 ' + similar.value.length + ' 条' : '还没攒出可一起读的内容'
})

const similarExtra = computed(() => {
  const code = Number(similarError.value)
  if (code === CODE.UNAUTHORIZED || code === CODE.TOKEN_EXPIRED || code === CODE.TOKEN_INVALID) {
    return '登录态已经不成立了：这条接口要先认出「你」，才能按你的可见性把私密帖、审核中的帖从相似位里剔掉。请重新登录。'
  }
  if (code === CODE.DB_UNAVAILABLE) {
    return '数据库暂不可用：相似位要读 item_similarity 与 post 两张表，后端按「库挂了也让页面活着」的口径降级 ——'
      + '这里只是这一块空着，正文与评论区不受影响。'
  }
  if (code === CODE.POST_NOT_FOUND) {
    return '源帖对你不可见或已经不在了：这条接口刻意回 30001/404 而不是 200 空列表 ——'
      + '「不可见」与「不存在」必须同形，否则它会变成一枚探测别人私密帖的探针。'
  }
  return ''
})

async function loadSimilar(id) {
  // 先清再拉：换帖时若不清，会有一屏的时间「上一条的相似位挂在新帖下面」，那是最容易被判成 bug 的错位。
  similar.value = []
  similarError.value = null
  if (!userStore.isLogged) return // 未登录不发这个注定吃 10002 的请求，界面上那句话说的是原因
  const target = Number(id)
  if (!Number.isFinite(target) || target <= 0) return
  similarLoading.value = true
  try {
    const rows = await similarPosts(target, SIMILAR_SIZE)
    similar.value = (Array.isArray(rows) ? rows : []).map(flattenFeedItem)
  } catch (e) {
    similarError.value = e && e.code !== undefined && e.code !== null ? e.code : 'network'
  } finally {
    similarLoading.value = false
  }
}

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
/* 与页脚那行统计同一块，但隔开一点：它是「我」的数据，不是「这条帖」的数据。 */
.dwell { margin: 12px 0 0; padding-top: 8px; border-top: 1px dashed var(--mi-border); }
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
/* 「看了又看」这张卡（T7.16）。刻意不做成和正文一样的宽度上限之外的样式：
   它读起来就是正文的下一段，所以间距、字号都跟着正文那张卡走。 */
.similar h2.sim-h { margin: 0; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
.sim-note { margin: 0 0 10px; line-height: 1.8; }
.sim-row { margin-top: 10px; }
.sim-card { border: 1px solid var(--mi-border); border-radius: 12px; padding: 12px 14px; background: rgba(127, 167, 196, 0.04); }
.sim-why { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--mi-border); }
.sim-reason { font-size: 12px; line-height: 1.7; color: var(--mi-mist); }
.sim-ch { flex: none; font-size: 12px; color: var(--mi-text-dim); }
.sec-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; }
.sec-ops { display: flex; align-items: center; gap: 8px; }
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
