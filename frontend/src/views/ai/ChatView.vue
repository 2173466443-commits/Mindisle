<template>
  <div class="chat">
    <div class="topbar">
      <div>
        <h1 class="h1">AI 陪伴对话</h1>
        <p class="dim">
          对话风格 {{ styleText }} · 本机消息 {{ messages.length }} 条 · 流式回复与情绪标注的真实通道：POST /api/ai/chat/stream（阶段 4）
        </p>
      </div>
      <div class="ops">
        <el-button size="small" :loading="busy.hotline" @click="loadHotline">刷新求助卡片</el-button>
        <el-button size="small" text @click="clearLocal">清空本机会话</el-button>
      </div>
    </div>

    <!-- 求助卡片走真实接口 GET /api/system/hotline：数据库未建也返回 200，只是 source=fallback -->
    <section v-if="card" class="mi-card crisis" :class="'lv' + riskLevel">
      <div class="crisis-head">
        <span class="badge" :class="'b' + riskLevel">{{ riskLabel }}</span>
        <b class="phone">{{ card.hotline }}</b>
        <span class="dim">{{ card.display }}</span>
        <span v-if="card.source !== 'sys_config'" class="dim">（内置文案：未读到 sys_config.prompt.crisis_card）</span>
      </div>
      <p class="crisis-text">{{ card.text }}</p>
      <div class="crisis-ops">
        <router-link class="link" to="/help">完整求助页（免登录可访问）</router-link>
        <span v-if="riskLevel >= 2" class="warn">
          本机词表命中了较高风险表达。真实 L0–L3 分级由后端「词典 + LLM 级联」判定，这里只做即时提示：既不替你隐瞒，也不假装已经完成判定。
        </span>
      </div>
    </section>

    <section class="mi-card stream">
      <div ref="streamEl" class="stream-box">
        <p v-if="messages.length === 0" class="dim empty">
          还没有对话。屿灵的回复来自后端 DeepSeek 流式链路（阶段 4），在那之前这里只保留你自己写下的话，不会用假回复填屏。
        </p>
        <div v-for="m in messages" :key="m.id" class="row" :class="m.role">
          <div class="bubble">
            <p class="txt">{{ m.text }}<span v-if="m.streaming" class="caret">▍</span></p>
            <p class="meta">
              <span>{{ m.at }}</span>
              <EmotionPill v-if="m.emotion" :value="m.emotion" class="pill" />
              <span v-if="m.level"> · 本机词表命中 L{{ m.level }}</span>
              <span v-if="m.role === 'me'"> · 仅存本机</span>
              <span v-if="m.streaming"> · 正在生成</span>
            </p>
          </div>
        </div>
      </div>
    </section>

    <StageNotice :code="codes.send" stage="4" api-name="POST /api/ai/chat/stream" />
    <p v-if="streamError" class="warn">{{ streamError }}</p>

    <section class="mi-card composer">
      <el-input
        v-model="input"
        type="textarea"
        :rows="3"
        maxlength="1000"
        show-word-limit
        resize="none"
        placeholder="写下你此刻的想法，例如：今晚又两点才睡，脑子里一直在转今天发生的事。"
        @keydown.ctrl.enter.prevent="send"
      />
      <div class="quick">
        <button v-for="q in QUICK" :key="q" type="button" class="chip" @click="input = q">{{ q }}</button>
      </div>
      <div class="send-row">
        <span class="dim">Ctrl + Enter 发送 · 单次最长 1000 字 · 阶段 4 起接入每日 20 次配额、上下文压缩与危机拦截</span>
        <div class="btns">
          <el-button v-if="busy.send" size="small" @click="stop">停止生成</el-button>
          <el-button type="primary" :loading="busy.send" :disabled="!input.trim()" @click="send">发送</el-button>
        </div>
      </div>
    </section>

    <p class="disclaimer">
      心屿提供的是情绪陪伴与倾诉，<b>不构成医疗诊断或治疗建议</b>；出现危机念头请拨打 <b>{{ card ? card.hotline : '12356' }}</b> 或前往就近医院急诊。
    </p>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { me } from '@/api/user'
import { hotline } from '@/api/system'
import { CODE } from '@/api/errorCode'
import StageNotice from '@/components/StageNotice.vue'
import EmotionPill from '@/components/EmotionPill.vue'

// 接口口径取自需求 §9.2 与手册 T4.5/T4.17，不自己发明路径：
//   POST /api/ai/chat/stream   SSE：meta -> delta* -> done | error
//   GET/POST/DELETE /api/ai/conversations   会话侧栏（阶段 4，本页暂不做多会话）
const CHAT_STREAM_API = '/api/ai/chat/stream'
// 会话内容只落在浏览器 localStorage：后端还没有会话接口，不谎称「已同步云端」。
const STORE_KEY = 'mindisle_chat_v1'

// 本机词表只承担「即时提示」这一件事，它不是判定器。
// 真实分级在阶段 4 由后端词典 + LLM 级联完成（词典置信度 < 0.55 或命中风险词才送模型）。
// 判定阈值存在 sys_config 的 risk.* 里，而 SystemController 的公开白名单刻意不含 risk.*
// （手册 §12 规范 2：不把阈值暴露给公网，等于告诉绕过者该把话说得多轻），所以这里不复制阈值。
const L3_WORDS = ['想死', '不想活', '自杀', '结束生命', '一了百了', '活着没意思', '跳楼', '割腕', '吞药', '伤害自己', '自我了断']
const L2_WORDS = ['自残', '割伤', '撑不下去', '死了算了', '彻底崩溃', '整夜睡不着', '吃不下饭', '想消失', '没有人在乎我']
const L1_WORDS = ['焦虑', '压力大', '难过', '情绪很低', '失眠', '崩溃', '孤独', '被孤立', '考砸', '吵架']

const QUICK = [
  '今天很难熬，我想先说说发生了什么',
  '我睡不着，能不能陪我慢慢聊几分钟',
  '和朋友闹翻了，我一直想不明白为什么',
  '帮我把现在的情绪拆成「事实—想法—感受」三层'
]

const messages = ref([])
const input = ref('')
const streamEl = ref(null)
const brief = ref({})
const card = ref(null)
const riskLevel = ref(0)
const streamError = ref('')
const busy = reactive({ send: false, hotline: false })
const codes = reactive({ send: null })
const conversationId = ref('')
const localSession = ref('local-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8))
let abortRef = null

const STYLE_TEXT = { gentle: '温和陪伴', direct: '直接坦率', humor: '轻松幽默', listener: '安静倾听' }
const styleText = computed(() => STYLE_TEXT[brief.value.aiStyle] || brief.value.aiStyle || '温和陪伴')
const riskLabel = computed(() => {
  if (riskLevel.value >= 3) return 'L3 建议立即求助'
  if (riskLevel.value === 2) return 'L2 需要专业支持'
  if (riskLevel.value === 1) return 'L1 低落·可倾诉'
  return 'L0 日常陪伴'
})

// 只取最高一档：命中 L3 之后不会被更弱的词「降级」。
function detectRisk(text) {
  if (!text) return 0
  for (const w of L3_WORDS) if (text.includes(w)) return 3
  for (const w of L2_WORDS) if (text.includes(w)) return 2
  for (const w of L1_WORDS) if (text.includes(w)) return 1
  return 0
}

function nowText() {
  const d = new Date()
  const pad = (n) => (n < 10 ? '0' + n : String(n))
  return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}

function authHeaders() {
  const t = localStorage.getItem('mindisle_token')
  return t ? { Authorization: 'Bearer ' + t } : {}
}

function pushMsg(role, text, level) {
  messages.value.push({
    id: messages.value.length + 1 + '-' + Date.now(),
    role,
    text,
    level: level || 0,
    emotion: '',
    streaming: false,
    at: nowText()
  })
  if (level && level > riskLevel.value) riskLevel.value = level
  // 必须回读数组代理里的对象：直接改原始对象不会触发视图更新。
  const proxy = messages.value[messages.value.length - 1]
  scrollToEnd()
  persist()
  return proxy
}

function dropIfEmpty(msg) {
  if (msg && !msg.text) {
    const i = messages.value.indexOf(msg)
    if (i >= 0) messages.value.splice(i, 1)
  }
}

function scrollToEnd() {
  nextTick(() => {
    const el = streamEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function persist() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify({
      localSession: localSession.value,
      conversationId: conversationId.value,
      riskLevel: riskLevel.value,
      messages: messages.value.map((m) => ({
        id: m.id, role: m.role, text: m.text, level: m.level, emotion: m.emotion, at: m.at
      })).slice(-50)
    }))
  } catch (e) {
    /* 隐私模式或配额超限：本机留痕失败不影响页面继续可用 */
  }
}

function restore() {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (!raw) return
    const data = JSON.parse(raw)
    if (Array.isArray(data.messages)) messages.value = data.messages
    if (data.localSession) localSession.value = data.localSession
    conversationId.value = data.conversationId || ''
    riskLevel.value = Number(data.riskLevel) || 0
  } catch (e) {
    messages.value = []
  }
}

function clearLocal() {
  messages.value = []
  riskLevel.value = 0
  conversationId.value = ''
  streamError.value = ''
  try {
    localStorage.removeItem(STORE_KEY)
  } catch (e) {
    /* ignore */
  }
  ElMessage.info('已清空本机会话记录（服务端会话删除接口在阶段 4 与侧栏一起做）')
}

// 求助卡片：后端有兜底常量，数据库未建也返回 200；这里再补一层纯前端兜底，保证入口永远不空。
async function loadHotline() {
  busy.hotline = true
  try {
    const data = await hotline()
    card.value = {
      hotline: (data && data.hotline) || '12356',
      text: (data && data.text) || '如果出现伤害自己的念头，请立即拨打心理援助热线，或前往就近医院急诊。',
      display: (data && data.display) || 'L2/L3 置顶卡片',
      source: (data && data.source) || 'local'
    }
  } catch (e) {
    card.value = {
      hotline: '12356',
      text: '如果出现伤害自己的念头，请立即拨打心理援助热线，或前往就近医院急诊。',
      display: 'L2/L3 置顶卡片',
      source: 'local'
    }
  } finally {
    busy.hotline = false
  }
}

async function loadBrief() {
  try {
    brief.value = (await me()) || {}
  } catch (e) {
    /* 拿不到风格就用默认温和陪伴，不挡主流程 */
  }
}

// SSE 帧解析：事件以空行分隔，字段行 event:/data:（手册 §5.8 第 4 条禁止用 EventSource，它带不了 JWT 头）
function parseSseEvent(raw) {
  let event = 'message'
  const dataLines = []
  raw.split('\n').forEach((line) => {
    if (line.indexOf('event:') === 0) event = line.slice(6).trim()
    else if (line.indexOf('data:') === 0) dataLines.push(line.slice(5).trim())
  })
  const text = dataLines.join('\n')
  let json = null
  if (text) {
    try {
      json = JSON.parse(text)
    } catch (e) {
      json = null
    }
  }
  return { event, text, json }
}

function handleSseEvent(raw, msg) {
  const ev = parseSseEvent(raw)
  if (ev.event === 'meta') {
    const j = ev.json || {}
    if (j.conversationId !== undefined && j.conversationId !== null) conversationId.value = String(j.conversationId)
    if (j.emotion) msg.emotion = String(j.emotion)
    const lv = Number(j.riskLevel) || 0
    if (lv > riskLevel.value) riskLevel.value = lv
    return
  }
  if (ev.event === 'delta') {
    const j = ev.json
    msg.text += j && j.content !== undefined ? String(j.content) : ev.text
    scrollToEnd()
    return
  }
  if (ev.event === 'done') {
    const j = ev.json || {}
    if (j.emotion) msg.emotion = String(j.emotion)
    if (j.degraded) streamError.value = '本次回复来自离线兜底话术库（模型不可用）。'
    return
  }
  if (ev.event === 'error') {
    const j = ev.json || {}
    streamError.value = j.msg || ev.text || '生成中断，本次回复未计入配额。'
    codes.send = j.code === undefined ? CODE.AI_UNAVAILABLE : Number(j.code)
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || busy.send) return
  const level = detectRisk(text)
  pushMsg('me', text, level)
  input.value = ''
  busy.send = true
  codes.send = null
  streamError.value = ''
  const msg = pushMsg('ai', '', 0)
  msg.streaming = true
  const ctrl = new AbortController()
  abortRef = ctrl
  try {
    const res = await fetch(CHAT_STREAM_API, {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }, authHeaders()),
      body: JSON.stringify({
        conversationId: conversationId.value || localSession.value,
        message: text,
        style: brief.value.aiStyle || 'gentle'
      }),
      signal: ctrl.signal
    })
    const ctype = res.headers.get('content-type') || ''
    if (!res.ok || ctype.indexOf('text/event-stream') < 0) {
      // 阶段 2 的 /v3/api-docs 里没有任何 /api/ai/**：这条请求会真实拿到 90006，
      // 或者在未登录态拿到 401。两种都按统一协议读出 code 交给页面提示，不伪造回复。
      const body = await res.json().catch(() => null)
      const err = new Error(body && body.msg ? body.msg : 'HTTP ' + res.status)
      if (body && body.code !== undefined) err.code = Number(body.code)
      else if (res.status === 401) err.code = CODE.UNAUTHORIZED
      throw err
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''
    for (;;) {
      const chunk = await reader.read()
      if (chunk.done) break
      buf += decoder.decode(chunk.value, { stream: true })
      let idx = buf.indexOf('\n\n')
      while (idx >= 0) {
        const rawEvent = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        handleSseEvent(rawEvent, msg)
        idx = buf.indexOf('\n\n')
      }
    }
    if (buf.trim()) handleSseEvent(buf, msg)
    dropIfEmpty(msg)
  } catch (e) {
    codes.send = e && e.code ? e.code : 'network'
    if (e && e.name === 'AbortError') {
      dropIfEmpty(msg)
      ElMessage.info('已停止生成')
    } else if (codes.send === CODE.CRISIS_BLOCKED) {
      dropIfEmpty(msg)
      pushMsg('sys', '后端判定这段话需要人工支持，已为你置顶求助入口（50002）。', 3)
    } else if (
      codes.send === CODE.RESOURCE_NOT_FOUND ||
      codes.send === CODE.NOT_IMPLEMENTED ||
      codes.send === CODE.METHOD_NOT_ALLOWED
    ) {
      dropIfEmpty(msg)
      pushMsg('sys', '阶段 4 的流式对话还没接入：这句话只保存在你本机浏览器里，没有发给任何模型。', level)
    } else {
      dropIfEmpty(msg)
      pushMsg('sys', '发送失败：' + ((e && e.message) || '未知错误'), 0)
    }
  } finally {
    msg.streaming = false
    abortRef = null
    busy.send = false
    persist()
  }
}

function stop() {
  if (abortRef) abortRef.abort()
}

onMounted(() => {
  restore()
  loadBrief()
  loadHotline()
  scrollToEnd()
})
onUnmounted(() => {
  if (abortRef) abortRef.abort()
})
</script>

<style scoped>
.chat { max-width: 900px; margin: 0 auto; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.h1 { margin: 0; font-size: 21px; }
.dim { color: var(--mi-text-dim); font-size: 12.5px; margin: 6px 0 0; }
.ops { display: flex; gap: 8px; flex-wrap: wrap; }
.crisis { margin-bottom: 12px; border-left: 4px solid var(--mi-mist); }
.crisis.lv1 { border-left-color: #7fb3a6; }
.crisis.lv2 { border-left-color: var(--mi-anger); }
.crisis.lv3 { border-left-color: var(--mi-anger); background: #2a1c25; }
.crisis-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.badge { font-size: 12px; padding: 2px 9px; border-radius: 999px; background: #24304d; color: var(--mi-text); }
.badge.b1 { background: #1e3a35; color: #7fb3a6; }
.badge.b2 { background: #3a2226; color: #f0a3a3; }
.badge.b3 { background: #5b1f26; color: #ffd9d9; }
.phone { font-size: 17px; color: var(--mi-primary); letter-spacing: .5px; }
.crisis-text { margin: 8px 0 0; font-size: 13.5px; line-height: 1.9; }
.crisis-ops { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.link { color: var(--mi-mist); font-size: 12.5px; }
.warn { color: #f0a3a3; font-size: 12.5px; line-height: 1.8; margin: 8px 2px 0; }
.stream { padding: 0; overflow: hidden; }
.stream-box { height: 340px; overflow-y: auto; padding: 16px 18px; }
.empty { text-align: center; padding-top: 118px; line-height: 1.9; }
.row { display: flex; margin-bottom: 12px; }
.row.me { justify-content: flex-end; }
.bubble { max-width: 76%; padding: 10px 14px; border-radius: 14px; background: #1d2a45; border: 1px solid var(--mi-border); }
.row.me .bubble { background: #35241f; border-color: #5a3a30; }
.row.sys .bubble { background: transparent; border-style: dashed; }
.row.ai .bubble { background: #1c3040; border-color: #2c4a63; }
.txt { margin: 0; font-size: 14px; line-height: 1.85; white-space: pre-wrap; word-break: break-word; }
.caret { color: var(--mi-primary); }
.meta { margin: 6px 0 0; font-size: 11px; color: var(--mi-text-dim); display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.meta .pill { border-width: 1px; }
.composer { margin-top: 12px; }
.quick { display: flex; gap: 8px; flex-wrap: wrap; margin: 10px 0; }
.chip { background: #1d2a45; border: 1px solid var(--mi-border); color: var(--mi-text-dim); font-size: 12px; padding: 5px 10px; border-radius: 999px; cursor: pointer; }
.chip:hover { color: var(--mi-text); border-color: var(--mi-mist); }
.send-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.btns { display: flex; gap: 8px; }
.disclaimer { margin: 12px 2px 0; font-size: 12px; line-height: 1.9; color: var(--mi-text-dim); }
</style>
