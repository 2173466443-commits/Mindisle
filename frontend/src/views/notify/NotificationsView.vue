<template>
  <div class="page nc">
    <div class="topbar">
      <div>
        <h1 class="h1">通知</h1>
        <p class="dim">
          谁赞了你、谁回复你、谁私信你、审核结果如何，都记在这里。
          <span v-if="notify.unread > 0">当前有 <b class="hot">{{ notify.unread }}</b> 条未读。</span>
          <span v-else-if="notify.loaded">当前没有未读。</span>
        </p>
      </div>
      <div class="ops">
        <el-button size="small" :loading="notify.loading" @click="reload">刷新</el-button>
        <el-button size="small" :disabled="notify.unread === 0" :loading="marking" @click="markAll">全部已读</el-button>
        <el-button size="small" type="primary" text @click="goFeed">去广场</el-button>
      </div>
    </div>

    <!-- 分类胶囊：第一格是「全部」，第二格是「未读」，其余每一格只在这批已加载条目里真的存在时才出现。
         为什么要这样筛：后端 GET /api/notifications 只有 size 与 beforeId 两个参数，没有 type，
         所以这里的分类是「在已加载的这一页里再切一刀」，不是重新向服务端要一类数据 ——
         这句话必须写在界面上，否则用户会以为点了「私信」就能看到全部私信历史。见下方「这一页还欠什么」。 -->
    <section v-if="notify.items.length" class="mi-card chips">
      <button v-for="c in chips" :key="c.key" type="button" class="chip"
              :class="{ 'is-on': kind === c.key }" @click="kind = c.key">
        <span class="chip-ico">{{ c.icon }}</span>
        <span class="chip-label">{{ c.label }}</span>
        <span v-if="c.count > 0" class="chip-count">{{ c.count }}</span>
      </button>
      <span class="dim chip-note">分类与计数只作用于已加载的 {{ notify.items.length }} 条</span>
    </section>

    <stage-notice v-if="errorCode" :code="errorCode" stage="3" api-name="GET /api/notifications" />

    <template v-else>
      <div v-loading="notify.loading && notify.items.length === 0" class="nc-list">
        <el-empty v-if="!notify.loading && !shown.length" :description="emptyText" />

        <article v-for="it in shown" :key="it.id" class="mi-card nc-item"
                 :class="{ 'is-unread': !it.read, 'is-jumpable': !!routeOf(it) }"
                 tabindex="0" @click="openItem(it)" @keyup.enter="openItem(it)">
          <span class="nc-ico">{{ notifyIcon(it.type) }}</span>
          <div class="nc-main">
            <div class="nc-line">
              <span class="nc-dot" :class="{ 'is-on': !it.read }"></span>
              <span class="nc-title">{{ it.title }}</span>
            </div>
            <p v-if="it.content" class="nc-content">{{ it.content }}</p>
            <div class="nc-meta">
              <span class="nc-tag">{{ it.typeLabel }}</span>
              <span class="nc-time" :title="fmtDateTime(it.createdAt)">{{ fromNow(it.createdAt) }}</span>
              <span v-if="it.read" class="nc-read">已读</span>
              <span v-else-if="!routeOf(it)" class="nc-noref">仅提醒，无跳转</span>
              <span v-else class="nc-go">{{ jumpText(it) }} ›</span>
            </div>
          </div>
          <button v-if="!it.read" type="button" class="nc-mark" title="标为已读" @click.stop="markOne(it)">已读</button>
        </article>
      </div>

      <div class="more">
        <el-button v-if="notify.hasMore" size="small" :loading="notify.loading" @click="loadMore">看更早的通知</el-button>
        <span v-else-if="notify.items.length" class="dim">到这里就是全部了（已加载 {{ notify.items.length }} 条）</span>
      </div>
    </template>

    <!-- 通知设置（需求 FR9.4 · 任务 T3.16）。开关的目录、顺序、哪几格置灰、置灰的理由句，
         全部来自后端 GET /api/notifications/preferences 的回执，这里一份都不抄：
         前端自己写一份「八类 + 三个不可关」的话，后端加一类通知时这一页就会少画一行而没人发现。 -->
    <section class="mi-card blk prefs">
      <div class="prefs-head">
        <h2 class="h2">通知设置</h2>
        <p class="dim">
          关掉的那一类<b>仍然会出现在上面那份列表里</b>，只是不再点亮顶栏红点、也不再实时弹出提醒 ——
          这里关的是打扰，不是记录。
        </p>
      </div>

      <div v-loading="notify.prefsLoading && notify.prefs.length === 0" class="prefs-body">
        <p v-if="!notify.prefsLoaded && !notify.prefsLoading" class="dim prefs-err">
          {{ notify.prefsError || '通知设置没读到' }}
          <button type="button" class="prefs-retry" @click="loadPrefs">重试</button>
        </p>

        <ul v-else class="pref-list">
          <li v-for="row in notify.prefs" :key="row.type" class="pref-row"
              :class="{ 'is-locked': row.locked, 'is-dirty': isDirty(row) }">
            <span class="pref-ico">{{ notifyIcon(row.type) }}</span>
            <div class="pref-text">
              <span class="pref-label">{{ row.label }}</span>
              <!-- 置灰的理由由后端逐档给（system / audit / crisis 各一句），界面只念不编。 -->
              <span v-if="row.locked" class="pref-reason">{{ row.lockReason }}</span>
              <span v-else class="pref-hint">{{ hintOf(row) }}</span>
            </div>
            <el-switch :model-value="isEnabled(row)" :disabled="row.locked || notify.prefsLoading"
                       @change="onToggle(row, $event)" />
          </li>
        </ul>
      </div>

      <div class="prefs-foot">
        <span class="dim">{{ footText }}</span>
        <el-button size="small" type="primary" :disabled="!dirtyTypes.length"
                   :loading="notify.prefsLoading" @click="save">保存改动</el-button>
      </div>
    </section>

    <section class="mi-card blk gap">
      <h2 class="h2">这一页还欠什么</h2>
      <ul class="lines">
        <li><b>分类是客户端筛选</b>：后端列表没有 type 参数，所以按类型切开后只能筛「已经加载到的这些条」，往下翻页筛到的仍是同一批。真正按类型分页要等后端补 <code>GET /api/notifications?type=</code>。</li>
        <li><b>通知设置只管提醒，不管落库</b>：关掉「赞」之后，赞仍然一条条写进 <code>notify_message</code>，只是按已读落库、不计红点、不推实时帧（Gate3 的判据「关闭类仍落库不计红点」）。所以这一页关的是打扰，不是记录；把开关全关也不会让任何人少发一条通知。</li>
        <li><b>实时提醒只有短句</b>：<code>/user/queue/notify</code> 的帧只有 <code>{type,id,unread,ts}</code> 四个键，后端刻意不带正文（需求 BR11：toast 可能出现在共享屏幕上），所以弹出来的是「有人回复了你」而不是「阿舟说：……」，正文由随后那次列表补拉带回来。没有「点 toast 直达那一条」：ElMessage 不是导航容器，要做深链得换成站内信弹层，那是另一件事。</li>
        <li><b>「已读」不区分是在这里点的还是在铃铛里点的</b>：两处共用同一个 store 与同一个未读数，所以在铃铛弹层里点过一条，回到这一页它就是灭的 —— 这是同一条数据的两种视图，不是 bug。</li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { notifyIcon, notifyRoute, NOTIFY_TYPES } from '@/api/notify'
import { useNotifyStore } from '@/stores/notify'
import { fromNow, fmtDateTime } from '@/utils/format'
import StageNotice from '@/components/StageNotice.vue'

// 需求 U13（需求分析文档 §10.1 用户端页面清单）：通知中心整页 = 分类列表 + 一键已读。
// 任务 T3.16 遗留的第 ① 号欠账：铃铛弹层（T3.11-b）只给了「最近二十条 + 就地已读」，
// 没有一处能让人安心翻旧账，也没有一处能把「按类型看」这件事做完。
//
// 这一页刻意复用 stores/notify.js，不新开一个 store：未读数只有一个真相来源
// （后端每页都带回 unreadCount），两处各存一份必然对不上，那正是这个 store 的注释里
// 一开始就拒绝的东西。副作用是翻页游标也是共享的 —— 弹层每次展开都会 reset 到最新一页，
// 所以从这一页翻了五屏再去看铃铛，列表会缩回第一屏。这是取舍，写在上面那条「还欠什么」里。
const notify = useNotifyStore()
const router = useRouter()

const kind = ref('all')
const marking = ref(false)
const errorCode = ref(null)

const ALL = 'all'
const UNREAD = 'unread'

// 胶囊顺序固定为「全部 / 未读 / 八类中的已出现者」，八类的顺序直接取 api/notify.js 的 NOTIFY_TYPES，
// 不在这里另抄一份类型表：那张表是后端 typeLabel 的镜像，抄第二份就会有两份各自漂移的一天。
const chips = computed(() => {
  const items = notify.items
  const out = [
    { key: ALL, label: '全部', icon: '🗂', count: items.length },
    { key: UNREAD, label: '未读', icon: '🔴', count: items.filter(function (x) { return !x.read }).length }
  ]
  NOTIFY_TYPES.forEach(function (t) {
    const hit = items.filter(function (x) { return x.type === t.value })
    if (hit.length) out.push({ key: t.value, label: t.label, icon: notifyIcon(t.value), count: hit.length })
  })
  return out
})

const shown = computed(() => {
  const items = notify.items
  if (kind.value === ALL) return items
  if (kind.value === UNREAD) return items.filter(function (x) { return !x.read })
  return items.filter(function (x) { return x.type === kind.value })
})

const emptyText = computed(() => {
  if (!notify.items.length) {
    return notify.error ? notify.error : '还没有通知。去广场发一条，或者先给别人的帖子点个赞。'
  }
  if (kind.value === UNREAD) return '未读的都清完了'
  return '这一类在已加载的 ' + notify.items.length + ' 条里没有'
})

function routeOf(item) {
  // 跳转表只在 api/notify.js 里有一份：帖子→详情页、用户→主页、私信→会话，
  // 其余（系统公告、AI 会话工单、举报回执）后端给的 ref 没有对应的用户侧路由，
  // notifyRoute 返回 null，这里就把它当「仅提醒」显示，不硬造一个点了没反应的空跳转。
  return notifyRoute(item)
}

function jumpText(item) {
  const to = routeOf(item)
  if (!to) return ''
  if (to.name === 'post-detail') return '看帖子'
  if (to.name === 'user-home') return '看主页'
  if (to.name === 'chat-detail') return '回私信'
  return '去看看'
}

// ---------------------------------------------------------------- 通知设置（FR9.4）

/**
 * 草稿：只存「被改过而还没保存」的格子，键是类型码。
 *
 * 为什么不用一个八格的全量副本：那会让「保存」按钮不知道该发哪几格，
 * 只能把八格全发一遍 —— 而后端的判据是「值没变就不写」，全发一遍虽然结果正确，
 * 却会让那句「有 N 个开关变了」变成假话（它数的是改动，不是提交）。
 */
/**
 * 草稿：只存「被改过而还没保存」的格子，键是类型码。
 *
 * 为什么不用一个八格的全量副本：那会让「保存」按钮不知道该发哪几格，
 * 只能把八格全发一遍 —— 而后端的判据是「值没变就不写」，全发一遍虽然结果正确，
 * 却会让那句「有 N 个开关变了」变成假话（它数的是改动，不是提交）。
 *
 * 为什么是 shallowRef 而不是 reactive({})：这是一个在浏览器里实测抓出来的坑。
 * 空对象的 reactive 上，hasOwnProperty 走 getOwnPropertyDescriptor 陷阱，而 Vue 只在
 * 「这个键已经有依赖」时才登记 HAS 订阅；再加上 && 短路，右边那个 draft[row.type] 压根没执行，
 * 于是这一格从来没被这次渲染订阅过。后果是：点开关确实改了 draft，界面却不重画 ——
 * 实测手工调用 onToggle 之后 isDirty(row) 已返回 true，li 上的 is-dirty 类仍是一动不动。
 * shallowRef 把 .value 整体换成一个新对象，订阅挂在 ref 本身：只要写过一次，就一定醒一次。
 */
const draft = shallowRef({})
const savedTip = ref('')

/** 后端回什么就显示什么：这一页不预先摆一份八个开关的表。 */
function isEnabled(row) {
  const v = draft.value[row.type]
  return v === undefined ? !!row.enabled : v
}

function isOff(row) { return !isEnabled(row) }

/**
 * 开关下面那行灰字。
 *
 * 开着的时候只说「会亮红点会弹提醒」；关掉之后如果后端带了说明句（pm、report 两格有），
 * 就念后端那句 —— 它讲的是这一类独有的取舍（「私信本身照旧送达，会话里的未读角标不受影响」），
 * 界面自己编的那句通用话盖不住这种细节。放在关掉之后才念，是因为那正是人会想知道的时刻。
 */
function hintOf(row) {
  if (!isOff(row)) return '正常接收：亮红点，也会实时提醒'
  return row.lockReason || '不会亮红点，也不会实时提醒；通知照旧出现在上面那份列表里'
}

function isDirty(row) {
  const v = draft.value[row.type]
  return v !== undefined && v !== !!row.enabled
}

const dirtyTypes = computed(function () {
  return notify.prefs.filter(isDirty).map(function (row) { return row.type })
})

const footText = computed(function () {
  if (dirtyTypes.value.length) {
    return '有 ' + dirtyTypes.value.length + ' 个开关待改动，未保存'
  }
  if (savedTip.value) return savedTip.value
  if (!notify.prefsLoaded) return ''
  return '没有待保存的改动'
})

function onToggle(row, value) {
  if (row.locked) return
  const next = Object.assign({}, draft.value)
  if (!!value === !!row.enabled) {
    // 关了又开回原值：把这一格从草稿里摘掉，别让它出现在提交里。
    if (!(row.type in next)) return
    delete next[row.type]
  } else {
    next[row.type] = !!value
  }
  draft.value = next
  savedTip.value = ''
}

/** 清空草稿：整对象换掉，别逐键 delete —— 那等于把上面那个订阅坑再埋一次。 */
function clearDraft() {
  draft.value = {}
}

function loadPrefs() {
  clearDraft()
  notify.loadPrefs()
}

async function save() {
  const toggles = notify.prefs
    .filter(isDirty)
    .map(function (row) { return { type: row.type, enabled: !!draft.value[row.type] } })
  if (!toggles.length) return
  try {
    await notify.savePrefs(toggles)
    // 用后端回执重画：开关的当前值从此以后等于库里那一行的值，而不是我点下去时以为的值。
    clearDraft()
    savedTip.value = '已保存 ' + toggles.length + ' 个开关'
  } catch (e) {
    // 失败时不清草稿：那句 400/10001 的理由（比如「审核结果不能关…」）由 http 层弹出，
    // 而用户改的那几格还留在开关上，看一眼就知道要重试还是该放弃。
    errorCode.value = e && e.code ? e.code : null
  }
}

async function reload() {
  errorCode.value = null
  try {
    await notify.load(true)
  } catch (e) {
    errorCode.value = e && e.code ? e.code : null
  }
}

async function loadMore() {
  try {
    await notify.loadMore()
  } catch (e) {
    errorCode.value = e && e.code ? e.code : null
  }
}

async function markOne(item) {
  try {
    await notify.markRead([item.id])
  } catch (e) {
    errorCode.value = e && e.code ? e.code : null
  }
}

// 点一条通知 = 一次标已读 + 一次跳转。顺序是「先发已读再跳」，和顶栏铃铛同一条口径：
// 跳转会把这一页销毁，回来后列表是重新拉的，若把已读放在跳转之后，那条请求就搭在了一个已经卸载的组件上。
// 已读失败不拦跳转 —— 读到了什么就该让人点开什么，红点多留一条不是事故。
async function openItem(item) {
  if (!item.read) await markOne(item)
  const to = routeOf(item)
  if (to) router.push(to)
}

async function markAll() {
  marking.value = true
  try {
    await notify.markAll()
    // 一键已读之后把分类切回「未读」的话会看到空列表，那是真的空，不是页面坏了。
    // 但更常见的预期是「清完未读就回到全部」，所以这里主动切一次，省得用户以为列表被清空了。
    if (kind.value === UNREAD) kind.value = ALL
  } catch (e) {
    errorCode.value = e && e.code ? e.code : null
  } finally {
    marking.value = false
  }
}

function goFeed() {
  router.push({ name: 'feed' })
}

onMounted(function () {
  // 铃铛弹层的 @show 会 reset 到第一页，所以进这一页也要显式拉一次；
  // 已经 loaded 时不重复拉，免得从顶栏点进来的一瞬间闪两下。
  if (!notify.loaded) reload()
  // 设置卡开页读一次。刻意不复用上一次的回执：偏好的真相在库里，
  // 而这个人可能在管理端或另一台设备上看清过同一格；少读一次换来的是「界面显示的可能是旧值」。
  if (!notify.prefsLoaded) loadPrefs()
})
</script>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h1 { margin: 0; font-size: 22px; }
.h2 { margin: 0 0 8px; font-size: 16px; font-weight: 700; color: var(--mi-text); }
.ops { display: flex; gap: 4px; flex-shrink: 0; }
.dim { font-size: 12px; color: var(--mi-text-dim); line-height: 1.8; }
.hot { color: var(--mi-primary); }

.chips { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 14px; padding: 12px 14px; }
.chip { display: inline-flex; align-items: center; gap: 5px; padding: 6px 14px; border-radius: 999px;
        border: 1px solid var(--mi-border); background: #fff; color: var(--mi-text);
        font-size: 13px; line-height: 1.4; cursor: pointer; transition: all .15s ease; }
.chip:hover { background: var(--mi-hover); }
.chip.is-on { background: var(--mi-primary); border-color: var(--mi-primary); color: #fff; }
.chip-ico { font-size: 13px; }
.chip-count { font-size: 11px; padding: 0 6px; border-radius: 999px; background: var(--mi-hover); color: var(--mi-text-dim); }
.chip.is-on .chip-count { background: rgba(255, 255, 255, .28); color: #fff; }
.chip-note { margin-left: auto; }

.nc-list { display: flex; flex-direction: column; gap: 10px; margin-top: 14px; }
.nc-item { display: flex; align-items: flex-start; gap: 12px; padding: 14px 16px; }
.nc-item.is-jumpable { cursor: pointer; }
.nc-item.is-jumpable:hover { background: var(--mi-hover); }
.nc-item.is-unread { border-left: 3px solid var(--mi-primary); }
.nc-ico { font-size: 18px; line-height: 1.4; }
.nc-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.nc-line { display: flex; align-items: center; gap: 7px; }
.nc-dot { width: 6px; height: 6px; border-radius: 50%; background: transparent; flex-shrink: 0; }
.nc-dot.is-on { background: var(--mi-primary); }
.nc-title { font-size: 14px; color: var(--mi-text); font-weight: 600; }
.nc-content { margin: 0; padding: 8px 10px; border-radius: 8px; background: var(--mi-hover);
              font-size: 13px; line-height: 1.7; color: var(--mi-text-2);
              display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.nc-meta { display: flex; align-items: center; gap: 10px; font-size: 12px; color: var(--mi-text-dim); }
.nc-tag { padding: 1px 8px; border-radius: 999px; background: var(--mi-hover); color: var(--mi-text-dim); font-size: 11px; }
.nc-read, .nc-noref { font-size: 11px; }
.nc-go { color: var(--mi-primary); font-size: 12px; }
.nc-mark { flex-shrink: 0; align-self: center; padding: 5px 12px; border-radius: 999px;
           border: 1px solid var(--mi-border); background: #fff; color: var(--mi-text-dim);
           font-size: 12px; cursor: pointer; }
.nc-mark:hover { border-color: var(--mi-primary); color: var(--mi-primary); }
.more { display: flex; justify-content: center; align-items: center; min-height: 44px; }

.prefs { padding: 14px 16px; }
.prefs-head { display: flex; flex-direction: column; gap: 2px; }
.prefs-head .dim b { color: var(--mi-text); }
.prefs-body { margin-top: 6px; }
.prefs-err { display: flex; align-items: center; gap: 8px; padding: 10px 0; }
.prefs-retry { padding: 3px 12px; border-radius: 999px; border: 1px solid var(--mi-border);
               background: #fff; color: var(--mi-primary); font-size: 12px; cursor: pointer; }
.prefs-retry:hover { border-color: var(--mi-primary); }
.pref-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.pref-row { display: flex; align-items: center; gap: 10px; padding: 11px 0;
            border-bottom: 1px dashed var(--mi-border); }
.pref-row:last-child { border-bottom: none; }
.pref-row.is-locked { opacity: .72; }
.pref-row.is-dirty .pref-label::after { content: '·待保存'; margin-left: 6px;
                                        font-size: 11px; color: var(--mi-primary); }
.pref-ico { font-size: 16px; flex-shrink: 0; }
.pref-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.pref-label { font-size: 14px; font-weight: 600; color: var(--mi-text); }
.pref-hint { font-size: 12px; color: var(--mi-text-dim); line-height: 1.6; }
.pref-reason { font-size: 12px; line-height: 1.6; color: var(--mi-text-2); }
.prefs-foot { display: flex; align-items: center; justify-content: space-between;
              gap: 10px; margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--mi-border); }

@media (max-width: 640px) {
  .pref-row { gap: 8px; }
  .prefs-foot { flex-direction: row; }
}
.blk { margin-top: 14px; }
.gap { display: flex; flex-direction: column; }
.lines { margin: 0; padding-left: 20px; font-size: 13px; line-height: 2; color: var(--mi-text-dim); }
.lines b { color: var(--mi-text); }
.lines code { font-size: 12px; background: var(--mi-hover); padding: 1px 5px; border-radius: 4px; }

@media (max-width: 640px) {
  .topbar { flex-direction: column; }
  .ops { width: 100%; justify-content: flex-end; }
  .chip-note { display: none; }
  .nc-item { padding: 12px; }
  .nc-mark { padding: 4px 9px; }
}
</style>