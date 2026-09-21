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
           评论树（T3.7）与相似帖推荐（FR5.6 / 阶段 6）确实还没有接口，这里留空而不是摆假数据。 -->
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
        <span class="acts-note">计数由后端按真实互动记录重算，与广场卡片上看到的应是同一个数。</span>
      </div>
      <p class="footnote">评论区排在任务 3.7、相似推荐排在阶段 6，本期此处为空白是正常的。</p>
    </article>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { postDetail } from '@/api/post'
import { usePostInteract } from '@/composables/usePostInteract'
import { CODE } from '@/api/errorCode'
import { fromNow, countdown, fmtCount, fmtDateTime } from '@/utils/format'
import CrisisCard from '@/components/CrisisCard.vue'
import StageNotice from '@/components/StageNotice.vue'

// U4 详情页。每打开一次就是后端一次真实计数（缓存累加 + 每 5 分钟回写），
// 所以这里绝不做「本地 +1」的假乐观更新——两边各加一次，数字就会凭空翻倍。
const route = useRoute()
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

watch(() => route.params.id, load, { immediate: true })
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
.footnote { margin: 12px 0 0; font-size: 12px; color: var(--mi-text-dim); }
.acts { display: flex; align-items: center; gap: 12px; margin-top: 16px; flex-wrap: wrap; }
.acts .act { font-size: 13px; }
.acts .act.act-on { font-weight: 700; }
.acts-note { font-size: 12px; color: var(--mi-text-dim); }
.meta .stat { white-space: nowrap; }
.gone { max-width: 620px; }
.gone-desc { font-size: 14px; color: var(--mi-text); }
.gone-note { font-size: 12px; color: var(--mi-text-dim); line-height: 1.8; }
</style>
