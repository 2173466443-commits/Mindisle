<template>
  <div class="publish">
    <!-- 从话题页「在这个话题下发一条」跳过来时带 ?topic=编号：这一行是给用户确认「带上了没有」。 -->
    <p v-if="presetTopicId" class="preset-line" :class="'is-' + presetState">{{ presetLine }}</p>

    <section class="mi-card">
      <post-composer :topic-list="topicList" :preset-type="presetType" :preset-topic-ids="presetTopicIds"
                     @published="onPublished" />
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { topics } from '@/api/feed'
import { topicDetail } from '@/api/topic'
import PostComposer from '@/components/PostComposer.vue'

// U5 独立发布页（手册 §6.2）：广场顶部那张卡是同一组件的紧凑形态，
// 两处共用一个组件而不是复制两份，是为了让「字数上限/匿名遮罩/求助卡片」这些口径只有一处能改。
const router = useRouter()
const topicList = ref([])
const presetType = computed(() => {
  const t = router.currentRoute.value.query.type
  return t === 'hole' || t === 'help' ? t : ''
})

// 话题页跳过来的 ?topic=：判定口径与 TopicDetailView 的 :id 完全一致（正整数才认），
// 0 表示「这个页面不是从话题页来的」。不认 ?topic=abc 也不认 0/-1，那两种写法直接当没带。
const presetTopicId = computed(() => {
  const raw = String(router.currentRoute.value.query.topic ?? '')
  return /^[1-9][0-9]*$/.test(raw) ? Number(raw) : 0
})
// 解析成功才有 name —— el-select 的选项文案是「# 话题名」，拿不到资料时宁可提示「带不上」，
// 也不要让下拉框显示一个裸编号（那看起来像是选中了，实际会发给后端一个不存在的话题）。
const presetTopic = ref(null)
const presetTopicIds = computed(() => (presetTopic.value ? [presetTopic.value.id] : []))
// 三态而不是两态：解析要等一次接口，中途直接报「带不上」等于给用户看一句假话。
const presetChecking = ref(false)
const presetState = computed(() => {
  if (!presetTopicId.value) return 'idle'
  if (presetChecking.value) return 'checking'
  return presetTopic.value ? 'ok' : 'miss'
})
const presetLine = computed(() => {
  if (presetState.value === 'checking') return '正在确认这个话题…'
  if (presetState.value === 'ok') {
    return '已带上话题 # ' + presetTopic.value.name + '：发布后会出现在这个话题的帖子列表里。'
  }
  return '这个话题现在带不上（可能在预审中、已下架或编号不存在）。照常发帖，然后在上面手动选话题即可。'
})
// 两个话题页之间直接跳转时路由名相同、组件复用，onMounted 不会再跑，所以编号一变就要重解析。
watch(presetTopicId, () => loadTopics())

async function loadTopics() {
  const id = presetTopicId.value
  if (id) presetChecking.value = true
  let list = []
  try {
    const data = await topics(20)
    list = Array.isArray(data) ? data : []
  } catch (e) {
    list = []
  }
  presetTopic.value = null
  if (id) {
    // 话题墙只放官方已过审的那些（limit 20），自建话题与非墙上的话题都不在里面，
    // 所以要把刚点进来的这一个单独补进候选项，否则下拉框里根本没有它可选。
    const hit = list.find((t) => t && Number(t.id) === id)
    if (hit) {
      presetTopic.value = { id: Number(hit.id), name: hit.name }
    } else {
      try {
        const one = await topicDetail(id)
        if (one && Number(one.id) === id) presetTopic.value = { id: Number(one.id), name: one.name }
      } catch (e) {
        // 待审 30004 / 不存在 90006 都会到这里：silent 接口，页面自己说清楚，不弹全局红条。
        presetTopic.value = null
      }
      if (presetTopic.value) list = [presetTopic.value, ...list]
    }
  }
  topicList.value = list
  if (id) presetChecking.value = false
}

function onPublished(data) {
  if (!data || !data.id) return
  ElMessage.success('已发布，正在打开这条帖子')
  router.push({ name: 'post-detail', params: { id: data.id } })
}

onMounted(loadTopics)
</script>

<style scoped>
/* 改版：表单不收宽就是一整条 1600 的输入框，字走一行要转头看。居中收到 960。 */
.publish { display: flex; flex-direction: column; gap: 18px; max-width: 960px; margin: 0 auto; }
.preset-line { margin: 0; font-size: 13px; line-height: 1.8; }
.preset-line.is-ok { color: var(--mi-mist); }
.preset-line.is-checking { color: var(--mi-text-dim); }
.preset-line.is-miss { color: var(--mi-warn); }
.rules h2 { margin: 0 0 8px; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
.lines { margin: 0; padding-left: 20px; line-height: 2; font-size: 13px; color: var(--mi-text-dim); }
.dim { margin: 10px 0 0; font-size: 12px; color: var(--mi-text-dim); }
</style>
