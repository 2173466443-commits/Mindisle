<template>
  <div class="page hp">
    <section class="mi-card hero">
      <h1 class="h1">当你现在很难受</h1>
      <p class="lead">
        你不需要一个人扛着。这里放着几根能马上抓住的绳子 ——
        先打个电话，或者先跟着下面两步把呼吸放慢，都可以。
      </p>
      <p v-if="card.text" class="card-text">{{ card.text }}</p>
      <p class="dim">
        号码来源：{{ sourceText }}
        <span v-if="card.degraded">（数据库没读到，用的是系统内置号码）</span>
      </p>
    </section>

    <!-- 安全确认：需求 §5.2 的 L3 处置矩阵要求「全屏求助页 + 强制确认」，
         两个按钮的差别不是样式，而是下一步给什么 ——
         选「我需要帮助」时把自助引导（grounding / 呼吸）整段展开，
         选「我现在安全」时只留一条随时可以回来的路，不追着人问。 -->
    <section class="mi-card safe">
      <div class="safe-q">此刻的你，安全吗？</div>
      <div class="safe-btns">
        <button type="button" class="sbtn s-safe" @click="answer(true)">我现在安全</button>
        <button type="button" class="sbtn s-help" @click="answer(false)">我需要帮助</button>
      </div>
      <p v-if="checkedIn" class="dim safe-note">
        你{{ checkedIn.safe ? '在 ' + fmtDateTime(checkedIn.at) + ' 说过自己是安全的' : '在 ' + fmtDateTime(checkedIn.at) + ' 说过需要帮助' }}
        （这条记录只存在这台设备的浏览器里，没有发给任何人）。
        <button type="button" class="lnk" @click="clearCheckin">清除这条记录</button>
      </p>
      <p v-else class="dim safe-note">不想回答也没关系，下面的号码一直都在。</p>
    </section>

    <section class="hotlines">
      <article v-for="h in hotlines" :key="h.label" class="mi-card hl" :class="'tone-' + h.tone">
        <div class="hl-top">
          <span class="hl-tag">{{ h.label }}</span>
          <span v-if="h.badge" class="hl-badge">{{ h.badge }}</span>
        </div>
        <div class="hl-num">
          <a v-if="h.dialable" :href="'tel:' + h.dial">{{ h.num }}</a>
          <span v-else>{{ h.num }}</span>
        </div>
        <p class="hl-desc">{{ h.desc }}</p>
        <!-- 号码没配置好之前不给「拨打」和「复制」：这是一张危机页，
             把人接进一个编造的号码，比让人自己去查更糟。 -->
        <div class="hl-ops">
          <template v-if="h.dialable">
            <button type="button" class="mini" @click="copy(h)">{{ copiedKey === h.key ? '已复制 ✓' : '复制号码' }}</button>
            <a class="mini tel" :href="'tel:' + h.dial">拨打</a>
          </template>
          <span v-else class="hl-off">号码待学校心理中心配置，暂不可拨</span>
        </div>
      </article>
    </section>

    <transition name="fold">
      <div v-show="needHelp || expanded" class="selfhelp">
        <section class="mi-card blk">
          <h2 class="h2">第一步：5-4-3-2-1 着陆练习</h2>
          <p class="dim">
            当脑子里的声音太快时，先回到这间屋子里。跟着下面五步，说出你注意到的东西 ——
            这不是转移注意力，是给情绪一个可以踩住的地面。
          </p>
          <div class="gd">
            <div v-for="(g, i) in GROUNDING" :key="g.sense" class="gd-row" :class="{ 'is-cur': i === gdStep }">
              <span class="gd-n">{{ g.count }}</span>
              <div class="gd-body">
                <div class="gd-t">{{ g.count }} 样{{ g.sense }}的东西</div>
                <div class="gd-h">{{ g.hint }}</div>
              </div>
              <button v-if="i === gdStep" type="button" class="mini gd-done" @click="nextGrounding">
                {{ gdStep === GROUNDING.length - 1 ? '做完了' : '我说完了' }}
              </button>
            </div>
          </div>
          <p v-if="gdStep >= GROUNDING.length" class="gd-end">
            做得很好。你刚刚带着自己走了一遍「回到此刻」的路 —— 如果还是难受，下面还有呼吸这一步，或者直接打电话。
          </p>
          <p v-else class="dim">
            <button type="button" class="lnk" @click="gdStep = 0">从头再来</button>
          </p>
        </section>

        <section class="mi-card blk">
          <h2 class="h2">第二步：4-4-4-4 盒式呼吸</h2>
          <p class="dim">吸气 4 秒、屏住 4 秒、呼气 4 秒、再停 4 秒。跟着圈走三到四轮就够了，不需要做得标准。</p>
          <div class="br">
            <div class="br-ring" :class="{ 'is-run': breathing }" :style="ringStyle">
              <span class="br-phase">{{ breathing ? phase : '准备' }}</span>
              <span class="br-count">{{ breathing ? count : '4-4-4-4' }}</span>
            </div>
            <div class="br-ops">
              <button type="button" class="mini wide" @click="toggleBreath">{{ breathing ? '暂停' : '开始跟着呼吸' }}</button>
              <span class="dim">已陪跑 {{ rounds }} 轮</span>
            </div>
          </div>
        </section>

        <section class="mi-card blk">
          <h2 class="h2">第三步：找一个人，不一定是专业的</h2>
          <p class="lines">
            需求 FR10.3 把「身边可信任的人」和热线并列，因为多数时候拦住一次危机的不是热线，
            而是隔壁宿舍有个人愿意听你说两句。室友、家人、辅导员、社团里说得上话的人 ——
            现在就可以把手机翻到那个对话框。
          </p>
          <div class="ops">
            <router-link v-if="logged" class="mini tel" :to="{ name: 'ai-chat' }">先和 AI 说说</router-link>
            <router-link v-else class="mini tel" :to="{ name: 'login', query: { redirect: '/ai' } }">登录后和 AI 说说</router-link>
            <router-link v-if="logged" class="mini" :to="{ name: 'chat' }">给私信里的人发一句</router-link>
            <router-link v-else class="mini" :to="{ name: 'login' }">登录后发私信</router-link>
          </div>
        </section>
      </div>
    </transition>

    <section v-if="!needHelp && !expanded" class="mi-card blk gap">
      <button type="button" class="mini wide" @click="expanded = true">我不想回答，但想学两个能自己做的练习</button>
    </section>

    <section class="mi-card blk note">
      <p class="tip">
        心屿是陪伴与倾诉的社区，<b>不是</b>医疗诊断工具，也不能替代危机干预（需求 FR10.6 的免责与告知）。
        系统检测到高风险表达时会触发 L0–L3 分级响应并置顶上述渠道；情况紧急请直接拨打 <b>120 / 110</b>。
      </p>
      <p class="dim">
        <router-link to="/login">返回登录</router-link> ·
        <router-link to="/feed">去广场看看</router-link> ·
        <router-link to="/privacy">隐私与账号</router-link>
      </p>
    </section>

    <section class="mi-card blk gap">
      <h2 class="h2">这一页还欠什么</h2>
      <ul class="lines">
        <li><b>校心理中心号码还是占位值</b>：接口 <code>GET /api/system/hotline</code> 已经支持 <code>campus</code> 键（管理端 配置中心 改 <code>prompt.crisis_card</code> 即可生效），但按项目红线「演示数据 100% 虚构」，默认值刻意写成一个明显拨不通的占位串并回 <code>campusConfigured=false</code>，界面上会明说这是示例 —— 不编一个看起来能打通的号码。</li>
        <li><b>「我现在安全」只写本地</b>：这条确认存在浏览器 localStorage（24 小时后视为过期），<b>不写库、不生成工单</b>。用户侧的确认接口还没有排期，写本地是「让人能回答」和「不假装服务端收到了」之间唯一诚实的取法。</li>
        <li><b>L3 全屏强制未做</b>：§5.2 处置矩阵里 L3 是「全屏求助页 + 强制确认」，现在只有 AI 对话与发帖链路会置顶 CrisisCard，这一页本身仍是普通路由页，可以随意离开。真正的全屏锁与倒计时属阶段 8。</li>
        <li><b>没有语音引导</b>：grounding 与呼吸目前只有文字和动画，需求 §11 的「呼吸/正念引导音频、白噪音屿声」整块是 V2 范围。</li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { hotline } from '@/api/system'
import { useUserStore } from '@/stores/user'
import { fmtDateTime } from '@/utils/format'

// U14 危机求助页（需求分析文档 §10.1 第 14 行 + FR10.3 + §5.2 的 L3 处置）。
// 这一页免登录可达是硬要求（危机场景下用户可能连密码都不记得），所以这里不出现任何 requiresAuth 的东西：
// 需要登录的入口一律给「登录后…」的文案，而不是点了跳登录页再回不来。
const user = useUserStore()
const logged = computed(() => user.isLogged)

const CHECKIN_KEY = 'mindisle_crisis_checkin'
const CHECKIN_TTL = 24 * 60 * 60 * 1000

const card = ref({})
const copiedKey = ref('')
const checkedIn = ref(null)
const needHelp = ref(false)
const expanded = ref(false)

// 号码表：12356 走后端（管理员可改），12355 与校中心同样走后端新增的两个键，
// 120/110 写死在前端 —— 那不是「配置项」，任何配置读失败都不该让急救号码消失。
const hotlines = computed(() => {
  const c = card.value || {}
  const out = [
    { key: 'national', label: '全国心理援助热线', num: c.hotline || '12356', dial: (c.hotline || '12356').replace(/[^0-9]/g, ''), desc: '24 小时人工接听，免费。不知道该怎么开口时，可以直接说「我现在很难受」。', tone: 'primary', dialable: true },
    { key: 'youth', label: '共青团青少年服务台', num: c.youth || '12355', dial: (c.youth || '12355').replace(/[^0-9]/g, ''), desc: '面向学生的咨询与求助，也接学业、人际、家庭这类「说不出口但一直压着你」的事。', tone: 'calm', dialable: true }
  ]
  if (c.campus) {
    // 后端占位值带了一段中文说明（"010-0000-0000（示例，待学校心理中心配置）"），
    // 大字号码位只留号码本身，那段说明归到 badge 与 desc 里去 —— 危机页的大字要一眼能读。
    const raw = String(c.campus)
    const configured = c.campusConfigured !== false
    out.push({
      key: 'campus', label: '学校心理咨询中心',
      num: raw.split('（')[0].trim(),
      dial: raw.replace(/[^0-9]/g, ''),
      dialable: configured,
      desc: configured ? '离你最近的一条线。多数学校也接受电话预约，不用先见面。' : '这一格还是系统占位值。请让管理员在 配置中心 的 prompt.crisis_card 里填上本校心理中心的实际号码 —— 我们不编一个看起来能打通的号码。',
      tone: 'calm',
      badge: configured ? '' : '待配置'
    })
  }
  out.push({ key: 'emergency', label: '紧急情况', num: '120 / 110', dial: '120', desc: '已经发生伤害、或你能确定马上要发生 —— 不要先上网找答案，直接打。', tone: 'urgent', dialable: true })
  return out
})

const sourceText = computed(() => {
  const s = card.value && card.value.source
  if (s === 'sys_config') return '系统配置（管理员可改）'
  if (s === 'fallback') return '内置兜底'
  return '读取中…'
})

const GROUNDING = [
  { count: 5, sense: '能看到', hint: '说出五样你现在看得见的东西：杯子、窗帘、手机、桌上的纸、灯。' },
  { count: 4, sense: '能摸到', hint: '四样摸得到的感觉：脚底和地面、椅背、袖口、桌面或自己的手。' },
  { count: 3, sense: '能听到', hint: '三种声音：空调、楼外的车、自己的呼吸，或者远处的说话声。' },
  { count: 2, sense: '能闻到', hint: '两种气味：空气本身也算一种。想不起来就凑近闻一下袖口。' },
  { count: 1, sense: '能尝到 / 想说给自己', hint: '一种嘴里的味道，或者一句你今天愿意对自己说的话。' }
]
const gdStep = ref(0)
function nextGrounding() { gdStep.value += 1 }

// 盒式呼吸：4 拍 × 4 段。用 setInterval 而不是 CSS animation 驱动文案，
// 是因为「第几秒」要显示出来；圈的缩放交给 transition，两者共用同一个 tick 状态。
const PHASES = ['吸气', '屏住', '呼气', '停']
const STEP_SEC = 4
const breathing = ref(false)
const phase = ref('吸气')
const count = ref(4)
const rounds = ref(0)
let timer = null
let t = 0

const ringStyle = computed(() => {
  if (!breathing.value) return {}
  const i = PHASES.indexOf(phase.value)
  const grow = i === 0, hold = i === 1, shrink = i === 2
  const k = (STEP_SEC - count.value) / STEP_SEC
  let scale = 1
  if (grow) scale = 0.72 + 0.28 * k
  else if (hold) scale = 1
  else if (shrink) scale = 1 - 0.28 * k
  return { transform: 'scale(' + scale.toFixed(3) + ')', transitionDuration: '1s' }
})

function toggleBreath() {
  if (breathing.value) { stopBreath(); return }
  breathing.value = true
  t = 0
  phase.value = PHASES[0]
  count.value = STEP_SEC
  timer = setInterval(function () {
    t += 1
    const idx = Math.floor(t / STEP_SEC) % 4
    phase.value = PHASES[idx]
    count.value = STEP_SEC - (t % STEP_SEC)
    if (t > 0 && t % 16 === 0) rounds.value += 1
  }, 1000)
}

function stopBreath() {
  breathing.value = false
  if (timer) { clearInterval(timer); timer = null }
  phase.value = '准备'
  count.value = 4
}

function readCheckin() {
  try {
    const raw = localStorage.getItem(CHECKIN_KEY)
    if (!raw) return null
    const o = JSON.parse(raw)
    if (!o || !o.at || Date.now() - Number(o.at) > CHECKIN_TTL) return null
    return { at: Number(o.at), safe: !!o.safe }
  } catch (e) {
    return null
  }
}

function answer(safe) {
  const at = Date.now()
  checkedIn.value = { at, safe }
  try { localStorage.setItem(CHECKIN_KEY, JSON.stringify({ at, safe })) } catch (e) { /* 隐私模式下写不进去，不假装成功 */ }
  if (!safe) needHelp.value = true
}

function clearCheckin() {
  checkedIn.value = null
  needHelp.value = false
  try { localStorage.removeItem(CHECKIN_KEY) } catch (e) { /* 同上 */ }
}

function copy(h) {
  const txt = String(h.num).replace(/[^0-9\/]/g, '')
  const done = function () { copiedKey.value = h.key; setTimeout(function () { copiedKey.value = '' }, 2000) }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(txt).then(done).catch(function () { legacyCopy(txt, done) })
  } else {
    legacyCopy(txt, done)
  }
}

// 非安全上下文（http + 局域网 IP）下 navigator.clipboard 直接不存在，
// 求助页不能因为「你是从别的手机连过来的」就复制不了号码。
function legacyCopy(txt, done) {
  const ta = document.createElement('textarea')
  ta.value = txt
  ta.setAttribute('readonly', '')
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try { document.execCommand('copy'); done() } catch (e) { /* 复制失败时用户仍然能长按选中 */ }
  document.body.removeChild(ta)
}

onMounted(async function () {
  try {
    card.value = (await hotline()) || {}
  } catch (e) {
    card.value = {}
  }
  checkedIn.value = readCheckin()
  if (checkedIn.value && !checkedIn.value.safe) needHelp.value = true
})

onUnmounted(stopBreath)
</script>

<style scoped>
.page { max-width: 880px; margin: 0 auto; }
.h1 { margin: 0; font-size: 24px; color: var(--mi-text); }
.h2 { margin: 0 0 8px; font-size: 16px; font-weight: 700; color: var(--mi-text); }
.dim { font-size: 12px; color: var(--mi-text-dim); line-height: 1.8; }
.hero { padding: 20px 22px; }
.lead { font-size: 15px; line-height: 1.9; margin: 10px 0 0; color: var(--mi-text); }
.card-text { margin: 12px 0 0; padding: 10px 12px; border-left: 3px solid var(--mi-primary);
             background: var(--mi-primary-soft); font-size: 14px; line-height: 1.8; color: var(--mi-text); border-radius: 0 8px 8px 0; }

.safe { margin-top: 14px; padding: 18px 22px; }
.safe-q { font-size: 15px; font-weight: 700; color: var(--mi-text); }
.safe-btns { display: flex; gap: 12px; margin-top: 14px; flex-wrap: wrap; }
.sbtn { flex: 1; min-width: 180px; padding: 16px 12px; border-radius: 14px; border: 1px solid var(--mi-border);
        background: #fff; font-size: 16px; font-weight: 700; cursor: pointer; transition: all .15s ease; }
.s-safe { color: var(--mi-text); }
.s-safe:hover { border-color: var(--mi-brand, #2f9e6f); background: #f2fbf6; }
.s-help { border-color: var(--mi-primary-line); background: var(--mi-primary-soft); color: var(--mi-primary); }
.s-help:hover { background: #ffe9ee; }
.safe-note { margin: 12px 0 0; }

.hotlines { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 14px; }
.hl { padding: 16px 18px; display: flex; flex-direction: column; gap: 6px; }
.tone-primary { border-color: var(--mi-primary-line); }
.tone-urgent { border-color: #ffb4a8; }
.hl-top { display: flex; align-items: center; gap: 8px; }
.hl-tag { font-size: 12px; color: var(--mi-text-dim); }
.hl-badge { font-size: 11px; padding: 1px 7px; border-radius: 999px; background: #fff4e5; color: #b76b12; }
.hl-num { font-size: 26px; font-weight: 800; letter-spacing: 1px; }
.hl-num a { color: var(--mi-primary); text-decoration: none; }
.tone-calm .hl-num a { color: var(--mi-text); }
.hl-desc { margin: 0; font-size: 13px; line-height: 1.7; color: var(--mi-text-dim); }.hl-off { font-size: 12px; color: #b76b12; background: #fff4e5; border-radius: 999px; padding: 6px 12px; }
.hl-ops { display: flex; gap: 8px; margin-top: 4px; }

.mini { display: inline-flex; align-items: center; justify-content: center; padding: 6px 14px; border-radius: 999px;
        border: 1px solid var(--mi-border); background: #fff; color: var(--mi-text); font-size: 13px; cursor: pointer;
        text-decoration: none; transition: all .15s ease; }
.mini:hover { border-color: var(--mi-primary); color: var(--mi-primary); }
.mini.tel { background: var(--mi-primary); border-color: var(--mi-primary); color: var(--mi-on-primary); font-weight: 700; }
.mini.tel:hover { color: var(--mi-on-primary); opacity: .9; }
.mini.wide { width: 100%; padding: 10px 14px; font-size: 14px; }
.lnk { border: 0; background: none; padding: 0; font-size: 12px; color: var(--mi-primary); cursor: pointer; text-decoration: underline; }

.selfhelp { margin-top: 0; }
.blk { margin-top: 14px; padding: 18px 22px; }
.gap { margin-top: 14px; padding: 14px 22px; }
.note { padding: 16px 22px; }
.tip { margin: 0 0 10px; font-size: 13px; line-height: 1.9; color: var(--mi-text); }
.tip b { color: var(--mi-primary); }
.lines { margin: 0; padding-left: 20px; font-size: 13px; line-height: 2; color: var(--mi-text-dim); }
.lines b { color: var(--mi-text); }
ul.lines { list-style: disc; }
p.lines { padding-left: 0; }

.gd { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
.gd-row { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-radius: 12px;
          border: 1px solid transparent; background: var(--mi-hover); opacity: .55; }
.gd-row.is-cur { border-color: var(--mi-primary-line); background: #fff; opacity: 1; }
.gd-n { width: 30px; height: 30px; flex: none; border-radius: 50%; background: var(--mi-primary); color: var(--mi-on-primary);
        display: flex; align-items: center; justify-content: center; font-weight: 800; font-size: 15px; }
.gd-row:not(.is-cur) .gd-n { background: var(--mi-border); color: var(--mi-text-dim); }
.gd-body { flex: 1; min-width: 0; }
.gd-t { font-size: 14px; font-weight: 700; color: var(--mi-text); }
.gd-h { font-size: 12px; color: var(--mi-text-dim); line-height: 1.7; }
.gd-end { margin: 12px 0 0; font-size: 14px; line-height: 1.9; color: var(--mi-text); }

.br { display: flex; align-items: center; gap: 22px; margin-top: 14px; flex-wrap: wrap; }
.br-ring { width: 132px; height: 132px; border-radius: 50%; background: var(--mi-primary-soft);
           border: 2px solid var(--mi-primary-line); display: flex; flex-direction: column;
           align-items: center; justify-content: center; gap: 2px; transition-property: transform;
           transition-timing-function: linear; }
.br-ring.is-run { border-color: var(--mi-primary); }
.br-phase { font-size: 15px; font-weight: 700; color: var(--mi-primary); }
.br-count { font-size: 22px; font-weight: 800; color: var(--mi-text); }
.br-ops { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.ops { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }

.fold-enter-active, .fold-leave-active { transition: opacity .2s ease; }
.fold-enter-from, .fold-leave-to { opacity: 0; }

@media (max-width: 640px) {
  .hotlines { grid-template-columns: 1fr; }
  .safe-btns { flex-direction: column; }
  .sbtn { min-width: 0; }
  .hl-num { font-size: 22px; }
}
</style>