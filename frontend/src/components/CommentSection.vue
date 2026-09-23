<template>
  <section class="mi-card comments">
    <header class="bar">
      <h2 class="h">评论 <span class="n">{{ fmtCount(shownTotal) }}</span></h2>
      <span class="rule">{{ countRule }}</span>
    </header>

    <crisis-card v-if="hotline" :hotline="hotline"
                 text="你刚才那句话里有求助的信号，现在就可以打这个电话，免费、24 小时都有人接" />

    <!-- 未登录连请求都不发（不是「先发了再把 401 吞掉」）：后端这条读接口带身份参与可见性判定，
         待审评论只有作者本人看得见，游客本来就取不到列表。此时页面既不能说「还没有评论」，
         也不能弹一条错误——那等于把「你得先登录」说成「出故障了」。 -->
    <div v-if="!logged" class="guest">
      <p class="guest-t">登录后才能看到评论并参与讨论。</p>
      <el-button size="small" type="primary" round @click="goLogin">去登录</el-button>
    </div>

    <template v-else>
      <el-alert v-if="loadError" type="error" :closable="false" show-icon
                :title="'评论没有加载出来（code=' + loadError + '）'" />
      <p v-if="loadError" class="degrade">多半是后端或数据库不可用，不是这条帖子没人评论。重试可以点下面的按钮。</p>
      <!-- el-skeleton 渲染出来是 div，所以这一条用 div 不用 p：p 里套 div 在 HTML 解析规则里是非法嵌套，
           浏览器实时解析（比如将来做 SSR hydration）会自己把 p 提前闭合掉。 -->
      <div v-else-if="loading && !threads.length" class="empty"><el-skeleton :rows="2" animated /></div>
      <p v-else-if="!threads.length" class="empty">还没有人留下评论，做第一个。</p>

      <article v-for="t in threads" :key="'t' + t.root.id" class="thread">
        <div v-for="row in rowsOf(t)" :key="'r' + row.id" class="row" :class="{ indent: !!row.parentId }">
          <div class="who-line">
            <span class="who">{{ row.authorName || '屿友' }}</span>
            <el-tag v-if="row.anonymous" size="small" type="info">匿名</el-tag>
            <el-tag v-if="row.authorIsPostOwner" size="small" type="success" effect="plain">楼主</el-tag>
            <span class="dot">·</span>
            <span class="time">{{ fromNow(row.createdAt) }}</span>
            <el-button class="reply-btn" size="small" text @click="startReply(row)">回复</el-button>
          </div>
          <p class="text">
            <span v-if="row.replyToName" class="to">回复 @{{ row.replyToName }}：</span>{{ row.content }}
          </p>
          <el-alert v-if="row.auditTip" type="warning" :closable="false" show-icon :title="row.auditTip" />
        </div>

        <el-button v-if="!t.expanded && t.replyTotal > t.replies.length" class="expand" size="small" text
                   :loading="expanding === t.root.id" @click="expand(t)">
          查看 {{ t.replyTotal }} 条回复
        </el-button>
        <p v-else-if="t.replies.length >= COMMENT_SUBTREE_CAP" class="cap">
          这一楼回复太多，接口单次最多给 {{ COMMENT_SUBTREE_CAP }} 条，先看到这儿。</p>
        <p v-else-if="t.expandError" class="cap fail">这一楼的回复没加载出来，可以再点一次「查看回复」。</p>
      </article>

      <div class="pager">
        <el-button v-if="hasMore" class="more" size="small" :loading="loading" @click="loadMore">查看更多评论</el-button>
        <span v-else-if="threads.length" class="end">一级评论 {{ total }} 条已全部加载{{ pendingRoots ? '（含 ' + pendingRoots + ' 条审核中、仅你可见）' : '' }}。</span>
      </div>

      <footer class="composer">
        <div v-if="replyTo" class="replying">
          <span>正在回复 @{{ replyTo.authorName || '屿友' }}</span>
          <el-button size="small" text @click="cancelReply">取消</el-button>
        </div>
        <el-input v-model="draft" class="box" type="textarea" :rows="3" resize="none"
                  placeholder="友善地说一句……" @keydown.ctrl.enter.prevent="submit" />
        <div class="ops">
          <el-checkbox v-model="anonymous" size="small">匿名发表</el-checkbox>
          <span class="grow"></span>
          <span class="len" :class="{ over: overLimit }">{{ len }} / {{ COMMENT_MAX_CHARS }}</span>
          <el-button class="send" type="primary" size="small" round :loading="submitting"
                     :disabled="!canSubmit" @click="submit">发送</el-button>
        </div>
        <p v-if="tip" class="tip-line">{{ tip }}</p>
        <p class="hint">
          Ctrl+Enter 发送。评论和帖子一样先审后发：命中灰词会转人工（那时只有你自己看得见），
          命中危机词会立刻在上面出现求助入口。匿名只隐藏名字，不隐藏这条评论仍然要过审核。
        </p>
      </footer>
    </template>

    <p class="footnote">
      <b>这一版评论区刻意没有什么：</b>评论点赞（后端只把库里的 like_cnt 带出来，评论点赞接口还没开，
      画一颗点不动的按钮比不画更糟）、删除与举报（T3.11）、@提醒与回复通知（阶段 6）、
      三级以上缩进（楼中楼在数据里可以无限深，展示一律压平成两级，第三层靠「回复 @某某」这句话表达）。
    </p>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  addComment,
  listComments,
  COMMENT_MAX_CHARS,
  COMMENT_PAGE_SIZE,
  COMMENT_SUBTREE_CAP,
  commentLength
} from '@/api/post'
import { fromNow, fmtCount } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import CrisisCard from '@/components/CrisisCard.vue'

/**
 * U4 评论区（任务 T3.7 · 手册 §6.2 U4、需求 FR4.3 / FR4.4 / FR7.3）。
 *
 * <p><b>为什么单独抽一个组件</b>：详情页当时 165 行，评论树（列表 + 楼中楼展开 + 发表框 + 回执处置）
 * 加进去会变成 400 行的单文件，而评论区在 U11 主页、U6 话题圈里后面还要复用同一套判据。
 * 抽出来的代价是多一个文件，换来的是「可见性口径只有一份」。不抽的代价是三处各写一遍
 * 「待审评论只有作者看得见」，其中一处忘了写就是一个隐私口子。</p>
 *
 * <p><b>为什么发完评论不重新拉一次列表</b>：拉一次就多一次请求，而这条链路上已经有
 * 60 次/分的全局限流和单帖单用户 20 条/日的评论配额，用刷新去换一条新评论是最贵的做法。
 * 也不能「把输入框里的字就地插进去」——那句话还没过机审，插进去就等于在屏幕上造了一条
 * 数据库里可能根本不存在的评论。这里的做法是<b>用后端回执画</b>：回执里的 content 是已经
 * 遮罩过的、status 是机审终态、id 是真实主键，所以它和重拉一次拿到的那一条是同一份数据。</p>
 *
 * <p><b>回执能不能画出来，判据与后端 isVisibleTo 逐字对齐</b>：PUBLISHED 人人可见；
 * PENDING 只有作者自己可见（此刻查看者就是他本人，所以能画，且带 auditTip）；
 * REJECTED / DELETED 谁都不可见——这两种只留文案，列表里一个像素都不给。</p>
 */
const props = defineProps({
  postId: { type: [Number, String], required: true },
  /**
   * 卡片/详情页页脚那个「评论 N」的值（= 后端 post.comment_cnt：只数 PUBLISHED，含楼中楼回复）。
   * 评论区标题必须用**这一个数**，不能用列表的 total —— total 是「对当前查看者可见的一级评论数」，
   * 既不含楼中楼、又把作者自己那条 PENDING 算进来，两个口径混在同一个屏幕上就会出现
   * 「页脚写 19、评论区标题写 20」这种看起来像 bug 的对不上（Gate3 截图 06 实测到）。
   * 不给这个 prop 时回退用 total，保证组件单独挂载时不炸。
   */
  publishedCount: { type: [Number, String], default: null }
})
// 一条已发布评论落库时告诉父级一次：详情页页脚那个「评论 N」就是靠它自增的，
// 口径与后端 post.comment_cnt 完全一致（只数 PUBLISHED 且未删除的评论，含楼中楼回复）。
const emit = defineEmits(['published'])

const router = useRouter()
const userStore = useUserStore()
const logged = computed(() => userStore.isLogged)

/** 一棵楼的视图模型：root 是后端 CommentThread.root，replies 是本页已拿到的回复，expanded 表示已经整棵展开过。 */
const threads = ref([])
const total = ref(0)
const page = ref(1)
const hasMore = ref(false)
const loading = ref(false)
const loadError = ref(null)
const expanding = ref(0)
const draft = ref('')
const anonymous = ref(false)
const replyTo = ref(null)
const submitting = ref(false)
const hotline = ref('')
const tip = ref('')

// 换帖时旧请求晚到会污染新帖子，所以每次发请求领一个号：回来时号不是最新的就整份丢掉。
// 这条纪律是从 usePagedPosts.js 抄来的，同一个坑不该在两个列表里各踩一次。
let seq = 0

// 屏上「仅作者自己可见」的待审评论条数：一级评论里的那几条（回复层的另算，见 pendingAll）。
const pendingRoots = computed(() => threads.value.filter(function (t) {
  return t && t.root && t.root.status === 'PENDING'
}).length)
const pendingAll = computed(() => threads.value.reduce(function (n, t) {
  if (!t) return n
  if (t.root && t.root.status === 'PENDING') n += 1
  return n + (Array.isArray(t.replies) ? t.replies.filter(function (r) { return r && r.status === 'PENDING' }).length : 0)
}, 0))
// 标题数字的唯一口径：父级给的「已发布评论数」，取不到才退回列表 total。
const shownTotal = computed(() => {
  const v = Number(props.publishedCount)
  return Number.isFinite(v) && v >= 0 ? v : total.value
})
const countRule = computed(() => {
  const base = '只数已发布评论，楼中楼回复也算在内，与卡片上的「评论」是同一个数。'
  return pendingAll.value > 0
    ? base + '本页另有 ' + pendingAll.value + ' 条审核中、仅你可见，所以下面会比这个数字多。'
    : base
})

const len = computed(() => commentLength(draft.value))
const overLimit = computed(() => len.value > COMMENT_MAX_CHARS)
const canSubmit = computed(() => !!draft.value.trim() && !overLimit.value && !submitting.value)

function rowsOf(thread) {
  return [thread.root].concat(thread.replies)
}

function toThread(raw) {
  const root = raw && raw.root
  if (!root) return null
  return {
    root: root,
    replies: Array.isArray(raw.replies) ? raw.replies : [],
    replyTotal: Number(raw.replyTotal) || 0,
    expanded: false,
    expandError: false
  }
}

/** 同 id 只留一份：本地插入 + 翻页回来的那一条会撞车，撞车时以后端那份为准（回执可能少了展示字段之外的东西）。 */
function mergeRows(oldRows, newRows) {
  const out = oldRows.slice()
  const seen = new Set(out.map(function (x) { return String(x.root.id) }))
  newRows.forEach(function (x) {
    if (x && !seen.has(String(x.root.id))) {
      out.push(x)
      seen.add(String(x.root.id))
    }
  })
  return out
}

async function fetchPage(targetPage, replace) {
  const mine = ++seq
  loading.value = true
  loadError.value = null
  try {
    const data = await listComments(props.postId, { page: targetPage, size: COMMENT_PAGE_SIZE })
    if (mine !== seq) return false
    const rows = (data && Array.isArray(data.list) ? data.list : []).map(toThread).filter(Boolean)
    threads.value = replace ? mergeRows([], rows) : mergeRows(threads.value, rows)
    if (data && typeof data.total === 'number' && data.total >= 0) total.value = data.total
    hasMore.value = !!(data && data.hasMore === true)
    page.value = targetPage
    return true
  } catch (e) {
    if (mine !== seq) return false
    loadError.value = e && e.code !== undefined && e.code !== null ? e.code : 'network'
    if (replace) threads.value = []
    return false
  } finally {
    if (mine === seq) loading.value = false
  }
}

function reload() {
  return fetchPage(1, true)
}

function loadMore() {
  if (!hasMore.value || loading.value) return Promise.resolve(false)
  return fetchPage(page.value + 1, false)
}

/**
 * 展开一整棵楼中楼：后端这条分支不带分页（一次给完，上限 COMMENT_SUBTREE_CAP 条），
 * 理由是回复分页会因为中间一条被删而整体错位。所以这里只需替换 replies，不用管游标。
 * 判「展开的是不是同一楼」用 root.id 比对：万一请求在路上而列表已经被换帖清掉，
 * 拿回来的那份不能塞进此刻的第一楼。
 */
async function expand(thread) {
  if (thread.expanded || expanding.value) return
  expanding.value = thread.root.id
  thread.expandError = false
  try {
    const data = await listComments(props.postId, { rootId: thread.root.id })
    const one = data && Array.isArray(data.list) ? data.list[0] : null
    const built = toThread(one)
    if (built && String(built.root.id) === String(thread.root.id)) {
      thread.replies = built.replies
      thread.replyTotal = Math.max(thread.replyTotal, built.replyTotal)
      thread.expanded = true
    }
  } catch (e) {
    thread.expandError = true
  } finally {
    expanding.value = 0
  }
}

function startReply(row) {
  replyTo.value = row
  tip.value = ''
}

function cancelReply() {
  replyTo.value = null
}

function goLogin() {
  const current = router.currentRoute.value
  router.replace({ name: 'login', query: current && current.name ? { redirect: current.fullPath } : {} })
}

/** 把后端回执画进列表。返回 false 表示这一条不该出现在屏幕上（被驳回或已删除）。 */
function applyReceipt(item) {
  if (!item || !item.id) return false
  if (item.status !== 'PUBLISHED' && item.status !== 'PENDING') return false
  const published = item.status === 'PUBLISHED'
  if (!item.parentId) {
    if (threads.value.some(function (t) { return String(t.root.id) === String(item.id) })) return published
    threads.value.push({
      root: item, replies: [], replyTotal: 0, expanded: true, expandError: false
    })
    if (published) {
      total.value += 1
      emit('published')
    }
    return true
  }
  const home = threads.value.find(function (t) { return String(t.root.id) === String(item.rootId) })
  if (!home) {
    // 回复的那一楼不在已加载的页里（很老的评论、或者用户还没翻到）：交给后端重排，别本地造位置。
    reload()
    return true
  }
  if (home.replies.some(function (r) { return String(r.id) === String(item.id) })) return published
  home.replies.push(item)
  home.replyTotal += 1
  if (published) emit('published')
  return true
}

async function submit() {
  if (!logged.value) {
    goLogin()
    return
  }
  if (!canSubmit.value) return
  submitting.value = true
  tip.value = ''
  try {
    const view = await addComment(props.postId, {
      content: draft.value.trim(),
      parentId: replyTo.value ? replyTo.value.id : null,
      anonymous: anonymous.value === true
    })
    if (view && view.hotline) hotline.value = view.hotline
    if (view && view.tip) tip.value = view.tip
    applyReceipt(view && view.comment)
    // 输入框一定清空：内容被驳回时也「已经提交过一次」，留着原文只会让人再撞一次同一道审核。
    draft.value = ''
    replyTo.value = null
    anonymous.value = false
  } catch (e) {
    // 失败留着草稿。全局错误条由 http 层弹（写接口不 silent），这里只负责不弄丢用户写的字。
  } finally {
    submitting.value = false
  }
}

function reset() {
  threads.value = []
  total.value = 0
  page.value = 1
  hasMore.value = false
  loadError.value = null
  hotline.value = ''
  tip.value = ''
  replyTo.value = null
  anonymous.value = false
  // 草稿跨帖留着是没意义的：那句话是对着这一条帖子写的。
  draft.value = ''
}

watch(() => props.postId, function () {
  seq += 1
  reset()
  if (logged.value) reload()
// immediate：路由复用同一个组件实例时（从上一帖的详情页跳进来），没有这一条就是首屏空白。
}, { immediate: true })

watch(logged, function (value) {
  if (value && !threads.value.length) reload()
})

defineExpose({ reload })
</script>

<style scoped>
.comments { max-width: 820px; }
.bar { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.h { margin: 0; font-size: 16px; }
.h .n { color: var(--mi-mist); }
.rule { font-size: 12px; color: var(--mi-text-dim); }
.guest { padding: 14px 0 4px; }
.guest-t { margin: 0 0 10px; font-size: 13px; color: var(--mi-text-dim); }
.empty { font-size: 13px; color: var(--mi-text-dim); margin: 12px 0; }
.degrade { font-size: 12px; color: var(--mi-text-dim); margin: 6px 0 0; }
.thread { border-top: 1px dashed var(--mi-border); padding: 10px 0 2px; }
.row { padding: 4px 0; }
.row.indent { margin-left: 28px; padding-left: 10px; border-left: 2px solid var(--mi-border); }
.who-line { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.who { font-size: 13px; font-weight: 700; color: var(--mi-mist); }
.dot, .time { font-size: 12px; color: var(--mi-text-dim); }
.reply-btn { margin-left: auto; }
.text { margin: 6px 0 0; font-size: 14px; line-height: 1.9; white-space: pre-wrap; word-break: break-word; color: var(--mi-text); }
.to { color: var(--mi-mist); }
.expand { margin: 2px 0 6px 28px; }
.cap { margin: 2px 0 6px 28px; font-size: 12px; color: var(--mi-text-dim); }
.cap.fail { color: var(--mi-primary); }
.pager { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.end { font-size: 12px; color: var(--mi-text-dim); }
.composer { margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--mi-border); }
.replying { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 12px; color: var(--mi-mist); }
.ops { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.grow { flex: 1; }
.len { font-size: 12px; color: var(--mi-text-dim); }
.len.over { color: #d84a4a; font-weight: 700; }
.tip-line { margin: 8px 0 0; font-size: 13px; color: var(--mi-primary); }
.hint { margin: 6px 0 0; font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); }
.footnote { margin: 14px 0 0; font-size: 12px; line-height: 1.9; color: var(--mi-text-dim); }
</style>
