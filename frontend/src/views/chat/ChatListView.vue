<template>
  <div class="page">
    <div class="topbar">
      <div>
        <h1 class="h1">私信</h1>
        <p class="dim">
          与屿友一对一说话。{{ unreadLine }}
          <span v-if="!isRealtime">实时通道未连接，本页每 30 秒自动刷新一次。</span>
        </p>
      </div>
      <div class="ops">
        <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
        <el-button size="small" type="primary" round @click="openNew">发起新会话</el-button>
        <el-button size="small" text @click="openBlocks">黑名单（{{ pm.blocks.length || blockCount }}）</el-button>
      </div>
    </div>

    <el-input v-model="keyword" size="small" clearable placeholder="按昵称或最后一句筛选（只筛已经加载出来的这些会话）"
              class="filter" />

    <el-alert v-if="pm.convError" type="error" show-icon :closable="false" class="blk"
              :title="'会话列表没读到：' + pm.convError" />

    <section v-loading="loading && pm.conversations.length === 0" class="mi-card list">
      <el-empty v-if="!loading && rows.length === 0" :image-size="80"
                :description="pm.conversations.length ? '没有匹配这个关键词的会话。' : '还没有人给你发过私信。去广场或话题圈认识一个人，再点进 TA 的主页发起会话。'" />
      <button v-for="it in rows" :key="it.peerId" type="button" class="row" @click="openThread(it)">
        <span class="ava">
          <el-avatar :size="42" :src="it.peerAvatar || ''">{{ avatarText(it) }}</el-avatar>
          <span v-if="isOn(it)" class="presence"></span>
        </span>
        <span class="main">
          <span class="line1">
            <span class="name">{{ it.peerName || ('屿友 ' + it.peerId) }}</span>
            <span class="time">{{ timeText(it.lastAt) }}</span>
          </span>
          <span class="line2">
            <span class="last">{{ it.lastMine ? '我：' : '' }}{{ preview(it) }}</span>
            <el-badge v-if="unreadOf(it) > 0" :value="unreadOf(it)" :max="99" class="badge" />
          </span>
        </span>
      </button>
      <div v-if="pm.convHasMore" class="more">
        <el-button size="small" text :loading="loading" @click="loadMore">看更早的会话</el-button>
      </div>
    </section>

    <section class="mi-card blk gap">
      <h2 class="h2">这一页有什么、没有什么</h2>
      <p class="para">
        会话列表、最后一条摘要、未读数、在线点都来自后端真实读数（GET /api/pm/conversations 一页带齐，
        顶栏那颗私信角标和这里用的是同一个 total，不存在两处各自归零）。
      </p>
      <p class="para">
        没有的两样：<b>删除单个会话</b>与<b>按用户名找人</b>。前者后端没有接口（私信是私密内容，
        真要清掉自己的数据走隐私中心的注销/导出链路，那里有留痕与冷静期）；
        后者需要一个「按用户名查 id」的接口，而那正是还没有的那一条 —— 所以发起会话要填数字 id（在对方主页地址栏里能看到）。
      </p>
    </section>

    <el-dialog v-model="newOpen" title="发起一个新会话" width="420px">
      <p class="dlg-tip dim">
        输入对方的用户 id。TA 的公开主页地址就是 <code>/user/23</code> 这种形状，23 就是它。
        后端只认数字 id，也只在数字 id 上判「是否存在、是否已注销」。
      </p>
      <el-input v-model="newUid" placeholder="例如 23" @keyup.enter="goNew" />
      <p v-if="newError" class="dlg-err">{{ newError }}</p>
      <template #footer>
        <el-button @click="newOpen = false">取消</el-button>
        <el-button type="primary" @click="goNew">进入会话</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="blockOpen" title="我的黑名单" width="460px">
      <p v-if="pm.blocksError" class="dlg-err">{{ pm.blocksError }}</p>
      <el-empty v-else-if="!pm.blocks.length" :image-size="60" description="没有拉黑过任何人。" />
      <ul v-else class="blk-list">
        <li v-for="b in pm.blocks" :key="b.peerId" class="blk-row">
          <el-avatar :size="30" :src="b.peerAvatar || ''">{{ (b.peerName || '屿').slice(0, 1) }}</el-avatar>
          <span class="blk-main">
            <span class="blk-name">{{ b.peerName || ('屿友 ' + b.peerId) }}</span>
            <span class="blk-meta">{{ b.reason || '没写原因' }} · {{ fromNow(b.blockedAt) }}</span>
          </span>
          <el-button size="small" text :loading="busyPeer === String(b.peerId)" @click="doUnblock(b)">解除</el-button>
        </li>
      </ul>
      <p class="dlg-tip dim">
        拉黑只挡今后的消息，历史仍在；对方不会收到「你被拉黑了」的通知，只会看到发送失败。
      </p>
      <template #footer>
        <el-button @click="blockOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { usePmStore } from '@/stores/pm'
import { fromNow } from '@/utils/format'

// 会话列表页（任务 T5.4 · 界面 U9 · 需求 FR6.2）。
//
// 【为什么这一页只读不写】
// 列表的一切状态都归 stores/pm.js：顶栏角标、详情页里的已读、WS 帧回来的新消息都在改同一份
// conversations/unreadTotal。这一页再存一份「我看到的未读」，就会出现「点进会话退了回来红点还在」。
// 所以模板里每个数都是从 store 里现取的 getter。
const pm = usePmStore()
const router = useRouter()
const keyword = ref('')
const loading = computed(() => pm.convLoading)
const blockCount = ref(0)

const unreadLine = computed(() => (pm.unreadTotal > 0
  ? '现在有 ' + pm.unreadTotal + ' 条没读。'
  : '没有未读。'))
const isRealtime = computed(() => pm.transport === 'open')

/**
 * 关键词只筛「已经加载出来的这些行」，不发请求：后端 /conversations 没有 search 参数，
 * 前端造一个「边打字边翻页找」的假搜索，比直接说明「这是本地筛选」更容易让人相信结果是全量。
 */
const rows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const list = pm.conversations
  if (!kw) return list
  return list.filter(function (it) {
    const name = String(it.peerName || '').toLowerCase()
    const last = String(it.lastContent || '').toLowerCase()
    return name.indexOf(kw) >= 0 || last.indexOf(kw) >= 0
  })
})

function unreadOf(it) {
  return Number(it.unreadCnt) || pm.unreadOf(it.peerId)
}

function isOn(it) {
  return !!it.online || pm.isOnline(it.peerId)
}

function avatarText(it) {
  return it.peerName ? String(it.peerName).slice(0, 1) : '屿'
}

/** 摘要里的图片消息后端已经折成「[图片]」，这里不重复判断类型，只管空值。 */
function preview(it) {
  if (it.lastMsgType === 'image') return '[图片]'
  return it.lastContent || '（这条没有文字）'
}

/** 列表时间沿用「今天 HH:mm / 昨天 / M月D日」这一套（FR6.2 明写的格式化口径）。 */
function timeText(value) {
  const day = fromNow(value)
  return day || ''
}

async function reload() {
  await pm.loadConversations(true).catch(function () {})
}

async function loadMore() {
  await pm.loadMoreConversations().catch(function () {})
}

function openThread(it) {
  router.push({ name: 'chat-detail', params: { uid: String(it.peerId) } })
}

const newOpen = ref(false)
const newUid = ref('')
const newError = ref('')

function openNew() {
  newUid.value = ''
  newError.value = ''
  newOpen.value = true
}

function goNew() {
  const raw = String(newUid.value || '').trim()
  if (!/^[1-9][0-9]*$/.test(raw)) {
    // 与路由闸同一形状：后端那条路径变量限 d+，非数字进来只会换回一个 90006 空白页。
    newError.value = 'id 必须是数字，比如 23。'
    return
  }
  newOpen.value = false
  router.push({ name: 'chat-detail', params: { uid: raw } })
}

const blockOpen = ref(false)
const busyPeer = ref('')

async function openBlocks() {
  blockOpen.value = true
  const list = await pm.loadBlocks(true)
  blockCount.value = list ? list.length : 0
}

async function doUnblock(b) {
  busyPeer.value = String(b.peerId)
  try {
    await pm.unblock(b.peerId)
    blockCount.value = pm.blocks.length
  } catch (e) {
    /* 不 silent：http 层已经说过一次 */
  } finally {
    busyPeer.value = ''
  }
}

onMounted(() => {
  reload()
  pm.refreshUnread()
})
</script>

<style scoped>
.page { max-width: 860px; margin: 0 auto; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h1 { margin: 0; font-size: 22px; }
.ops { display: flex; gap: 4px; flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }
.filter { margin-top: 12px; }
.blk { margin-top: 14px; }
.gap { display: flex; flex-direction: column; }
.h2 { margin: 0 0 8px; font-size: 16px; color: var(--mi-mist); }
.para { margin: 0 0 8px; font-size: 13px; line-height: 1.9; color: var(--mi-text-dim); }
.list { padding: 6px 0; display: flex; flex-direction: column; }
.row { display: flex; align-items: center; gap: 12px; width: 100%; padding: 12px 16px; background: none; border: none; border-bottom: 1px solid var(--mi-border); cursor: pointer; text-align: left; color: inherit; font: inherit; }
.row:last-child { border-bottom: none; }
.row:hover { background: rgba(127, 167, 196, 0.07); }
.ava { position: relative; flex: none; }
.presence { position: absolute; right: 0; bottom: 1px; width: 9px; height: 9px; border-radius: 50%; background: #67c23a; border: 2px solid var(--mi-card); }
.main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.line1 { display: flex; justify-content: space-between; gap: 10px; }
.name { font-size: 14px; font-weight: 600; color: var(--mi-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.time { font-size: 11px; color: var(--mi-text-dim); flex: none; }
.line2 { display: flex; align-items: center; gap: 8px; }
.last { flex: 1 1 auto; min-width: 0; font-size: 12px; color: var(--mi-text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.badge { flex: none; }
.more { display: flex; justify-content: center; padding: 8px 0 2px; }
.dim { color: var(--mi-text-dim); font-size: 12px; }
.dlg-tip { margin: 0 0 10px; line-height: 1.8; }
.dlg-tip code { background: var(--mi-bg); padding: 1px 5px; border-radius: 4px; }
.dlg-err { margin: 8px 0 0; font-size: 12px; color: var(--mi-anger); }
.blk-list { list-style: none; margin: 0 0 10px; padding: 0; }
.blk-row { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px dashed var(--mi-border); }
.blk-main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.blk-name { font-size: 13px; color: var(--mi-text); }
.blk-meta { font-size: 11px; color: var(--mi-text-dim); }
</style>
