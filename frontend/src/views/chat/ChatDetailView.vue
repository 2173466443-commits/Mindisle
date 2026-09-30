<template>
  <div class="page">
    <div class="topbar">
      <div class="who">
        <el-button link class="back" @click="goBack">← 返回</el-button>
        <el-avatar :size="40" :src="thread ? thread.peerAvatar : ''">{{ avatarText }}</el-avatar>
        <div class="who-txt">
          <div class="who-name">{{ displayName }}</div>
          <p class="who-meta">
            <span :class="['dot', peerOnline ? 'on' : 'off']"></span>
            <span>{{ peerOnline ? '在线' : '离线' }}</span>
            <span v-if="lastSeen">· 上次在线 {{ fromNow(lastSeen) }}</span>
          </p>
        </div>
      </div>
      <div class="ops">
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
        <el-button size="small" text :disabled="!uid" @click="goPeerHome">看TA的主页</el-button>
        <el-button v-if="!isBlocked" size="small" text type="danger" :disabled="!uid" @click="openBlock">
          不再收TA的消息
        </el-button>
        <el-button v-else size="small" text :disabled="!uid" @click="doUnblock">解除限制</el-button>
      </div>
    </div>

    <!-- 通道状态条（手册 §8.2 第 5 条）。刻意说明「轮询也收得到」而不是隐藏降级：
         用户看不出实时和轮询的差别，但一定看得出「我发了三分钟对方没回」，
         不写这一行，他就会以为是自己没发出去。 -->
    <p v-if="!isRealtime" class="chan">实时通道未连接，本页每 30 秒自动取一次最新一页；消息仍然能发出去（走 HTTP 等价接口）。</p>

    <el-alert v-if="!uid" type="error" show-icon :closable="false" class="blk"
              title="地址里的用户 id 不是数字"
              description="会话地址形如 /chat/23。后端 /api/pm/thread/{peerId} 只匹配数字，非数字直接 90006 而不是 500，所以这里不替人猜那是谁。" />

    <el-alert v-else-if="isSelf" type="warning" show-icon :closable="false" class="blk"
              title="这是你自己的账号"
              description="后端在 send 的第一步就拒绝自发（10001「不能给自己发私信，写给自己可以用草稿箱」），这里提前拦一次，是为了不让人对着一个必然报错的输入框打字。" />

    <el-alert v-if="thread && thread.error" type="error" show-icon :closable="false" class="blk"
              :title="'这个会话没读到：' + thread.error" />

    <div v-if="isBlocked" class="blk blocked">
      <p class="blocked-t">你已经不再接收这个人的消息。</p>
      <p class="blocked-d">
        拉黑之后你发出去的每条都会撞回同一句「对方已开启隐私保护」（30005）——那是后端刻意含糊的说法，
        它不告诉对方是谁挡的，否则拉黑就成了一条可反向探测的关系信号（FR6.7）。历史消息仍然留在你这里。
      </p>
      <el-button size="small" @click="doUnblock">解除限制</el-button>
    </div>

    <template v-else>
      <div ref="scroller" v-loading="loading && messages.length === 0" class="thread mi-card">
        <div class="older">
          <el-button v-if="thread && thread.hasMore" size="small" text :loading="loading" @click="loadOlder">
            看更早的消息
          </el-button>
          <span v-else-if="messages.length" class="dim">到这里就是全部了（本页已加载 {{ messages.length }} 条）</span>
        </div>

        <el-empty v-if="!loading && messages.length === 0" :image-size="70"
                  description="还没有说过话。第一句可以很短，比如「你好」。" />

        <div v-for="group in groups" :key="group.day" class="group">
          <div class="day"><span>{{ group.day }}</span></div>
          <div v-for="m in group.items" :key="bubbleKey(m)" :class="['row', m.mine ? 'mine' : 'peer']">
            <!-- 危机卡放在气泡外面而不是里面：那段话的第一读者是「刚看到这句话的人」，
                 折叠进气泡会被正文的视觉重量压掉，而它恰恰是最不该被扫过去的一行（需求 BR11）。 -->
            <div v-if="m.alert" class="alert-box">
              <p class="alert-t">🫂 屿安在意这条消息</p>
              <p class="alert-d">{{ m.alert }}</p>
              <router-link class="alert-link" :to="{ name: 'help' }">打开心屿求助页（含 24 小时热线）</router-link>
            </div>
            <div :class="['bubble', m.status === 'failed' ? 'is-failed' : '', m.status === 'pending' ? 'is-pending' : '']">
              <el-image v-if="m.msgType === 'image'" :src="m.content" :preview-src-list="[m.content]"
                        fit="cover" class="bubble-img" preview-teleported hide-on-click-modal
                        alt="对方发来的图片" />
              <p v-else class="bubble-txt">{{ m.content }}</p>
              <div class="bubble-meta">
                <span>{{ fmtChatTime(m.createdAt) }}</span>
                <span v-if="m.mine" class="state">{{ statusText(m) }}</span>
              </div>
              <p v-if="m.status === 'failed'" class="fail">
                {{ m.error || '这条私信没发出去' }}
                <el-button link type="primary" size="small" @click="retry(m)">重发</el-button>
              </p>
            </div>
          </div>
        </div>
        <div ref="tail" class="tail"></div>
      </div>

      <div class="composer mi-card">
        <div class="comp-line">
          <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/gif" class="file" @change="pickImage" />
          <el-button size="small" text :loading="uploading" :disabled="!canSend" @click="chooseImage">🖼 图片</el-button>
          <span class="dim comp-tip">
            图片走站内上传链路（重编码去 EXIF 后落在 /uploads/），外链发不出去；一条私信最多 {{ PM_CONTENT_MAX }} 个字。
          </span>
        </div>
        <el-input v-model="draft" type="textarea" :rows="3" resize="none" :maxlength="PM_CONTENT_MAX"
                  show-word-limit :disabled="!canSend" placeholder="说点什么。Enter 发送，Shift + Enter 换行。"
                  @keydown.enter.exact.prevent="submit" />
        <div class="comp-ops">
          <span v-if="draftLen > PM_CONTENT_MAX - 80" class="dim">快到 {{ PM_CONTENT_MAX }} 字上限了，后端会原样退回。</span>
          <el-button type="primary" round :loading="sending" :disabled="!canSend || draftLen === 0" @click="submit">
            发送
          </el-button>
        </div>
      </div>

      <p v-if="messages.length" class="toolbar">
        <el-button link size="small" @click="openReport(null)">举报这段对话</el-button>
        <span class="dim">私信是唯一允许进入人工审核队列的私密内容类型，且只在 L2/L3 生命安全风险下向管理员展示原文（隐私政策已明示）。</span>
      </p>
    </template>

    <!-- 举报：一条一条报才有可办的对象。整段对话那一次只报最新一条，并在文案里说清楚。 -->
    <el-dialog v-model="reportOpen" title="举报这条私信" width="440px">
      <p class="dim dlg-tip">
        举报会把这一条原文送进屿安的审核队列（含危机分级结果）。审核员看到的只有这一条，不是你们的整段对话。
      </p>
      <el-select v-model="reportReason" placeholder="选一个原因" class="dlg-full">
        <el-option v-for="r in PM_REPORT_REASONS" :key="r.value" :label="r.label" :value="r.value" />
      </el-select>
      <el-input v-model="reportNote" type="textarea" :rows="3" :maxlength="PM_REPORT_REMARK_MAX" show-word-limit
                class="dlg-gap" placeholder="补充说明（可以不填）" />
      <template #footer>
        <el-button @click="reportOpen = false">取消</el-button>
        <el-button type="primary" :loading="reportBusy" :disabled="!reportReason || !reportTargetId" @click="doReport">
          提交举报
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="blockOpen" title="不再收这个人的消息" width="440px">
      <p class="dim dlg-tip">
        拉黑之后：对方发给你的消息不会送达，他看到的只是一句「发送失败」，不知道你拉黑了他；
        你也发不出去。历史消息仍然留在你这里，删掉要去隐私中心。
      </p>
      <el-input v-model="blockReason" :maxlength="PM_BLOCK_REASON_MAX" show-word-limit placeholder="原因（只给你自己看，选填）" />
      <template #footer>
        <el-button @click="blockOpen = false">取消</el-button>
        <el-button type="danger" :loading="blockBusy" @click="doBlock">确认拉黑</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { usePmStore } from '@/stores/pm'
import { useUserStore } from '@/stores/user'
import { fromNow, fmtChatTime, fmtChatDay } from '@/utils/format'
import { uploadImage } from '@/api/file'
import { PM_CONTENT_MAX, PM_REPORT_REASONS, PM_REPORT_REMARK_MAX, PM_BLOCK_REASON_MAX } from '@/api/pm'
import { me } from '@/api/user'

// 会话详情页（任务 T5.5 · 界面 U10 · 需求 FR6.2/6.3/6.4/6.5/6.7/6.8）。
//
// 【这一页只干三件事，其余都交给 store】
// 渲染列表、把用户输入变成一次 send、在「看着」的时候把已读报上去。
// 合并去重、断线补拉、轮询降级、回执打勾全在 stores/pm.js 里，
// 这里刻意不复制第二份状态：两处各自维护「哪条已读」就是两处各自出错。
const route = useRoute()
const router = useRouter()
const pm = usePmStore()
const user = useUserStore()

const scroller = ref(null)
const tail = ref(null)
const fileInput = ref(null)
const draft = ref('')
const sending = ref(false)
const uploading = ref(false)
const atBottom = ref(true)

const uid = computed(() => {
  const raw = String(route.params.uid || '')
  return /^[1-9][0-9]*$/.test(raw) ? raw : ''
})
// 本页刚绑定的那个 peer，要用变量锁住，不能用 uid.value 现算：
// 从会话页跳去 /user/5（看TA的主页）时，route.params.uid 已经变成空，
// onUnmounted 里的 pm.activePeer === uid.value 就永远不成立，activePeer 手不交接。
// 后果：那个人之后发的每一条私信都会在用户根本不在会话页时被自动标已读
// ——Gate5 C10 抓到的就是这一条（未读角标 33ms 被自己吃掉，列表页拍到的是「没有未读」）。
let boundUid = ''
const thread = computed(() => (uid.value ? pm.threads[uid.value] || null : null))
const messages = computed(() => (thread.value && Array.isArray(thread.value.list) ? thread.value.list : []))
const loading = computed(() => !!(thread.value && thread.value.loading))
const isSelf = computed(() => !!uid.value && String(user.profile && user.profile.id ? user.profile.id : '') === uid.value)
const isBlocked = computed(() => !!(thread.value && thread.value.blocked))
const peerOnline = computed(() => !!(thread.value && (thread.value.peerOnline || pm.isOnline(uid.value))))
const lastSeen = computed(() => (thread.value ? thread.value.peerLastLoginAt : null))
const displayName = computed(() => (thread.value && thread.value.peerName) || ('屿友 ' + (uid.value || '?')))
const avatarText = computed(() => (displayName.value ? String(displayName.value).slice(0, 1) : '屿'))
const isRealtime = computed(() => pm.transport === 'open')
const canSend = computed(() => !!uid.value && !isSelf.value && !isBlocked.value)
// 码点计数走 Array.from，与 api/post.js 里 commentLength 同一口径（一个 emoji 算 1 个字）。
// [GATE5 修复] 原来这行写的是 draft.value.codePointCount(0, draft.value.length)：JS 的 String
// 上根本没有 codePointCount（那是 Java 的 API），而它待在一个 computed 里，于是每次渲染这个页面
// 都会抛 TypeError，Vue 把整块详情页吞掉 —— 界面是空的、不弹错、连请求都不发。
const draftLen = computed(() => Array.from(draft.value || '').length)

/**
 * 按天分组。分隔条只在跨天那天出现一次 —— 逐条打绝对时间会把气泡撑得比正文长，
 * 逐条打相对时间又回答不了「我们是从哪天开始聊的」，两个都不如「分组 + 条内只到分钟」。
 */
const groups = computed(() => {
  const out = []
  for (const m of messages.value) {
    const day = fmtChatDay(m.createdAt) || '更早'
    const last = out.length ? out[out.length - 1] : null
    if (last && last.day === day) last.items.push(m)
    else out.push({ day: day, items: [m] })
  }
  return out
})

// 待发气泡没有 id，键只能退到 clientMsgId。
function bubbleKey(m) {
  return m.id != null ? 'm' + m.id : 'p' + (m.clientMsgId || '')
}

function statusText(m) {
  if (m.status === 'pending') return '发送中…'
  if (m.status === 'failed') return '没发出去'
  if (m.readAt) return '已读'
  if (m.status === 'delivered') return '已送达'
  if (m.status === 'read') return '已读'
  return ''
}

async function load(reset = true) {
  if (!uid.value || isSelf.value) return
  try {
    await pm.fetchThread(uid.value, reset)
    await syncRead()
    await scrollToBottom(true)
  } catch (e) {
    /* store 已把那句话写进 thread.error，模板就地显示，不再弹全局条 */
  }
}

async function refresh() {
  await load(true)
}

async function loadOlder() {
  const el = scroller.value
  const before = el ? el.scrollHeight : 0
  await pm.loadMoreThread(uid.value).catch(function () {})
  await nextTick()
  // 往更早翻 = 内容从顶上长出来。不补回这段高度，视口会跳到最上面，
  // 用户「想回到刚才那条」得自己再滚一遍 —— 这是所有倒序游标列表共同的坑。
  if (el) el.scrollTop = el.scrollHeight - before + el.scrollTop
}

/** 屏幕上最后一条收到的消息 = 已读上报的水位线（只报到这里，不报到服务端的全量）。 */
async function syncRead() {
  if (!uid.value || isSelf.value) return
  let last = null
  for (const m of messages.value) if (!m.mine && m.id != null) last = m
  if (!last && pm.unreadOf(uid.value) <= 0) return
  await pm.markRead(uid.value, last ? last.id : null)
}

async function scrollToBottom(force) {
  await nextTick()
  if (!force && !atBottom.value) return
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
}

function onScroll() {
  const el = scroller.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

async function submit() {
  if (!canSend.value) return
  const text = draft.value.trim()
  if (!text) return
  if (draftLen.value > PM_CONTENT_MAX) {
    ElMessage.warning('这条超过 ' + PM_CONTENT_MAX + ' 个字了，后端会直接退回')
    return
  }
  sending.value = true
  try {
    await pm.sendText(uid.value, text)
    draft.value = ''
    atBottom.value = true
    await scrollToBottom(true)
  } catch (e) {
    /* 失败已经落在那条气泡上（含原因与重发按钮），这里不再叠一条弹层 */
  } finally {
    sending.value = false
  }
}

async function retry(m) {
  if (!m || !m.clientMsgId) return
  await pm.resend(uid.value, m.clientMsgId).catch(function () {})
}

function chooseImage() {
  if (fileInput.value) fileInput.value.value = ''
  if (fileInput.value) fileInput.value.click()
}

/** 选图 → 先传（/api/files/image，重编码去 EXIF）→ 再发那条 /uploads/ 相对地址。 */
async function pickImage(evt) {
  const file = evt && evt.target && evt.target.files && evt.target.files[0]
  if (!file || !canSend.value) return
  uploading.value = true
  try {
    const data = await uploadImage(file)
    if (!data || !data.url) {
      ElMessage.error('上传没返回图片地址，这条没发出去')
      return
    }
    await pm.sendImage(uid.value, data.url)
    atBottom.value = true
    await scrollToBottom(true)
  } catch (e) {
    ElMessage.error((e && e.message) || '图片没传上去')
  } finally {
    uploading.value = false
  }
}

// ---------------------------------------------------------------- 举报 / 拉黑
const reportOpen = ref(false)
const reportBusy = ref(false)
const reportReason = ref('')
const reportNote = ref('')
const reportTargetId = ref(null)

function openReport(m) {
  const list = messages.value.filter(function (x) { return x.id != null })
  const target = m || (list.length ? list[list.length - 1] : null)
  if (!target) {
    ElMessage.warning('还没有可以举报的消息')
    return
  }
  reportTargetId.value = target.id
  reportReason.value = ''
  reportNote.value = ''
  reportOpen.value = true
}

async function doReport() {
  reportBusy.value = true
  try {
    await pm.report(reportTargetId.value, reportReason.value, reportNote.value)
    reportOpen.value = false
  } catch (e) {
    /* 不 silent 的接口，文案由 http 层说过一次了 */
  } finally {
    reportBusy.value = false
  }
}

const blockOpen = ref(false)
const blockBusy = ref(false)
const blockReason = ref('')

function openBlock() {
  blockReason.value = ''
  blockOpen.value = true
}

async function doBlock() {
  blockBusy.value = true
  try {
    await pm.block(uid.value, blockReason.value)
    blockOpen.value = false
    ElMessage.success('已经不再接收这个人的消息')
  } catch (e) {
    ElMessage.error((e && e.message) || '拉黑没成功')
  } finally {
    blockBusy.value = false
  }
}

async function doUnblock() {
  try {
    await pm.unblock(uid.value)
    ElMessage.success('限制已解除')
    await load(true)
  } catch (e) {
    ElMessage.error((e && e.message) || '解除失败')
  }
}

function goBack() {
  // 从会话列表进来的回列表；直接敲地址进来的（没有历史）回广场，不做「回不去的死角」。
  if (window.history.length > 1) router.back()
  else router.push({ name: 'chat' })
}

function goPeerHome() {
  router.push({ name: 'user-home', params: { id: uid.value } })
}

// 进入/离开这一页要交接 activePeer：已读上报只发给「正被看着」的那个会话，
// 不交接的后果是离开之后新消息仍然被标成已读，而屏幕上早已没有它。
onMounted(() => {
  boundUid = String(uid.value || '')
  pm.setActivePeer(boundUid)
  ensureProfile()
  load(true)
  const el = scroller.value
  if (el) el.addEventListener('scroll', onScroll, { passive: true })
  document.addEventListener('visibilitychange', onVisible)
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisible)
  const el = scroller.value
  if (el) el.removeEventListener('scroll', onScroll)
  if (boundUid && pm.activePeer === boundUid) pm.setActivePeer('')
})

/**
 * 补一次自己的 id。userStore.profile 只在登录成功与资料页里被填过，刷新页面就没了
 * （与 PostDetailView 里那段「顺手补一次 /users/me」是同一个缺口）。
 * 不补的话自发判定会失效，用户能在自己的会话里打字，然后每一条都撞回 10001。
 */
async function ensureProfile() {
  if (user.profile && user.profile.id != null) return
  try {
    const data = await me()
    if (data) user.setProfile(data)
  } catch (e) {
    /* 拿不到就停在「不判自发」，反正后端那一闸一直在 */
  }
}

function onVisible() {
  if (document.visibilityState === 'visible' && boundUid && pm.activePeer === boundUid) syncRead()
}

watch(uid, (to, from) => {
  if (!to) return
  boundUid = to
  pm.setActivePeer(to)
  draft.value = ''
  if (from) {
    // 从一个会话直接切到另一个（列表页里连点两行）：桶可能已经存在，必须显式重取第一页，
    // 否则画的是上一个会话的缓存，而顶上的名字已经换了。
    load(true)
  } else {
    load(!thread.value || !thread.value.loaded)
  }
})

// 新消息到账：自己发的总是滚到底，别人发的只在本来就贴着底的时候滚
// （否则对方连发五条，正在往上翻历史的人会突然被拽回最新一条）。
watch(() => messages.value.length, () => {
  const last = messages.value[messages.value.length - 1]
  scrollToBottom(!!(last && last.mine))
})
</script>

<style scoped>
.page { max-width: 860px; margin: 0 auto; display: flex; flex-direction: column; gap: 12px; }
.topbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.who { display: flex; align-items: center; gap: 10px; min-width: 0; }
.back { font-size: 13px; }
.who-txt { min-width: 0; }
.who-name { font-size: 16px; font-weight: 700; color: var(--mi-text); }
.who-meta { margin: 2px 0 0; font-size: 12px; color: var(--mi-text-dim); display: flex; align-items: center; gap: 5px; }
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.dot.on { background: var(--mi-success); }
.dot.off { background: var(--mi-text-dim); }
.ops { display: flex; gap: 4px; flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }
.chan { margin: 0; font-size: 12px; color: var(--mi-joy); }
.blk { margin-top: 2px; }
.blocked { border: 1px solid var(--mi-border); border-radius: 12px; padding: 14px 16px; background: var(--mi-card); }
.blocked-t { margin: 0 0 6px; font-size: 14px; font-weight: 700; color: var(--mi-text); }
.blocked-d { margin: 0 0 10px; font-size: 12px; line-height: 1.9; color: var(--mi-text-dim); }
.thread { max-height: 56vh; min-height: 260px; overflow-y: auto; padding: 14px 16px; display: flex; flex-direction: column; gap: 6px; }
.older { display: flex; justify-content: center; align-items: center; min-height: 32px; }
.group { display: flex; flex-direction: column; gap: 6px; }
.day { display: flex; justify-content: center; margin: 8px 0 2px; }
.day span { font-size: 11px; color: var(--mi-text-dim); background: var(--mi-fill); border-radius: 999px; padding: 2px 10px; }
.row { display: flex; }
.row.mine { justify-content: flex-end; }
.row.peer { justify-content: flex-start; }
.bubble { max-width: 72%; border-radius: 14px; padding: 8px 12px; background: var(--mi-bubble); color: var(--mi-text); word-break: break-word; }
.mine .bubble { background: var(--mi-bubble-me); border: 1px solid var(--mi-primary-line); }
.bubble-txt { margin: 0; font-size: 14px; line-height: 1.75; white-space: pre-wrap; }
.bubble-img { width: 180px; height: 180px; border-radius: 10px; display: block; }
.bubble-meta { display: flex; gap: 8px; justify-content: flex-end; margin-top: 4px; font-size: 11px; color: var(--mi-text-dim); }
.is-pending .bubble-meta { opacity: 0.7; }
.is-failed { border-color: var(--mi-anger) !important; }
.fail { margin: 4px 0 0; font-size: 12px; color: var(--mi-anger); }
.alert-box { width: 100%; border: 1px solid var(--mi-primary-line); background: var(--mi-primary-soft); border-radius: 12px; padding: 8px 12px; }
.alert-t { margin: 0; font-size: 13px; font-weight: 700; color: var(--mi-primary); }
.alert-d { margin: 4px 0 0; font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); }
.alert-link { display: inline-block; margin-top: 4px; font-size: 12px; color: var(--mi-primary); }
.tail { height: 1px; }
.composer { padding: 10px 12px 12px; }
.comp-line { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.file { display: none; }
.comp-tip { font-size: 12px; line-height: 1.6; }
.comp-ops { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 8px; }
.toolbar { margin: 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.toolbar .dim { font-size: 11px; line-height: 1.7; }
.dim { color: var(--mi-text-dim); font-size: 12px; }
.dlg-tip { margin: 0 0 10px; line-height: 1.8; }
.dlg-full { width: 100%; }
.dlg-gap { margin-top: 10px; }
</style>
