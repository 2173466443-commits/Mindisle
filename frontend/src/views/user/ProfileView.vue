<template>
  <div class="page">
    <div class="topbar">
      <div>
        <h1 class="h1">账户中心</h1>
        <p class="dim">身份、授权与数据举证三件套：基本信息读真实接口，授权开关直接写后端流水表。</p>
      </div>
      <div class="ops">
        <el-button size="small" :loading="busy.page" @click="loadAll">刷新</el-button>
        <el-button size="small" @click="goMyPosts">我的帖子</el-button>
        <el-button size="small" @click="goPrivacy">隐私中心</el-button>
        <el-button size="small" type="primary" @click="openEdit">编辑资料</el-button>
        <el-button size="small" text @click="doLogout">退出登录</el-button>
      </div>
    </div>

    <StageNotice :code="codes.page" api-name="GET /api/users/me" />

    <section class="mi-card blk">
      <div class="head">
        <el-avatar :size="58">{{ initial }}</el-avatar>
        <div class="who">
          <p class="nick">{{ brief.nickname || brief.username || '未获取到用户信息' }}</p>
          <p class="sub">@{{ brief.username || '-' }} · 角色 {{ brief.role || 'GUEST' }} · 对话风格 {{ brief.aiStyle || 'gentle' }} · 账号状态 {{ brief.status || '-' }}</p>
        </div>
      </div>

      <div class="stats">
        <div class="stat"><b>{{ countOf(prof.followingCnt) }}</b><span>关注</span></div>
        <div class="stat"><b>{{ countOf(prof.followerCnt) }}</b><span>粉丝</span></div>
        <div class="stat"><b>{{ countOf(prof.postCnt) }}</b><span>树洞</span></div>
        <div class="stat"><b>{{ grantedCount }}</b><span>生效授权</span></div>
      </div>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="年级">{{ gradeText }}</el-descriptions-item>
        <el-descriptions-item label="学校">{{ prof.school || '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="性别">{{ genderText }}</el-descriptions-item>
        <el-descriptions-item label="个性签名">{{ prof.bio || '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="感兴趣话题" :span="2">
          <span v-if="tags.length === 0" class="dim">未填写</span>
          <el-tag v-for="(t, i) in tags" :key="i" size="small" class="tag">{{ t }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <p class="foot dim">数据来源：GET /api/users/me（UserBrief）与 GET /api/users/me/profile（ProfileView）。库里 interest_tags 是 JSON 字符串，解析失败时降级为按逗号切分展示，不抛错。</p>
    </section>

    <section class="mi-card blk">
      <h2 class="h2">授权管理（可逐项撤回）</h2>
      <p class="dim">每一次开关都会调用 POST /api/users/me/consents 写入 user_consent 流水（时间、协议版本、来源页、IP、UA）。撤回是「新增一条 WITHDRAW」，不删除历史，满足个保法第 15 条的可撤回与第 29 条的单独同意要求。</p>
      <StageNotice :code="codes.consent" api-name="POST /api/users/me/consents" />
      <div v-for="c in CONSENTS" :key="c.type" class="crow">
        <div class="crt">
          <p class="cname">{{ c.name }}</p>
          <p class="cmemo">{{ c.memo }}</p>
        </div>
        <el-switch
          v-model="consentOn[c.type]"
          :loading="busyItem[c.type]"
          @change="onToggle(c.type, $event)"
        />
      </div>
      <p class="foot dim">当前协议版本：{{ consentVersion }}（取自 GET /api/system/configs?keys=prompt.version；读不到时用前端兜底值，后端也会按 sys_config 兜底为 v1）。</p>
    </section>

    <section class="mi-card blk">
      <h2 class="h2">授权流水（最近 {{ ledger.length }} 条）</h2>
      <el-table v-loading="busy.ledger" :data="ledger" size="small" empty-text="暂无记录（多为数据库未建或后端返回 90002）">
        <el-table-column prop="createdAt" label="时间" width="190" />
        <el-table-column prop="consentType" label="类型" width="160" />
        <el-table-column label="动作" width="90">
          <template #default="s">
            <el-tag size="small" :type="s.row.action === 'GRANT' ? 'success' : 'info'">
              {{ s.row.action === 'GRANT' ? '授权' : '撤回' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="contentVersion" label="协议版本" width="110" />
        <el-table-column prop="sourcePage" label="来源页" min-width="120" />
      </el-table>
      <StageNotice :code="codes.ledger" api-name="GET /api/users/me/consents" />
    </section>

    <el-dialog v-model="editOpen" title="编辑资料" width="480px">
      <el-form :model="form" label-position="top">
        <el-form-item label="昵称（≤32 字）">
          <el-input v-model="form.nickname" maxlength="32" show-word-limit />
        </el-form-item>
        <el-form-item label="学校（≤64 字）">
          <el-input v-model="form.school" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender">
            <el-radio value="M">男</el-radio>
            <el-radio value="F">女</el-radio>
            <el-radio value="U">保密</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="个性签名（≤200 字）">
          <el-input v-model="form.bio" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <StageNotice :code="codes.save" stage="3" api-name="PUT /api/users/me/profile" />
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="busy.save" @click="saveProfile">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { me, profile, listConsents, grantConsent, updateProfile } from '@/api/user'
import { logout } from '@/api/auth'
import { configs } from '@/api/system'
import { useUserStore } from '@/stores/user'
import StageNotice from '@/components/StageNotice.vue'

// 与后端 ConsentRequest 的枚举严格一致，不多列也不漏列（阶段 2 实测：5 类授权已全部实现）。
const CONSENTS = [
  { type: 'TERMS', name: '用户服务协议', memo: '账号使用、社区规范与内容处置的基本约定。' },
  { type: 'PRIVACY', name: '隐私政策', memo: '个人信息整体处理规则；撤回后仅保留法律要求的最小数据集。' },
  { type: 'SENSITIVE_INFO', name: '敏感个人信息单独同意', memo: '情绪记录、年级等敏感字段的处理合法性基础（个保法第 29 条）。' },
  { type: 'EMOTION_SHARE', name: '情绪状态匿名展示', memo: '允许在推荐流中以七色情绪标签匿名展示当下状态，同步写 emotion_share_consent 冗余位。' },
  { type: 'CRISIS_CONTACT', name: '危机情形下的主动联系', memo: '仅在识别到 L3 危机信号时用于启动 12356 转介流程。' }
]

const GRADES = { FRESH: '大一', SOPH: '大二', JUNIOR: '大三', SENIOR: '大四', OTHER: '其他 / 已毕业' }
const router = useRouter()
const user = useUserStore()

const brief = ref({})
const prof = ref({})
const ledger = ref([])
const consentOn = reactive({})
const busyItem = reactive({})
const consentVersion = ref('v1')
const busy = reactive({ page: false, ledger: false, save: false })
const codes = reactive({ page: null, consent: null, ledger: null, save: null })

const initial = computed(() => {
  const n = brief.value.nickname || brief.value.username || '屿'
  return String(n).slice(0, 1).toUpperCase()
})
const gradeText = computed(() => GRADES[prof.value.grade] || prof.value.grade || '未填写')
const genderText = computed(() => {
  const g = prof.value.gender
  if (g === 'M' || g === 'MALE' || g === 1) return '男'
  if (g === 'F' || g === 'FEMALE' || g === 2) return '女'
  if (g === 'U' || g === 'UNKNOWN' || g === 'SECRET' || g === 0 || g === 3) return '保密'
  return g || '未填写'
})
const tags = computed(() => {
  const raw = prof.value.interestTags
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    if (Array.isArray(arr)) return arr.map((x) => String(x)).filter(Boolean).slice(0, 20)
    if (typeof arr === 'string') return [arr]
    return []
  } catch (e) {
    return String(raw).split(/[,，;；]/).map((s) => s.trim()).filter(Boolean).slice(0, 20)
  }
})
const grantedCount = computed(() => CONSENTS.filter((c) => consentOn[c.type] === true).length)

function countOf(v) {
  return v === null || v === undefined ? 0 : v
}

// 同一类型可能有多条流水：取 id 最大（其次 createdAt 最大）的一条作为当前生效状态。
function syncConsentState(rows) {
  const latest = {}
  rows.forEach((r) => {
    const prev = latest[r.consentType]
    if (!prev) { latest[r.consentType] = r; return }
    const a = Number(r.id) || 0
    const b = Number(prev.id) || 0
    if (a > b || (a === b && String(r.createdAt || '') > String(prev.createdAt || ''))) latest[r.consentType] = r
  })
  CONSENTS.forEach((c) => {
    const rec = latest[c.type]
    consentOn[c.type] = !!rec && rec.action === 'GRANT'
    busyItem[c.type] = false
  })
  // 路由守卫用的 consent 位只在真的读到流水后才更新，避免 DB 不可用时误踢人。
  user.setConsent({ privacy: consentOn.PRIVACY === true, sensitive: consentOn.SENSITIVE_INFO === true })
}

async function loadBrief() {
  try {
    const data = await me()
    brief.value = data || {}
    user.setProfile(data)
    codes.page = null
    return true
  } catch (e) {
    codes.page = e.code || 'network'
    return false
  }
}

async function loadProf() {
  try {
    const data = await profile()
    prof.value = data || {}
    return true
  } catch (e) {
    prof.value = {}
    return false
  }
}

async function loadLedger() {
  busy.ledger = true
  try {
    const data = await listConsents()
    const rows = Array.isArray(data) ? data : []
    ledger.value = rows.slice(0, 50)
    codes.ledger = null
    syncConsentState(rows)
  } catch (e) {
    codes.ledger = e.code || 'network'
    ledger.value = []
  } finally {
    busy.ledger = false
  }
}

async function loadVersion() {
  try {
    const data = await configs('prompt.version', { silent: true })
    // 后端 GET /api/system/configs 返回的是数组 [{key,value,valueType}]，不是对象，
    // 直接按对象取键会永远拿不到值，只能回落到前端兜底 v1。
    const row = Array.isArray(data) ? data.find((x) => x && x.key === 'prompt.version') : null
    const raw = row ? row.value : data && data['prompt.version']
    if (raw) consentVersion.value = String(raw)
  } catch (e) {
    /* 读不到就用兜底 v1，不打断页面主流程 */
  }
}

async function loadAll() {
  busy.page = true
  await Promise.all([loadBrief(), loadProf(), loadLedger(), loadVersion()])
  busy.page = false
}

async function onToggle(type, val) {
  busyItem[type] = true
  codes.consent = null
  try {
    await grantConsent({
      consentType: type,
      action: val ? 'GRANT' : 'WITHDRAW',
      contentVersion: consentVersion.value,
      sourcePage: 'account'
    })
    ElMessage.success(val ? '已记录你的授权' : '已记录撤回，相关处理将停止')
    user.setConsent({ privacy: consentOn.PRIVACY === true, sensitive: consentOn.SENSITIVE_INFO === true })
    loadLedger()
  } catch (e) {
    consentOn[type] = !val
    codes.consent = e.code || 'network'
  } finally {
    busyItem[type] = false
  }
}

const editOpen = ref(false)
const form = reactive({ nickname: '', school: '', gender: 'U', bio: '' })

// U12 入口：账户中心的「树洞」计数以前是个死数字，点不进去；现在它通向真正按 user_id 查的那张列表。
function goMyPosts() {
  router.push({ name: 'my-posts' })
}

// 隐私中心（任务 T4.21）：账户中心这页管的是「授权开关」，而个保法第 45/47 条要的是「我能把自己的数据带走、
// 也能把自己的账号关掉」。这两件事以前散在三个接口里没人给它们一个入口，现在收进 /privacy 一整页：
// 逐域条数对账、导出下载、冷静期注销与撤回、授权撤回、管理员到期清除。
function goPrivacy() {
  router.push({ name: 'privacy' })
}

function openEdit() {
  form.nickname = brief.value.nickname || ''
  form.school = prof.value.school || ''
  form.gender = prof.value.gender || 'U'
  form.bio = prof.value.bio || ''
  codes.save = null
  editOpen.value = true
}

async function saveProfile() {
  busy.save = true
  codes.save = null
  try {
    await updateProfile({ ...form })
    ElMessage.success('资料已保存')
    editOpen.value = false
    loadBrief()
    loadProf()
  } catch (e) {
    // 阶段 2 后端直接返回 90001，这里显式提示而不是假装保存成功。
    codes.save = e.code || 'network'
  } finally {
    busy.save = false
  }
}

async function doLogout() {
  try {
    await logout()
  } catch (e) {
    /* 令牌已失效也要清本地态 */
  }
  user.clear()
  router.replace({ name: 'login' })
}

onMounted(loadAll)
</script>

<style scoped>
.page { max-width: 980px; margin: 0 auto; }
.topbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.h1 { margin: 0; font-size: 22px; }
.h2 { margin: 0 0 6px; font-size: 16px; }
.ops { display: flex; gap: 4px; flex-shrink: 0; }
.blk { margin-top: 14px; }
.head { display: flex; align-items: center; gap: 14px; }
.nick { margin: 0; font-size: 18px; font-weight: 700; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--mi-text-dim); }
.stats { display: flex; gap: 26px; margin: 16px 0; flex-wrap: wrap; }
.stat { display: flex; flex-direction: column; align-items: center; min-width: 64px; }
.stat b { font-size: 20px; color: var(--mi-primary); }
.stat span { font-size: 12px; color: var(--mi-text-dim); }
.tag { margin: 0 6px 4px 0; }
.crow {
  display: flex; align-items: center; justify-content: space-between;
  gap: 16px; padding: 12px 0; border-bottom: 1px dashed var(--mi-border);
}
.crow:last-of-type { border-bottom: none; }
.crt { min-width: 0; }
.cname { margin: 0; font-size: 14px; font-weight: 600; }
.cmemo { margin: 3px 0 0; font-size: 12px; line-height: 1.7; color: var(--mi-text-dim); }
.foot { margin: 12px 0 0; font-size: 12px; line-height: 1.8; }
.dim { color: var(--mi-text-dim); font-size: 12px; line-height: 1.8; }
</style>
