<template>
  <article class="mi-card post" @click="goDetail">
    <header class="head">
      <el-tag v-if="item.type === 'hole'" size="small" type="warning" effect="dark">树洞</el-tag>
      <el-tag v-else-if="item.type === 'help'" size="small" type="danger" effect="dark">求助</el-tag>
      <el-tag v-else size="small" effect="dark">分享</el-tag>
      <span class="who" :class="{ link: canOpenAuthor }" @click.stop="openAuthor">{{ item.displayName || '屿友' }}</span>
      <el-tag v-if="item.anonymous" size="small" type="info">匿名</el-tag>
      <span class="dot">·</span>
      <span class="time">{{ shownAt }}</span>
      <span v-if="destroyLine" class="destroy">{{ destroyLine }}</span>
      <el-tag v-if="showStatus && item.status" size="small" :type="statusTone" effect="plain">{{ statusText }}</el-tag>
      <el-tag v-if="showStatus && item.visibility === 'private'" size="small" type="info" effect="plain">仅自己可见</el-tag>
      <el-button v-if="dismissable" class="hide" size="small" text @click.stop="$emit('dismiss', item.id)">不感兴趣</el-button>
    </header>

    <h3 class="title">{{ item.title }}</h3>
    <p class="excerpt">{{ item.excerpt }}</p>

    <div v-if="item.images && item.images.length" class="imgs" @click.stop>
      <el-image v-for="(img, i) in item.images" :key="i" :src="img.url" :preview-src-list="previewList"
                :initial-index="i" fit="cover" class="img" preview-teleported hide-on-click-modal />
    </div>

    <div v-if="item.topics && item.topics.length" class="topics">
      <span v-for="t in item.topics" :key="t" class="topic"># {{ t }}</span>
    </div>

    <el-alert v-if="item.auditTip" type="warning" :closable="false" show-icon class="tip" :title="item.auditTip" />
    <crisis-card v-if="item.hotline" :hotline="item.hotline" level="inline"
                 text="这条内容提到了求助热线，你也可以拨打全国心理援助热线" />

    <!-- 互动条（T3.6）。三个约定写在这儿，免得后来人以为是漏了什么：
         1) 每个按钮都必须 @click.stop —— 卡片整体是「进详情」，不 stop 就是点一下赞顺带跳一页；
         2) 计数以服务端回执为准，本地只负责立刻点亮（见 usePostInteract 的注释）；
         3) 评论数现在恒为 0，那是 T3.7 的接口还没上，不是这里少写了什么。 -->
    <footer class="meta">
      <span class="stat">浏览 {{ fmtCount(item.viewCnt) }}</span>
      <span class="stat">评论 {{ fmtCount(item.commentCnt) }}</span>
      <span class="grow"></span>
      <el-button class="act" :class="{ 'act-on': item.liked }" size="small" text
                 :disabled="isBusy(item, 'like')" @click.stop="act(item, 'like')">
        {{ item.liked ? '已赞' : '赞' }} {{ fmtCount(item.likeCnt) }}
      </el-button>
      <el-button class="act" :class="{ 'act-on': item.collected }" size="small" text
                 :disabled="isBusy(item, 'collect')" @click.stop="act(item, 'collect')">
        {{ item.collected ? '已收藏' : '收藏' }} {{ fmtCount(item.collectCnt) }}
      </el-button>
    </footer>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { fromNow, countdown, fmtCount } from '@/utils/format'
import { statusLabel } from '@/api/post'
import { usePostInteract } from '@/composables/usePostInteract'
import CrisisCard from '@/components/CrisisCard.vue'

// 广场卡片（U3）。入参就是后端 PostListItem 的原始字段，不做二次映射：
// 前端自己造一套字段名，接口一改就要在两处找 bug。
const props = defineProps({
  item: { type: Object, required: true },
  // 状态徽标只在「我的帖子 / 屿友主页」这类列表里画：广场按可见性过滤之后 status 恒为 PUBLISHED，
  // 在那儿显示徽标是一排毫无信息量的绿标签，还会让人误以为广场能看到未过审的帖。
  showStatus: { type: Boolean, default: false },
  // 「不感兴趣」是广场的负反馈交互，挂到自己的帖子上语义就成了「我不想再看到自己发的东西」，且后端还没有对应接口。
  dismissable: { type: Boolean, default: true }
})
defineEmits(['dismiss'])

const router = useRouter()
// 点赞与收藏：卡片只改列表里那一条原始对象，失败回滚由 composable 负责
const { isBusy, toggle } = usePostInteract()
const act = function (it, kind) { return toggle(it, kind) }

const shownAt = computed(() => fromNow(props.item.publishedAt) || '尚未发布')
// 只有树洞有 autoDestroyAt；普通帖这个字段是 null，倒计时不显示。
const destroyLine = computed(() => (props.item.autoDestroyAt ? countdown(props.item.autoDestroyAt) : ''))
const previewList = computed(() => (props.item.images || []).map((x) => x.url))

function goDetail() {
  router.push({ name: 'post-detail', params: { id: props.item.id } })
}

// 匿名帖的 authorId 后端恒为 null（PostListItem 的注释写了理由：留着 user_id 抓包就能反查作者，匿名等于没做）。
// 所以「能不能点进作者主页」不必在前端再判一次 anonymous —— 字段为空就是不可点。
// 少一个 if，就少一处「前端得自己记得匿名规则」的地方，规则只留在服务端一处。
const canOpenAuthor = computed(() => props.item.authorId !== null && props.item.authorId !== undefined)
const statusText = computed(() => statusLabel(props.item.status))
const statusTone = computed(() => {
  const s = props.item.status
  if (s === 'PUBLISHED') return 'success'
  if (s === 'REJECTED' || s === 'TAKEDOWN' || s === 'DELETED') return 'danger'
  if (s === 'HUMAN_REVIEW' || s === 'MACHINE_REVIEW' || s === 'APPEALING') return 'warning'
  return 'info'
})

function openAuthor() {
  if (!canOpenAuthor.value) return
  router.push({ name: 'user-home', params: { id: props.item.authorId } })
}
</script>

<style scoped>
.post { cursor: pointer; padding: 16px 18px; }
.post:hover { border-color: var(--mi-primary); }
.head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.who { font-size: 13px; font-weight: 700; color: var(--mi-mist); }
.who.link { cursor: pointer; }
.who.link:hover { color: var(--mi-primary); text-decoration: underline; }
.dot, .time { font-size: 12px; color: var(--mi-text-dim); }
.destroy { font-size: 12px; color: var(--mi-primary); }
.hide { margin-left: auto; }
.title { margin: 10px 0 6px; font-size: 16px; line-height: 1.5; color: var(--mi-text); }
.excerpt { margin: 0; font-size: 13px; line-height: 1.8; color: var(--mi-text-dim); white-space: pre-wrap; word-break: break-word; }
.imgs { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.img { width: 96px; height: 96px; border-radius: 10px; border: 1px solid var(--mi-border); }
.topics { display: flex; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
.topic { font-size: 12px; color: var(--mi-mist); }
.tip { margin-top: 10px; }
.meta { display: flex; gap: 16px; margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--mi-border); font-size: 12px; color: var(--mi-text-dim); }
.meta .stat { white-space: nowrap; }
.meta .grow { flex: 1 1 auto; }
.meta .act { padding: 0 4px; font-size: 12px; color: var(--mi-text-dim); }
.meta .act.act-on { color: var(--mi-primary); font-weight: 700; }
.meta .act + .act { margin-left: 0; }
</style>
