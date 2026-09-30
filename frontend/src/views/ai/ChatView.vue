<template>
  <div class="chat">
    <!--
      U7 左栏（FR2.1 多会话）。列表的真实来源是 GET /api/ai/conversations，
      不是本机 localStorage 的镜像：换一台浏览器登录同一个账号，这里应该还是这几条。
    -->
    <aside class="side mi-card">
      <div class="side-head">
        <b>我的对话</b>
        <button type="button" class="chip new" :disabled="busy.send" @click="newChat">＋ 新建</button>
      </div>
      <p v-if="convError" class="warn">{{ convError }}</p>
      <div v-loading="busy.conv || busy.hist" class="conv-box">
        <p v-if="!conversations.length && !busy.conv" class="dim side-empty">
          还没有会话。发出的第一句话会自动建一条，标题取它开头的一小段。
        </p>
        <div
          v-for="c in conversations"
          :key="c.id"
          class="conv"
          :class="{ on: String(c.id) === activeId }"
          role="button"
          tabindex="0"
          @click="openConversation(c)"
          @keydown.enter.prevent="openConversation(c)"
        >
          <input
            v-if="renaming === String(c.id)"
            v-model="renameDraft"
            class="ct-input"
            type="text"
            :maxlength="TITLE_MAX"
            aria-label="重命名这条会话"
            @click.stop
            @keydown.enter.prevent="commitRename"
            @keydown.esc.prevent="cancelRename"
            @blur="commitRename"
          />
          <span v-else class="ct">{{ c.title || '新的对话' }}</span>
          <span class="cd dim">{{ shortTime(c.lastMsgAt || c.createdAt) }}</span>
          <span class="cs dim">{{ STYLE_TEXT[c.style] || '温暖陪伴' }}</span>
          <span
            class="rn"
            role="button"
            tabindex="0"
            title="重命名这条会话"
            @click.stop="startRename(c)"
            @keydown.enter.stop.prevent="startRename(c)"
          >✎</span>
          <span
            class="del"
            :class="{ armed: armedDelete === String(c.id) }"
            role="button"
            tabindex="0"
            :title="armedDelete === String(c.id) ? '再点一次确认删除' : '删除这条会话'"
            @click.stop="armDelete(c)"
            @keydown.enter.stop.prevent="armDelete(c)"
          >{{ armedDelete === String(c.id) ? '确认' : '×' }}</span>
        </div>
      </div>
      <p class="dim side-foot">
        会话存在服务端，最近 {{ CONVERSATION_MAX }} 条之外会自动清除最旧的；删掉的会话不再出现在这里。
      </p>
    </aside>

    <div class="main">
      <div class="topbar">
        <div>
          <h1 class="h1">AI 陪伴对话</h1>
          <p class="dim">
            对话风格 {{ styleText }} · 本条会话 {{ messages.length }} 句 · 真实通道
            POST /api/ai/chat/stream（SSE：meta → delta → done）
            <span v-if="srvRisk"> · 后端判定 L{{ srvRisk }}</span>
          </p>
        </div>
        <div class="ops">
          <el-button size="small" :loading="busy.hotline" @click="loadHotline">刷新求助卡片</el-button>
          <el-button size="small" text :disabled="!activeId" @click="removeActive">删除本会话</el-button>
        </div>
      </div>

      <!-- 求助卡片：GET /api/system/hotline 有内置兜底，数据库未建也返回 200，只是 source=fallback -->
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
          <span v-if="srvRisk >= 2" class="warn">
            后端把这一轮判定为 L{{ srvRisk }}：求助入口已置顶，站内也已登记一条需要人工关注的记录。
            这仍然是陪伴不是诊断，但请把它当成一个真实存在的出口。
          </span>
          <span v-else-if="localRisk >= 2" class="warn">
            本机词表命中了较高风险表达。真实 L0–L3 由后端「词典 + LLM 级联」判定，发出这一句之后如果后端也判成
            L2/L3，这里会换成后端口径。
          </span>
        </div>
      </section>

      <section class="mi-card stream">
        <div ref="streamEl" class="stream-box">
          <p v-if="messages.length === 0" class="dim empty">
            {{ activeId ? '这条会话里还没有消息。' : '还没有对话。写下此刻的想法，屿屿会一个字一个字地回你。' }}
          </p>
          <div v-for="m in messages" :key="m.id" class="row" :class="m.role">
            <div class="bubble">
              <p v-if="m.role === 'ai'" class="txt md" v-html="mdHtml(m)"></p>
              <p v-else class="txt">{{ m.text }}<span v-if="m.streaming" class="caret">▍</span></p>
              <p class="meta">
                <span>{{ m.at }}</span>
                <EmotionPill v-if="m.emotion" :value="m.emotion" class="pill" />
                <span v-if="m.channel">{{ m.channel === 'llm' ? '· 模型复判' : '· 词典初判' }}</span>
                <span v-if="m.srvLevel"> · 屿屿判定 L{{ m.srvLevel }}</span>
                <span v-else-if="m.level && m.role === 'me' && !m.judged"> · 本机词表提示 L{{ m.level }}</span>
                <span v-if="m.role === 'me' && !m.persisted"> · 未发送成功，没有落库</span>
                <span v-if="m.degraded" class="off"> · 离线模式</span>
                <span v-if="m.rewritten" class="off"> · 安全改写</span>
                <span v-if="m.interrupted" class="off"> · 生成中断</span>
                <span v-if="m.firstTokenMs"> · 首字 {{ m.firstTokenMs }}ms／全篇 {{ m.latencyMs }}ms</span>
                <span v-if="m.streaming"> · 正在生成</span>
              </p>
              <p v-if="m.degradeReason" class="dim reason">{{ m.degradeReason }}</p>
              <p v-if="m.dropped" class="retry">
                <button type="button" :disabled="busy.send" @click="retryLast(m)">重新生成这一句</button>
                <span class="dim">连接在半句上断了，这一句没有落库；重试会当成新的一轮重新发送。</span>
              </p>
              <p v-if="m.role === 'ai' && m.mid" class="fb">
                <span class="dim">这句帮到你了吗</span>
                <button type="button" :class="{ on: m.feedback === 'UP' }" @click="mark(m, 'UP')">有用</button>
                <button type="button" :class="{ on: m.feedback === 'DOWN' }" @click="mark(m, 'DOWN')">
                  没被理解
                </button>
              </p>
            </div>
          </div>
        </div>
      </section>

      <StageNotice :code="codes.send" stage="4" api-name="POST /api/ai/chat/stream" :extra="noticeExtra" />
      <p v-if="streamError" class="warn">{{ streamError }}</p>
      <p v-if="needConsent" class="warn">
        这一句一个字都没进模型，也没落库。
        <el-button size="small" type="primary" link @click="goConsent">去「我的 · 隐私与同意」授权</el-button>
      </p>

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
          <button v-for="q in QUICK" :key="q" type="button" class="chip" :disabled="busy.send" @click="input = q">
            {{ q }}
          </button>
        </div>
        <div class="send-row">
          <span class="dim">
            Ctrl + Enter 发送 · 单次最长 1000 字 · 后端依次做敏感词清洗、情绪识别、L0–L3 风险判定与用量预算
          </span>
          <div class="btns">
            <el-button v-if="busy.send" size="small" @click="stop">停止生成</el-button>
            <el-button type="primary" :loading="busy.send" :disabled="!input.trim()" @click="send">发送</el-button>
          </div>
        </div>
      </section>

      <p class="disclaimer">
        心屿提供的是情绪陪伴与倾诉，<b>不构成医疗诊断或治疗建议</b>；出现危机念头请拨打
        <b>{{ card ? card.hotline : '12356' }}</b> 或前往就近医院急诊。
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { me } from '@/api/user'
import { hotline } from '@/api/system'
import { CODE } from '@/api/errorCode'
import {
  CONVERSATION_MAX,
  MESSAGE_PAGE_MAX,
  STYLE_TEXT,
  TITLE_MAX,
  deleteConversation,
  listConversations,
  listMessages,
  renameConversation,
  sendFeedback
} from '@/api/ai'
import StageNotice from '@/components/StageNotice.vue'
import EmotionPill from '@/components/EmotionPill.vue'
import { renderChatHtml } from '@/utils/markdown'

// 接口口径取自需求 §9.1/§9.2 与手册 T4.2/T4.5/T4.17/T4.19，不自己发明路径：
//   POST   /api/ai/chat/stream                    SSE：meta -> delta* -> done | error
//   GET    /api/ai/conversations                  U7 左栏（FR2.1）
//   GET    /api/ai/conversations/{id}/messages    点开旧会话的回看
//   DELETE /api/ai/conversations/{id}             删除一条会话
//   POST   /api/ai/messages/{id}/feedback         赞踩（FR2.7）
// 只有流式那条留在这里用 fetch 发：它是 text/event-stream，走 axios 会等整段响应才 resolve，
// 等于把逐字上屏降级成「憋十几秒然后一整段」；而 EventSource 又带不了 JWT 头（手册 §5.8 第 4 条）。
const CHAT_STREAM_API = '/api/ai/chat/stream'

// 本机只记「上次停在哪个会话」。消息正文的真相在服务端 chat_message 表，不再往 localStorage 抄一份。
const ACTIVE_KEY = 'mindisle_chat_active'

// SSE 终止帧标记：done 或 error 任一到达即 true，用来识别「读流结束了但一帧终止都没收到」的安静断流。
let streamTerminal = false

// 会话还没建出来时占位的本机号。后端 openConversation 的规则是「前端传的不是纯数字就当新开会话」，
// 所以这个号不会被拿去查库，也就不会出现「拿本机号猜服务端 id」那条越权路径。
const localSession = ref('local-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8))

// 本机词表只承担「发送前即时提示」这一件事，它不是判定器：
// 真实分级在 ChatService 第 ④ 步由词典 + LLM 级联完成，结果走 SSE meta.riskLevel 回来。
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

// chat_message.risk_level 是 'L0'..'L3' 字符串（MessageView 原样透出），界面要的是档位数字。
const RISK_NUM = { L0: 0, L1: 1, L2: 2, L3: 3 }

const router = useRouter()
const messages = ref([])
const conversations = ref([])
const activeId = ref('')
const input = ref('')
const streamEl = ref(null)
const brief = ref({})
const card = ref(null)
const localRisk = ref(0)
const srvRisk = ref(0)
const streamError = ref('')
const convError = ref('')
const armedDelete = ref('')
// 行内重命名：renaming 存的是「正在改的那条会话 id」，空串 = 没有输入框在编辑态
const renaming = ref('')
const renameDraft = ref('')
const busy = reactive({ send: false, hotline: false, conv: false, hist: false })
const codes = reactive({ send: null })
// conversationId 必须是字符串：后端 ChatSendRequest 的第一字段就是 String，纯数字与否由服务端判断。
const conversationId = ref('')
let abortRef = null
let armTimer = null
let armedActive = false

const riskLevel = computed(() => Math.max(localRisk.value, srvRisk.value))
const needConsent = computed(() => Number(codes.send) === CODE.SENSITIVE_CONSENT_REQUIRED)
const STYLE_LOCAL = { gentle: '温和陪伴', direct: '直接坦率', humor: '轻松幽默', listener: '安静倾听' }
const styleText = computed(() => STYLE_LOCAL[brief.value.aiStyle] || brief.value.aiStyle || '温和陪伴')
const riskLabel = computed(() => {
  if (riskLevel.value >= 3) return 'L3 建议立即求助'
  if (riskLevel.value === 2) return 'L2 需要专业支持'
  if (riskLevel.value === 1) return 'L1 低落·可倾诉'
  return 'L0 日常陪伴'
})

// 90001/40001 这类码的兜底文案在 errorCode.js 的总表里，但那一句话不足以说明「你现在能做什么」。
const noticeExtra = computed(() => {
  const c = Number(codes.send)
  if (c === CODE.AI_BUDGET_EXCEEDED) {
    return '今天的对话字数预算用完了（NFR6）。这不影响你继续逛社区，明天再来找屿屿说话。'
  }
  if (c === CODE.RATE_LIMITED) {
    return '这一分钟里发得太多了（NFR7 限的是模型调用，6 次/分）。等十几秒再把这句话发一次。'
  }
  if (c === CODE.SENSITIVE_CONSENT_REQUIRED) {
    return 'AI 陪伴处理的是情绪与健康相关信息，按 NFR8 需要你单独授权。没授权时一个字都不会进模型，也不会落库。'
  }
  if (c === CODE.AI_UNAVAILABLE) {
    return '模型连续失败后被暂时断开（熔断），这段时间的回复来自离线陪伴话术库，界面上会标「离线模式」。'
  }
  return ''
})

// 只取最高一档：命中 L3 之后不会被更弱的词「降级」。
function detectRisk(text) {
  if (!text) return 0
  for (const w of L3_WORDS) if (text.includes(w)) return 3
  for (const w of L2_WORDS) if (text.includes(w)) return 2
  for (const w of L1_WORDS) if (text.includes(w)) return 1
  return 0
}

function pad2(n) {
  return n < 10 ? '0' + n : String(n)
}

function nowText() {
  const d = new Date()
  return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes())
}

// 后端时间一律 'yyyy-MM-dd HH:mm:ss'（ConversationService.TS），这里只截成 MM-DD HH:mm，不做时区换算。
function shortTime(value) {
  const s = String(value || '')
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/)
  if (!m) return s
  const thisYear = new Date().getFullYear()
  const head = Number(m[1]) === thisYear ? '' : m[1] + '-'
  return head + m[2] + '-' + m[3] + ' ' + m[4] + ':' + m[5]
}

function authHeaders() {
  const t = localStorage.getItem('mindisle_token')
  return t ? { Authorization: 'Bearer ' + t } : {}
}

function pushMsg(role, text, level, extra) {
  messages.value.push(
    Object.assign(
      {
        id: messages.value.length + 1 + '-' + Date.now(),
        role,
        text,
        level: level || 0,
        srvLevel: 0,
        emotion: '',
        channel: '',
        mid: null,
        umid: null,
        feedback: 'NONE',
        degraded: false,
        degradeReason: '',
        rewritten: false,
        interrupted: false,
        firstTokenMs: 0,
        latencyMs: 0,
        persisted: false,
        judged: false,
        streaming: false,
        at: nowText()
      },
      extra || {}
    )
  )
  if (role === 'me' && level && level > localRisk.value) localRisk.value = level
  scrollToEnd()
  // 必须回读数组代理里的对象：直接改原始对象不会触发视图更新。
  return messages.value[messages.value.length - 1]
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

function writeActive(id) {
  try {
    if (id) localStorage.setItem(ACTIVE_KEY, String(id))
    else localStorage.removeItem(ACTIVE_KEY)
  } catch (e) {
    /* 隐私模式写不进去：下次进来落在最新一条会话，不影响任何功能 */
  }
}

function readActive() {
  try {
    return localStorage.getItem(ACTIVE_KEY) || ''
  } catch (e) {
    return ''
  }
}

// ============================================================ U7 左栏：会话的读与删（FR2.1）

async function loadConversations() {
  busy.conv = true
  convError.value = ''
  try {
    const rows = await listConversations({ limit: CONVERSATION_MAX })
    conversations.value = Array.isArray(rows) ? rows : []
  } catch (e) {
    conversations.value = []
    convError.value = '会话列表暂时读不到：' + ((e && e.message) || '未知错误')
  } finally {
    busy.conv = false
  }
}

/** 服务端 MessageView -> 界面消息。回看和实时流共用一套字段口径，气泡才不会两种长相。 */
function mapServerMessage(row) {
  const role = row.role === 'assistant' ? 'ai' : row.role === 'user' ? 'me' : 'sys'
  return {
    id: 'srv-' + row.id,
    role,
    text: row.content || '',
    level: 0,
    srvLevel: RISK_NUM[row.riskLevel] || 0,
    emotion: row.emotionLabel || '',
    channel: row.emotionChannel || '',
    mid: role === 'ai' ? Number(row.id) : null,
    umid: role === 'me' ? Number(row.id) : null,
    feedback: row.feedback || 'NONE',
    degraded: !!row.degraded,
    degradeReason: '',
    rewritten: false,
    interrupted: !!row.interrupted,
    firstTokenMs: 0,
    latencyMs: 0,
    persisted: true,
    streaming: false,
    at: shortTime(row.createdAt)
  }
}

async function openConversation(c) {
  if (busy.send) {
    ElMessage.info('等这条回复生成完再切换会话')
    return
  }
  const id = String(c.id)
  activeId.value = id
  conversationId.value = id
  writeActive(id)
  armedDelete.value = ''
  localRisk.value = 0
  srvRisk.value = 0
  streamError.value = ''
  codes.send = null
  busy.hist = true
  try {
    const rows = await listMessages(id, { limit: MESSAGE_PAGE_MAX })
    messages.value = (Array.isArray(rows) ? rows : []).map(mapServerMessage)
    // 回看一条旧会话时把风险档也带回来：L2/L3 的那几轮重新打开还应该看得见求助卡片。
    let top = 0
    messages.value.forEach(function (m) {
      if (m.srvLevel > top) top = m.srvLevel
    })
    srvRisk.value = top
  } catch (e) {
    messages.value = []
    if (Number(e && e.code) === CODE.RESOURCE_NOT_FOUND) {
      ElMessage.info('这条会话已经不在了，已从列表里去掉')
      conversations.value = conversations.value.filter(function (x) {
        return String(x.id) !== id
      })
      newChat()
    } else {
      streamError.value = '这条会话的消息暂时读不到：' + ((e && e.message) || '未知错误')
    }
  } finally {
    busy.hist = false
    scrollToEnd()
  }
}

// 「新建」刻意不发 POST /api/ai/conversations：先建一条空会话，侧栏就会永远多一行没有内容的「新的对话」。
// 真正建会话的时机是发出第一句话，由后端 openConversation 用首句前若干字当标题（FR2.1）。
function newChat() {
  if (busy.send) {
    ElMessage.info('等这条回复生成完再开新对话')
    return
  }
  activeId.value = ''
  conversationId.value = ''
  messages.value = []
  localRisk.value = 0
  srvRisk.value = 0
  streamError.value = ''
  convError.value = ''
  armedDelete.value = ''
  codes.send = null
  writeActive('')
}

/** 两段式删除：第一下只把按钮变成「确认」，四秒内不点就自动收回。项目里没引 ElMessageBox，不为一个确认框多拉一套依赖。 */
function armDelete(c) {
  const id = String(c.id)
  if (armedDelete.value === id) {
    if (armTimer) clearTimeout(armTimer)
    armTimer = null
    armedDelete.value = ''
    removeConversation(id)
    return
  }
  armedDelete.value = id
  if (armTimer) clearTimeout(armTimer)
  armTimer = setTimeout(function () {
    armedDelete.value = ''
  }, 4000)
}

/**
 * 开始重命名（FR2.1）。用行内 input 而不是弹框：本项目刻意没引 ElMessageBox
 * （理由就在上面 armDelete 的注释里），为一个 30 字的标题再拉一套弹层依赖不值当；
 * 行内改名还顺手省掉「先选中哪一条」这一步 —— 输入框就长在它自己的那一行里。
 */
function startRename(c) {
  if (busy.send) {
    ElMessage.info('等这条回复生成完再改标题')
    return
  }
  renaming.value = String(c.id)
  renameDraft.value = c.title || ''
  nextTick(function () {
    const el = document.querySelector('.conv-box .ct-input')
    if (el) {
      el.focus()
      el.select()
    }
  })
}

function cancelRename() {
  renaming.value = ''
  renameDraft.value = ''
}

/**
 * 提交重命名。Enter、失焦、点别处都会走到这里，所以第一件事是把自己从编辑态摘掉
 * （renaming 一清空，后面再来的 blur 就自然 return），否则一次回车会发两遍 PATCH。
 * 空串与「和原标题一样」都不发请求：前者是用户还没想好，后者是纯粹的无效写。
 */
async function commitRename() {
  const id = renaming.value
  if (!id) return
  const draft = (renameDraft.value || '').trim()
  renaming.value = ''
  renameDraft.value = ''
  const row = conversations.value.find(function (c) {
    return String(c.id) === id
  })
  if (!row) return
  if (!draft) {
    ElMessage.warning('标题不能是空的，先留着原来那个')
    return
  }
  if (draft === (row.title || '')) return
  try {
    const view = await renameConversation(id, draft)
    row.title = view && view.title ? view.title : draft
    ElMessage.success('这条会话现在叫「' + row.title + '」')
  } catch (e) {
    ElMessage.error('改标题失败：' + ((e && e.message) || '未知错误'))
    if (Number(e && e.code) === CODE.RESOURCE_NOT_FOUND) loadConversations()
  }
}

function removeActive() {
  const id = activeId.value
  if (!id) return
  if (!armedActive) {
    armedActive = true
    ElMessage.warning('再点一次「删除本会话」就会删掉它')
    setTimeout(function () {
      armedActive = false
    }, 6000)
    return
  }
  armedActive = false
  removeConversation(id)
}

async function removeConversation(id) {
  if (busy.send) {
    ElMessage.info('等这条回复生成完再删除')
    return
  }
  try {
    await deleteConversation(id)
    ElMessage.success('已删除：这条会话不再出现在你的列表里（正文由服务端的清除任务处理）')
    conversations.value = conversations.value.filter(function (c) {
      return String(c.id) !== id
    })
    if (activeId.value === id) newChat()
    else if (!conversations.value.length) loadConversations()
  } catch (e) {
    ElMessage.error('删除失败：' + ((e && e.message) || '未知错误'))
    if (Number(e && e.code) === CODE.RESOURCE_NOT_FOUND) loadConversations()
  }
}

/** 赞踩（FR2.7）。同一个值再点一次等于取消，落到后端是 NONE，不发多余的 UPDATE。 */
async function mark(m, value) {
  if (!m.mid) {
    ElMessage.info('这条回复没有存到服务端，暂时打不了分')
    return
  }
  const next = m.feedback === value ? 'NONE' : value
  try {
    await sendFeedback(m.mid, next)
    m.feedback = next
    if (next === 'UP') ElMessage.success('收到了，这条会进对话质量评估集（FR2.7 / T4.19）')
    else if (next === 'DOWN') ElMessage.success('收到了，这句会被记成需要改进的样本')
  } catch (e) {
    ElMessage.error('反馈没发出去：' + ((e && e.message) || '未知错误'))
    if (Number(e && e.code) === CODE.RESOURCE_NOT_FOUND) m.mid = null
  }
}

function goConsent() {
  router.push({ name: 'me' })
}

// ============================================================ SSE 流式对话（FR2.2 / T4.5）

// 求助卡片走的是 GET /api/system/hotline；done 帧里也带 hotline（同一个配置项），
// 拿到就顺手对齐一次，避免出现「卡片上 12356、正文里另一个号」这种在最要紧场合会害人的不一致。
function alignHotline(value) {
  const s = value ? String(value) : ''
  if (!s || !card.value || card.value.hotline === s) return
  card.value = Object.assign({}, card.value, { hotline: s })
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

// AI 气泡走白名单渲染器（T4.17）：模型输出是不可信输入，正文里的尖括号一律进不了 DOM。
function mdHtml(m) {
  return renderChatHtml(m.text, m.streaming)
}

// 断流重试：只撤掉这条没写完的助手气泡，上一条用户气泡保留（它确实已经落库，撤掉就等于界面和库不一致）。
function retryLast(m) {
  const text = m.retryText || ''
  if (!text || busy.send) return
  const i = messages.value.indexOf(m)
  if (i >= 0) messages.value.splice(i, 1)
  input.value = text
  send()
}

/**
 * 四帧的字段口径逐条对齐后端（ChatService.MetaPayload / DonePayload 与 AiController 的两个 frame record）。
 *
 * <p>读法全部是「键存在才用」：application.yml 的 jackson.default-property-inclusion 是 non_null，
 * null 字段会整个从 JSON 里消失，写成 j.degradeReason || '默认' 看着安全，真正的坑是
 * 「这个键压根没来」与「它来了但是 null」——两者都该走默认值，而 {@code in} 判断会漏。
 * 反过来说，任何「后端没发这个键」的猜测都不该出现在这里。</p>
 */
function handleSseEvent(raw, msg, mine) {
  const ev = parseSseEvent(raw)
  if (ev.event === 'meta') {
    const j = ev.json || {}
    if (j.conversationId !== undefined && j.conversationId !== null) conversationId.value = String(j.conversationId)
    if (j.userMessageId !== undefined && j.userMessageId !== null && mine) {
      mine.umid = Number(j.userMessageId)
      mine.persisted = true
    }
    // 情绪判的是`用户那一句`，标签因此挂在 me 那条气泡下方。依据两条硬事实：
    // ① chat_message.emotion_label 只写在 user 行上（助手行是 null，见 ai/dto/MessageView 注释），
    //    回看旧会话走 mapServerMessage 时标签天然落在 me 行；
    // ② 实时流如果挂在 ai 行，同一条消息就会出现「当场看和刷新后再看，标签在不同气泡上」的错位。
    // 服务端判过这一轮之后（meta 到了），本机词表的预估就退成背景信息，不再顶在前面上（见 judged 与模板 v-else-if）。
    if (mine) {
      mine.judged = true
      if (j.emotion) {
        mine.emotion = String(j.emotion)
        // 此刻手上确实只有词典通道的结论：T4.8 的模型复判是异步补标，完成后不回改本轮推送，
        // 所以「词典初判」四个字在实时流里是准的；刷新之后才可能变成「模型复判」。
        mine.channel = 'dict'
      }
    } else if (j.emotion) {
      msg.emotion = String(j.emotion)
      msg.channel = 'dict'
    }
    // meta.riskLevel 是 0-3 的数字（后端 MetaPayload 特意不用 'L2' 字符串：Number('L2') 是 NaN，
    // 兜成 0 就等于危机那一轮的求助卡片在唯一需要它的时刻不亮）。
    const lv = Number(j.riskLevel)
    if (Number.isFinite(lv)) {
      msg.srvLevel = lv
      if (lv > srvRisk.value) srvRisk.value = lv
    }
    return
  }
  if (ev.event === 'delta') {
    const j = ev.json
    msg.text += j && j.content !== undefined ? String(j.content) : ev.text
    scrollToEnd()
    return
  }
  if (ev.event === 'done') {
    streamTerminal = true
    const j = ev.json || {}
    msg.persisted = true
    // 这两个 id 是赞踩与「刷新后还在」的全部依据：messageId 给助手这条，userMessageId 在 meta 里给上一条。
    if (j.messageId !== undefined && j.messageId !== null) msg.mid = Number(j.messageId)
    if (j.emotion) (mine || msg).emotion = String(j.emotion)
    if (j.degraded) {
      msg.degraded = true
      msg.degradeReason = j.degradeReason
        ? String(j.degradeReason)
        : '模型这一次不可用，这条来自离线陪伴话术库（BR6 要求界面标明）。'
    }
    if (j.safetyRewritten) {
      // 输出侧闸整条替换过：气泡必须换成落库的那份，否则界面显示的和服务端存的是两句话。
      msg.rewritten = true
      if (j.content) msg.text = String(j.content)
    }
    if (j.interrupted) {
      msg.interrupted = true
      if (j.content) msg.text = String(j.content)
    }
    if (j.firstTokenMs !== undefined) msg.firstTokenMs = Number(j.firstTokenMs) || 0
    if (j.latencyMs !== undefined) msg.latencyMs = Number(j.latencyMs) || 0
    if (j.hotline && card.value && card.value.hotline !== String(j.hotline)) {
      card.value = Object.assign({}, card.value, { hotline: String(j.hotline) })
    }
    return
  }
  if (ev.event === 'error') {
    streamTerminal = true
    const j = ev.json || {}
    codes.send = j.code === undefined ? CODE.AI_UNAVAILABLE : Number(j.code)
    streamError.value = j.msg || ev.text || '生成中断，这一句没有计入今日配额。'
    // error 帧不带 messageId，所以已经上屏的半句在这里没法点赞踩；刷新这条会话就能补上。
    if (msg.text) msg.interrupted = true
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || busy.send) return
  const level = detectRisk(text)
  const mine = pushMsg('me', text, level)
  input.value = ''
  busy.send = true
  codes.send = null
  streamError.value = ''
  const msg = pushMsg('ai', '', 0)
  msg.streaming = true
  msg.retryText = text
  streamTerminal = false
  const wasNew = !activeId.value
  const ctrl = new AbortController()
  abortRef = ctrl
  try {
    const res = await fetch(CHAT_STREAM_API, {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }, authHeaders()),
      body: JSON.stringify({
        // 空串或 local-xxx 都会被后端 openConversation 当成「新开一条会话」，不是拿去查库的 id
        conversationId: conversationId.value || localSession.value,
        message: text,
        style: brief.value.aiStyle || 'gentle'
      }),
      signal: ctrl.signal
    })
    const ctype = res.headers.get('content-type') || ''
    if (!res.ok || ctype.indexOf('text/event-stream') < 0) {
      // 这一支覆盖三类真实失败：未登录 401、限流 429、以及后端包没更新导致的路由缺失 90006。
      // 两种都按统一协议读出 code 交给页面提示，不伪造回复。
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
        handleSseEvent(rawEvent, msg, mine)
        idx = buf.indexOf('\n\n')
      }
    }
    if (buf.trim()) handleSseEvent(buf, msg, mine)
    if (!streamTerminal && !ctrl.signal.aborted) {
      // 读流自己结束了，却没有 done 也没有 error：这种「安静的断」只可能来自 vite HMR 重连、代理重启或网络半途断。
      // 后端在 AiController 的 finally 里一定会 finish() 终止帧，所以缺帧就是链路断了，不能当成正常回复。
      msg.dropped = true
      msg.interrupted = true
      if (!msg.text) msg.text = '（这一句没写完就断了）'
      streamError.value = '连接在生成途中断了，这一句没有落库，也不计入今日配额。'
    }
    dropIfEmpty(msg)
  } catch (e) {
    codes.send = e && e.code ? e.code : 'network'
    if (e && e.name === 'AbortError') {
      if (msg.text) msg.interrupted = true
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
      pushMsg('sys', '后端没有收下这一句（接口没通或路由不存在）：它只留在你本机，没有发给任何模型。', level)
    } else {
      dropIfEmpty(msg)
      pushMsg('sys', '发送失败：' + ((e && e.message) || '未知错误'), 0)
    }
  } finally {
    msg.streaming = false
    abortRef = null
    busy.send = false
    // 第一轮对话：后端建了会话并回了真实 id，侧栏要在这一句之后把它显示出来（标题取自首句）。
    if (wasNew && /^\d+$/.test(String(conversationId.value))) {
      activeId.value = String(conversationId.value)
      writeActive(activeId.value)
      loadConversations()
    }
    scrollToEnd()
  }
}

function stop() {
  if (abortRef) abortRef.abort()
}

onMounted(async () => {
  loadBrief()
  loadHotline()
  await loadConversations()
  const remembered = readActive()
  if (!remembered) return
  let target = null
  for (const c of conversations.value) {
    if (String(c.id) === remembered) target = c
  }
  if (target) openConversation(target)
  else writeActive('') // 那条已经被删掉或超出 50 条：忘掉它，停在「新建」的空态
})
onUnmounted(() => {
  if (abortRef) abortRef.abort()
  if (armTimer) clearTimeout(armTimer)
})
</script>

<style scoped>
/* U7 = 左会话列表 + 右聊天区。侧栏固定宽度，右侧 min-width:0 让长句子换行而不是把布局撑破。 */
.chat { display: flex; align-items: flex-start; gap: 14px; max-width: 1180px; margin: 0 auto; }
.main { flex: 1 1 auto; min-width: 0; }
.side { flex: 0 0 236px; align-self: stretch; padding: 12px 10px; display: flex; flex-direction: column; }
.side-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px 8px; }
.side-head b { font-size: 14px; }
.conv-box { flex: 1 1 auto; overflow-y: auto; min-height: 260px; max-height: calc(100vh - 320px); }
.side-empty { padding: 14px 6px; line-height: 1.9; }
.conv { position: relative; display: block; padding: 9px 48px 9px 10px; border-radius: 10px; cursor: pointer; }
.conv:hover { background: var(--mi-hover); }
.conv.on { background: var(--mi-fill); box-shadow: inset 2px 0 0 var(--mi-primary); }
.conv .ct { display: block; font-size: 13px; line-height: 1.6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv .cd { display: block; font-size: 11px; margin-top: 2px; }
.conv .cs { display: block; font-size: 11px; color: var(--mi-mist); margin-top: 1px; }
.conv .rn { position: absolute; right: 28px; top: 8px; width: 18px; height: 18px; line-height: 16px; text-align: center; border: 1px solid var(--mi-border); border-radius: 6px; font-size: 12px; color: var(--mi-text-dim); }
.conv .rn:hover { color: var(--mi-text); border-color: var(--mi-primary); }
.conv .ct-input { display: block; width: 100%; box-sizing: border-box; font-size: 13px; line-height: 1.6; padding: 1px 4px; border: 1px solid var(--mi-primary); border-radius: 6px; background: var(--mi-bg-elev); color: var(--mi-text); }
.conv .del { position: absolute; right: 6px; top: 8px; width: 18px; height: 18px; line-height: 16px; text-align: center; border: 1px solid var(--mi-border); border-radius: 6px; font-size: 12px; color: var(--mi-text-dim); }
.conv .del:hover { color: var(--mi-danger-text); border-color: var(--mi-anger); }
.conv .del.armed { color: var(--mi-on-primary); background: var(--mi-danger); border-color: var(--mi-anger); font-size: 11px; }
.side-foot { margin-top: 8px; padding: 8px 4px 0; border-top: 1px solid var(--mi-border); line-height: 1.8; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.h1 { margin: 0; font-size: 21px; }
.dim { color: var(--mi-text-dim); font-size: 12.5px; margin: 6px 0 0; }
.ops { display: flex; gap: 8px; flex-wrap: wrap; }
.crisis { margin-bottom: 12px; border-left: 4px solid var(--mi-mist); }
.crisis.lv1 { border-left-color: var(--mi-trust); }
.crisis.lv2 { border-left-color: var(--mi-anger); }
.crisis.lv3 { border-left-color: var(--mi-anger); background: var(--mi-danger-soft); }
.crisis-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.badge { font-size: 12px; padding: 2px 9px; border-radius: 999px; background: var(--mi-fill); color: var(--mi-text); }
.badge.b1 { background: var(--mi-mist-bg); color: var(--mi-trust); }
.badge.b2 { background: var(--mi-danger-soft); color: var(--mi-danger-text); }
.badge.b3 { background: var(--mi-danger); color: var(--mi-on-primary); }
.phone { font-size: 17px; color: var(--mi-primary); letter-spacing: .5px; }
.crisis-text { margin: 8px 0 0; font-size: 13.5px; line-height: 1.9; }
.crisis-ops { margin-top: 8px; display: flex; flex-direction: column; gap: 6px; }
.link { color: var(--mi-mist); font-size: 12.5px; }
.warn { color: var(--mi-danger-text); font-size: 12.5px; line-height: 1.8; margin: 8px 2px 0; }
.stream { padding: 0; overflow: hidden; }
.stream-box { height: 420px; overflow-y: auto; padding: 16px 18px; }
.empty { text-align: center; padding-top: 158px; line-height: 1.9; }
.row { display: flex; margin-bottom: 12px; }
.row.me { justify-content: flex-end; }
.bubble { max-width: 76%; padding: 10px 14px; border-radius: 14px; background: var(--mi-card); border: 1px solid var(--mi-border); }
.row.me .bubble { background: var(--mi-bubble-me); border-color: var(--mi-primary-line); }
.row.sys .bubble { background: transparent; border-style: dashed; }
.row.ai .bubble { background: var(--mi-bubble); border-color: var(--mi-border-2); }
.txt { margin: 0; font-size: 14px; line-height: 1.85; white-space: pre-wrap; word-break: break-word; }
.caret { color: var(--mi-primary); }
/* AI 气泡的 Markdown 样式（T4.17）。v-html 插进来的节点不带 data-v，必须用 :deep() 才够得着；
   .md 要把 pre-wrap 关掉，否则块级标签前面会多出空行。 */
.md { white-space: normal; }
.md :deep(.md-p) { margin: 0; }
.md :deep(.md-h) { margin: 2px 0 4px; font-weight: 600; color: var(--mi-mist); }
.md :deep(.md-h1) { font-size: 16px; }
.md :deep(.md-h2) { font-size: 15.5px; }
.md :deep(.md-h3) { font-size: 15px; }
.md :deep(.md-ul), .md :deep(.md-ol) { margin: 4px 0; padding-left: 22px; }
.md :deep(.md-ul) { list-style: disc; }
.md :deep(.md-ol) { list-style: decimal; }
.md :deep(.md-li) { margin: 2px 0; }
.md :deep(.md-quote) { margin: 6px 0; padding: 6px 10px; border-left: 3px solid var(--mi-primary); background: var(--mi-fill); border-radius: 0 8px 8px 0; }
.md :deep(.md-code) { font-family: ui-monospace, Consolas, monospace; font-size: 12.5px; background: var(--mi-fill); border: 1px solid var(--mi-hairline); border-radius: 5px; padding: 1px 5px; }
.md :deep(.md-pre) { margin: 6px 0; padding: 9px 11px; background: var(--mi-fill); border: 1px solid var(--mi-hairline); border-radius: 9px; overflow-x: auto; }
.md :deep(.md-pre-code) { display: block; font-family: ui-monospace, Consolas, monospace; font-size: 12.5px; line-height: 1.7; white-space: pre; }
.md :deep(.md-hr) { border: 0; border-top: 1px dashed var(--mi-border); margin: 8px 0; }
.md :deep(.md-a) { color: var(--mi-primary); text-decoration: underline; text-underline-offset: 2px; }
.md :deep(.md-strong) { color: var(--mi-text); }
.md :deep(.md-caret) { color: var(--mi-primary); }
.retry { margin: 6px 0 0; display: flex; align-items: center; gap: 8px; font-size: 11.5px; }
.retry button { background: var(--mi-warn-soft); border: 1px solid var(--mi-warn); color: var(--mi-warn); font-size: 11.5px; padding: 3px 10px; border-radius: 999px; cursor: pointer; }
.retry button:disabled { opacity: .5; cursor: not-allowed; }
.meta { margin: 6px 0 0; font-size: 11px; color: var(--mi-text-dim); display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.meta .pill { border-width: 1px; }
.meta .off { color: var(--mi-danger-text); }
.reason { margin: 4px 0 0; font-size: 11.5px; line-height: 1.7; }
.fb { margin: 7px 0 0; display: flex; align-items: center; gap: 8px; font-size: 11.5px; }
.fb button { background: var(--mi-fill); border: 1px solid var(--mi-border); color: var(--mi-text-dim); font-size: 11.5px; padding: 3px 10px; border-radius: 999px; cursor: pointer; }
.fb button:hover { color: var(--mi-text); border-color: var(--mi-mist); }
.fb button.on { color: var(--mi-on-primary); background: var(--mi-mist); border-color: var(--mi-mist); }
.composer { margin-top: 12px; }
.quick { display: flex; gap: 8px; flex-wrap: wrap; margin: 10px 0; }
.chip { background: var(--mi-fill); border: 1px solid var(--mi-border); color: var(--mi-text-dim); font-size: 12px; padding: 5px 10px; border-radius: 999px; cursor: pointer; }
.chip:hover { color: var(--mi-text); border-color: var(--mi-mist); }
.chip.new { padding: 3px 10px; }
.chip[disabled] { opacity: .5; cursor: not-allowed; }
.send-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.btns { display: flex; gap: 8px; }
.disclaimer { margin: 12px 2px 0; font-size: 12px; line-height: 1.9; color: var(--mi-text-dim); }

/* 窄屏（演示时也常用来投屏）把侧栏收到上面来，会话列表横向滚动而不是压成一列长条 */
@media (max-width: 980px) {
  .chat { flex-direction: column; }
  .side { flex: none; width: 100%; }
  .conv-box { max-height: 168px; }
}
</style>
