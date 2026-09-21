<template>
  <div class="page">
    <div class="topbar">
      <div>
        <h1 class="h1">我的帖子</h1>
        <p class="dim">这里能看到「仅自己可见」和「还没过审」的帖子。广场上找不到它们，那是可见性分工，不是系统把帖吞了。</p>
      </div>
      <div class="ops">
        <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        <el-button size="small" text @click="goProfile">账户中心</el-button>
        <el-button size="small" type="primary" @click="goPublish">去发帖</el-button>
      </div>
    </div>

    <section class="mi-card bar">
      <el-radio-group v-model="activeStatus" size="small" @change="reload">
        <el-radio-button v-for="t in TABS" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>
      <span class="dim">{{ countLine }}</span>
    </section>

    <stage-notice v-if="errorCode" :code="errorCode" stage="3" api-name="GET /api/users/me/posts" :extra="listExtra" />

    <template v-else>
      <div v-loading="loading && items.length === 0" class="list">
        <el-empty v-if="!loading && items.length === 0" :description="emptyText">
          <el-button type="primary" @click="goPublish">去发帖</el-button>
        </el-empty>
        <post-card v-for="item in items" :key="item.id" :item="item" show-status :dismissable="false" />
      </div>

      <div class="more">
        <el-button v-if="items.length && hasMore" size="small" :loading="loading" @click="loadMore">加载更多</el-button>
        <span v-else-if="items.length" class="dim">到这里就是全部了（已加载 {{ items.length }} 条）</span>
      </div>
    </template>

    <section class="mi-card blk gap">
      <h2 class="h2">这一页还欠什么</h2>
      <ul class="lines">
        <li>状态一次只能筛一个值：后端 status 是单值等值过滤（白名单外直接 10001），所以没有「人工审核中 + 未通过」的合并视图。</li>
        <li>收藏、浏览历史、匿名发帖记录、通知设置四个子页未开工：对应表在 sql 里已建，读接口还没有，这里不摆点开是空壳的入口。</li>
        <li>编辑与删除未开放：本批任务只做到「发出去的帖找得回来」，post 的更新与删除接口还没排到，所以卡片上只有「看」，没有「改」。</li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { myPosts } from '@/api/user'
import { CODE } from '@/api/errorCode'
import { usePagedPosts } from '@/composables/usePagedPosts'
import PostCard from '@/components/PostCard.vue'
import StageNotice from '@/components/StageNotice.vue'

// 四个 Tab 就是后端 status 白名单里的四个值，「全部」用空串表示干脆不传（后端 null 与空串都不加状态条件）。
// 不放 DRAFT / MACHINE_REVIEW 两个 Tab：草稿入口未开工，机审又是在发帖请求里同步跑完的，
// 用户侧几乎不可能停在这两态，摆一个常年为空的 Tab 只会让人以为功能坏了 —— 后端支持不等于界面上该有。
const TABS = [
  { value: '', label: '全部' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'HUMAN_REVIEW', label: '人工审核中' },
  { value: 'REJECTED', label: '未通过' }
]

const router = useRouter()
const activeStatus = ref('')

const { items, loading, hasMore, total, errorCode, reload, loadMore } = usePagedPosts(myPosts, {
  // query() 在发请求那一刻才求值，所以切 Tab 与「加载更多」共用同一个闭包，不需要手动把状态同步进参数
  query: () => (activeStatus.value ? { status: activeStatus.value } : {})
})

const countLine = computed(() => {
  const loaded = '已加载 ' + items.value.length + ' 条'
  if (total.value < 0) return loaded
  return (activeStatus.value ? '该状态共 ' : '我发过 ') + total.value + ' 条 · ' + loaded
})

const emptyText = computed(() => (activeStatus.value
  ? '这个状态下还没有帖子'
  : '还没有发过帖子。第一条可以从树洞开始，它到期会自动销毁。'))

const listExtra = computed(() => {
  if (Number(errorCode.value) === CODE.DB_UNAVAILABLE) {
    return '数据库暂不可用：这张列表要读 post 表，后端按「库挂了也让页面活着」的口径降级，所以这里是空态而不是白屏。'
  }
  return ''
})

function goProfile() {
  router.push({ name: 'me' })
}

function goPublish() {
  router.push({ name: 'publish' })
}

onMounted(reload)
</script>

<style scoped>
.page { max-width: 900px; margin: 0 auto; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h1 { margin: 0; font-size: 22px; }
.h2 { margin: 0 0 8px; font-size: 16px; color: var(--mi-mist); }
.ops { display: flex; gap: 4px; flex-shrink: 0; }
.bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-top: 14px; padding: 12px 18px; }
.list { display: flex; flex-direction: column; gap: 12px; margin-top: 14px; }
.more { display: flex; justify-content: center; align-items: center; min-height: 44px; }
.blk { margin-top: 14px; }
.lines { margin: 0; padding-left: 20px; font-size: 13px; line-height: 2; color: var(--mi-text-dim); }
.dim { font-size: 12px; color: var(--mi-text-dim); line-height: 1.8; }
</style>
