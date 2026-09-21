<template>
  <div class="publish">
    <section class="mi-card">
      <post-composer :topic-list="topicList" :preset-type="presetType" @published="onPublished" />
    </section>

    <section class="mi-card rules">
      <h2>发之前值得知道的四件事</h2>
      <ul class="lines">
        <li>先审后发：机器审核秒级放行；命中灰词会转人工，这条期间只有你自己看得到，列表里会标「审核中」。</li>
        <li>危机内容不删：出现自伤、伤人等表达时照常放行，同时给你 12356 求助入口并生成人工工单（拦内容不拦人）。</li>
        <li>匿名不是隐藏：匿名只换展示名并遮罩正文里的联系方式，账号违规仍按实名追责。</li>
        <li>新手期每天 5 帖（含被拦下的），注册满 24 小时解除；这是防刷屏，不是针对你。</li>
      </ul>
      <p class="dim">上面四条都是后端真实规则的复述，出处分别是任务 3.3 状态机、3.12 频率限制与需求 BR5/FR1.4。</p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { topics } from '@/api/feed'
import PostComposer from '@/components/PostComposer.vue'

// U5 独立发布页（手册 §6.2）：广场顶部那张卡是同一组件的紧凑形态，
// 两处共用一个组件而不是复制两份，是为了让「字数上限/匿名遮罩/求助卡片」这些口径只有一处能改。
const router = useRouter()
const topicList = ref([])
const presetType = computed(() => {
  const t = router.currentRoute.value.query.type
  return t === 'hole' || t === 'help' ? t : ''
})

async function loadTopics() {
  try {
    const data = await topics(20)
    topicList.value = Array.isArray(data) ? data : []
  } catch (e) {
    topicList.value = []
  }
}

function onPublished(data) {
  if (!data || !data.id) return
  ElMessage.success('已发布，正在打开这条帖子')
  router.push({ name: 'post-detail', params: { id: data.id } })
}

onMounted(loadTopics)
</script>

<style scoped>
.publish { display: flex; flex-direction: column; gap: 18px; }
.rules h2 { margin: 0 0 8px; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
.lines { margin: 0; padding-left: 20px; line-height: 2; font-size: 13px; color: var(--mi-text-dim); }
.dim { margin: 10px 0 0; font-size: 12px; color: var(--mi-text-dim); }
</style>
