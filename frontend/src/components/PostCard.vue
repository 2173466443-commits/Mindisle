<template>
  <article class="mi-card post" @click="goDetail">
    <header class="head">
      <el-tag v-if="item.type === 'hole'" size="small" type="warning" effect="dark">树洞</el-tag>
      <el-tag v-else-if="item.type === 'help'" size="small" type="danger" effect="dark">求助</el-tag>
      <el-tag v-else size="small" effect="dark">分享</el-tag>
      <span class="who">{{ item.displayName || '屿友' }}</span>
      <el-tag v-if="item.anonymous" size="small" type="info">匿名</el-tag>
      <span class="dot">·</span>
      <span class="time">{{ shownAt }}</span>
      <span v-if="destroyLine" class="destroy">{{ destroyLine }}</span>
      <el-button class="hide" size="small" text @click.stop="$emit('dismiss', item.id)">不感兴趣</el-button>
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

    <footer class="meta">
      <span>浏览 {{ fmtCount(item.viewCnt) }}</span>
      <span>赞 {{ fmtCount(item.likeCnt) }}</span>
      <span>评论 {{ fmtCount(item.commentCnt) }}</span>
    </footer>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { fromNow, countdown, fmtCount } from '@/utils/format'
import CrisisCard from '@/components/CrisisCard.vue'

// 广场卡片（U3）。入参就是后端 PostListItem 的原始字段，不做二次映射：
// 前端自己造一套字段名，接口一改就要在两处找 bug。
const props = defineProps({
  item: { type: Object, required: true }
})
defineEmits(['dismiss'])

const router = useRouter()

const shownAt = computed(() => fromNow(props.item.publishedAt) || '尚未发布')
// 只有树洞有 autoDestroyAt；普通帖这个字段是 null，倒计时不显示。
const destroyLine = computed(() => (props.item.autoDestroyAt ? countdown(props.item.autoDestroyAt) : ''))
const previewList = computed(() => (props.item.images || []).map((x) => x.url))

function goDetail() {
  router.push({ name: 'post-detail', params: { id: props.item.id } })
}
</script>

<style scoped>
.post { cursor: pointer; padding: 16px 18px; }
.post:hover { border-color: var(--mi-primary); }
.head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.who { font-size: 13px; font-weight: 700; color: var(--mi-mist); }
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
</style>
