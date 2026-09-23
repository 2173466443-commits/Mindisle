<template>
  <div class="page">
    <div class="topbar">
      <div>
        <h1 class="h1">{{ title }}</h1>
        <p class="dim">这里只放这个人「已经发布、并且选择公开」的帖子，匿名与马甲帖恒不出现。</p>
      </div>
      <div class="ops">
        <el-button size="small" :loading="loading" :disabled="!targetId" @click="reload">刷新</el-button>
        <el-button size="small" text @click="goFeed">回广场</el-button>
      </div>
    </div>

    <!-- 资料卡（T3.6 补上的一半）：读 GET /api/users/{id}/profile。
         这张卡上的字段是后端挑过的公开口径 —— 年级 / 院系 / 性别 / risk_flag 恒不出现，
         获赞数也只算「公开且非匿名」的帖子（需求 FR1.4：看得到这个人公开说过什么，不等于能从主页推出他是谁）。
         取不到资料卡不挡帖子列表：那是一行锦上添花的字，不是一块空白屏。 -->
    <section v-if="card" class="mi-card card blk">
      <el-avatar :size="52" :src="card.avatar || ''">{{ avatarText }}</el-avatar>
      <div class="card-main">
        <div class="card-name">{{ card.displayName }}</div>
        <p class="card-bio">{{ card.bio || '这个人还没有留下简介。' }}</p>
        <div class="card-stats">
          <span>公开帖子 {{ fmtCount(card.publicPostCnt) }}</span>
          <span>关注 {{ fmtCount(card.followingCnt) }}</span>
          <span>粉丝 {{ fmtCount(card.followerCnt) }}</span>
          <span>获赞 {{ fmtCount(card.receivedLikeCnt) }}</span>
        </div>
      </div>
      <div class="card-op">
        <el-button v-if="!card.self" size="small" round :type="card.following ? 'default' : 'primary'"
                   :plain="card.following" :disabled="followBusy" @click="actFollow">
          {{ card.following ? '已关注' : '关注' }}
        </el-button>
        <span v-else class="dim">这是你自己的主页</span>
      </div>
    </section>
    <p v-else-if="cardNote" class="card-note">{{ cardNote }}</p>

    <el-alert v-if="!targetId" type="error" show-icon :closable="false" class="blk"
              title="地址里的用户 id 不是数字"
              description="主页地址形如 /user/23。后端那条路由也只匹配数字 id（非数字直接 404/90006，不是 500），所以这里不替人猜那是谁的主页。" />

    <el-alert v-else-if="notFound" type="warning" show-icon :closable="false" class="blk"
              title="看不到这个主页"
              description="账号不存在与已注销在后端回的是同一个码：能区分就等于给外人一条免费的「这个号还在不在」枚举通道。" />

    <stage-notice v-else-if="errorCode" :code="errorCode" stage="3" api-name="GET /api/users/:id/posts" :extra="listExtra" />

    <template v-else>
      <div v-loading="loading && items.length === 0" class="list">
        <el-empty v-if="!loading && items.length === 0"
                  description="这里还没有可展示的公开帖子。对方发过但选择仅自己可见的内容不会出现在别人主页上。" />
        <post-card v-for="item in items" :key="item.id" :item="item" show-status :dismissable="false" />
      </div>

      <div class="more">
        <el-button v-if="items.length && hasMore" size="small" :loading="loading" @click="loadMore">加载更多</el-button>
        <span v-else-if="items.length" class="dim">到这里就是全部了（已加载 {{ items.length }} 条）</span>
      </div>
    </template>

    <section class="mi-card blk gap">
      <h2 class="h2">这一页刻意有什么、刻意没有什么</h2>
      <p class="para">
        资料卡与关注按钮来自任务 3.6 的两个新接口（GET /api/users/:id/profile、POST /api/users/:id/follow）。
        它们不是把 /api/users/me/profile 改个入参就读别人：读别人走的是单独一条公开口径，
        年级、院系、性别、risk_flag 不出参，获赞数只统计公开非匿名帖。
      </p>
      <p class="para">
        仍然没有的两样：私信入口（后端接口排在阶段 4 之后）与「关注列表 / 粉丝列表」页。
        后者不是顺手就能加的 —— 一旦能顺着关系逐跳，匿名与马甲的可关联性就得先过一遍评审，
        所以这里只给一个按钮，不给一张可以顺着点下去的关系网。
      </p>
      <p class="para">
        列表里的点赞收藏同样是真接口：在这张主页上点过的赞，回广场看同一张卡片还是亮的，
        因为那个红心态的判定来自后端的互动记录，不是某个页面的本地变量。
      </p>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { userPosts, userHomepage, followUser } from '@/api/user'
import { fmtCount } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import { CODE } from '@/api/errorCode'
import { usePagedPosts } from '@/composables/usePagedPosts'
import PostCard from '@/components/PostCard.vue'
import StageNotice from '@/components/StageNotice.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 资料卡：null 表示还没取到。取不到就连按钮也不画 —— 宁可少一块卡片，也不留一颗点了没反应的「关注」
const card = ref(null)
const cardNote = ref('')
const followBusy = ref(false)
const avatarText = computed(() => (card.value && card.value.displayName ? card.value.displayName.slice(0, 1) : '屿'))

// 与后端 UserPostController 那条路径变量的约束保持同一形状（那边限定只能是数字，这里再要求不带前导 0）：
// 前端先判一次，是为了地址栏打错时能给出一句人话，而不是把 abc 发出去换回一个 404 再让人猜为什么是空白页。
const targetId = computed(() => {
  const raw = String(route.params.id || '')
  return /^[1-9][0-9]*$/.test(raw) ? raw : ''
})

const title = computed(() => (targetId.value ? '屿友 ' + targetId.value + ' 的主页' : '屿友主页'))

const { items, loading, hasMore, errorCode, reload, loadMore, clear } = usePagedPosts(
  (params) => userPosts(targetId.value, params),
  {}
)

const notFound = computed(() => Number(errorCode.value) === CODE.USER_NOT_FOUND)
const listExtra = computed(() => (Number(errorCode.value) === CODE.DB_UNAVAILABLE
  ? '数据库暂不可用：主页列表要读 post 表，后端按「库挂了也让页面活着」的口径降级，这里是空态而不是白屏。'
  : ''))

async function loadCard() {
  card.value = null
  cardNote.value = ''
  if (!targetId.value) return
  try {
    card.value = await userHomepage(targetId.value)
  } catch (e) {
    // 401 不在这里处理：http 层已经把未登录的人带去登录页了。这里只保证「卡片没了，列表还在」
    cardNote.value = '资料卡暂时取不到（' + ((e && e.code) || 'network') + '），帖子列表照常显示。'
  }
}

/** 关注 / 取关：与点赞同一套「先改界面、以回执覆盖、失败回滚」，理由见 usePostInteract 的注释。 */
async function actFollow() {
  if (!card.value || followBusy.value) return
  if (!userStore.isLogged) {
    router.replace({ name: 'login', query: { redirect: route.fullPath } })
    return
  }
  const snapshot = { following: !!card.value.following, followerCnt: Number(card.value.followerCnt) || 0 }
  const next = !snapshot.following
  card.value.following = next
  card.value.followerCnt = Math.max(0, snapshot.followerCnt + (next ? 1 : -1))
  followBusy.value = true
  try {
    const view = await followUser(targetId.value, next ? 'follow' : 'unfollow')
    if (view && typeof view === 'object') {
      card.value.following = !!view.following
      card.value.followerCnt = Number(view.followerCnt) || 0
    }
  } catch (e) {
    card.value.following = snapshot.following
    card.value.followerCnt = snapshot.followerCnt
  } finally {
    followBusy.value = false
  }
}

// 从一个主页跳到另一个主页时 Vue 复用同一个组件实例，不重新 mounted。
// 不 watch 路由参数，页面就会停在上一个人的列表上 —— 这是「切主页看到上一个人的缓存」的另一半成因。
// 资料卡必须跟着一起换：漏了它，A 的主页上那颗「关注」点的会是 B。
watch(targetId, (to) => {
  if (!to) {
    // 与话题详情页同一形状：编号改成读不了的形状时，上一张资料卡会连那颗「关注」按钮一起留在屏上，
    // 而按钮用的已经是地址栏里那个 abc。清卡片 + clear()，别把「地址栏说一套、屏幕画另一套」留给用户去发现。
    card.value = null
    cardNote.value = ''
    clear()
    return
  }
  reload()
  loadCard()
})

onMounted(() => {
  if (targetId.value) {
    reload()
    loadCard()
  }
})

function goFeed() {
  router.push({ name: 'feed' })
}
</script>

<style scoped>
.page { max-width: 900px; margin: 0 auto; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h1 { margin: 0; font-size: 22px; }
.h2 { margin: 0 0 8px; font-size: 16px; color: var(--mi-mist); }
.ops { display: flex; gap: 4px; flex-shrink: 0; }
.list { display: flex; flex-direction: column; gap: 12px; margin-top: 14px; }
.more { display: flex; justify-content: center; align-items: center; min-height: 44px; }
.blk { margin-top: 14px; }
.para { margin: 0 0 8px; font-size: 13px; line-height: 1.9; color: var(--mi-text-dim); }
.card { display: flex; align-items: flex-start; gap: 14px; }
.card-main { flex: 1 1 auto; min-width: 0; }
.card-name { font-size: 16px; font-weight: 700; color: var(--mi-text); }
.card-bio { margin: 6px 0 8px; font-size: 13px; line-height: 1.8; color: var(--mi-text-dim); word-break: break-word; }
.card-stats { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12px; color: var(--mi-text-dim); }
.card-op { flex-shrink: 0; }
.card-note { margin: 10px 0 0; font-size: 12px; color: var(--mi-text-dim); }
.dim { font-size: 12px; color: var(--mi-text-dim); line-height: 1.8; }
</style>
