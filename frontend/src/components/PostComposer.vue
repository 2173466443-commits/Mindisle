<template>
  <div class="composer">
    <div class="sec-head">
      <h2>{{ compact ? '此刻想说点什么' : '发布到心屿' }}</h2>
      <div class="types">
        <el-radio-group v-model="form.type" size="small" @change="onTypeChange">
          <el-radio-button v-for="t in POST_TYPES" :key="t.value" :value="t.value">{{ t.label }}</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <p class="type-note">{{ typeNote }}</p>

    <el-input v-model="form.title" maxlength="50" show-word-limit placeholder="给这段话起个标题（必填，最多 50 字）"
              class="title-input" @input="touch" />

    <el-input v-model="form.content" type="textarea" :rows="compact ? 4 : 8" maxlength="5000" show-word-limit
              :placeholder="contentPlaceholder" @input="touch" />

    <!-- 敏感词实时提醒：只画「这里可能要改」，绝不显示命中词面（后端本来也不回传） -->
    <div v-if="pre.pending" class="pre-line"><el-icon class="is-loading"><Loading /></el-icon> 正在检查内容…</div>
    <el-alert v-else-if="pre.hit" :type="pre.tone" show-icon :closable="false" class="pre-line" :title="pre.title">
      <p class="pre-body">{{ pre.tip }}</p>
      <p class="pre-meta">命中 {{ pre.hitCount }} 处 · 词库 {{ pre.dictVersion }}
        <span v-if="positionText"> · 位置 {{ positionText }}</span></p>
    </el-alert>

    <crisis-card v-if="pre.hotline" :hotline="pre.hotline" text="这段话里有让人担心的表达，先看看求助入口" />

    <div class="row">
      <el-select v-model="form.topicIds" multiple :multiple-limit="3" collapse-tags clearable
                 placeholder="关联话题（最多 3 个，可不选）" class="w260" @change="touch">
        <el-option v-for="t in topicList" :key="t.id" :label="'# ' + t.name" :value="t.id" />
      </el-select>

      <el-select v-if="form.type === 'hole'" v-model="form.autoDestroyHours" class="w160" @change="touch">
        <el-option v-for="h in DESTROY_OPTIONS" :key="h.value" :label="h.label" :value="h.value" />
      </el-select>

      <el-select v-model="form.visibility" class="w160" @change="touch">
        <el-option label="公开可见" value="public" />
        <el-option label="仅自己可见" value="private" />
      </el-select>

      <el-tooltip :content="anonTip" placement="top">
        <span class="switch-line">
          <el-switch v-model="form.anonymous" :disabled="form.type === 'hole'" @change="touch" />
          <span class="switch-label">匿名发布</span>
        </span>
      </el-tooltip>
    </div>

    <p v-if="presetDropped" class="preset-note">话题最多带 3 个：刚点进来的这个话题给你留着了，草稿里的 {{ presetDropped }} 个话题被挤掉，需要的话在上面的下拉里重选。</p>

    <div class="row">
      <el-upload :show-file-list="false" :http-request="doUpload" accept="image/png,image/jpeg,image/gif" multiple>
        <el-button size="small" :loading="busy.upload">添加配图</el-button>
      </el-upload>
      <span class="hint">jpg / png / gif，单张 ≤5MB，最多 9 张（服务端重编码去 EXIF，GIF 只保第一帧）</span>
    </div>
    <div v-if="form.images.length" class="imgs">
      <div v-for="(img, i) in form.images" :key="img.url" class="thumb">
        <el-image :src="img.url" fit="cover" class="thumb-img" />
        <button class="rm" title="移除这张" @click="removeImage(i)">×</button>
        <span class="dim">{{ img.width }}×{{ img.height }}</span>
      </div>
    </div>

    <div class="row actions">
      <el-button type="primary" :loading="busy.publish" @click="doPublish">发布</el-button>
      <el-button @click="clearDraft">清空草稿</el-button>
      <span class="hint">{{ draftLine }}</span>
    </div>

    <!-- 发布结果：后端返的是「处置结论」，不是简单的成/败，三态都得让用户看懂 -->
    <div v-if="result" class="result">
      <el-alert :type="result.tone" show-icon :closable="false" :title="result.title">
        <p v-if="result.tip" class="res-tip">{{ result.tip }}</p>
        <p v-if="result.extra" class="res-extra">{{ result.extra }}</p>
      </el-alert>
      <crisis-card v-if="result.hotline" :hotline="result.hotline" />
    </div>

    <stage-notice v-if="failCode" :code="failCode" api-name="POST /api/posts" stage="3"
                  :extra="failCode === 10010 ? '今天的发帖次数用完了（新注册 24 小时内每天 5 帖，含被拦下的那些）。明天再来，或者把想说的话先存成草稿。' : ''" />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { createPost, POST_TYPES } from '@/api/post'
import { precheck } from '@/api/audit'
import { uploadImage } from '@/api/file'
import { fmtDateTime } from '@/utils/format'
import CrisisCard from '@/components/CrisisCard.vue'
import StageNotice from '@/components/StageNotice.vue'

// U5 发布器（任务 T3.13）。三个设计约束写在这里，别在调用方再复制一份：
// 1) 标题与正文的字数上限来自后端（FR4.1 标题 ≤50、正文 ≤5000），前端 maxlength 只是省一次往返，
//    真正的判定在服务端，改这里不会改约束，只会让两边不一致。
// 2) 预检接口「需登录」：游客进不到这一页（路由 requiresAuth），所以不必处理 401 分支。
// 3) 草稿只存本机 localStorage。后端草稿箱（DRAFT 落库）属任务 3.15，别在这里假装已实现。
const props = defineProps({
  topicList: { type: Array, default: () => [] },
  compact: { type: Boolean, default: false },
  presetType: { type: String, default: '' },
  // 话题页「在这个话题下发一条」带过来的 ?topic=编号（任务 T3.8 · 手册 §6.2 U6）。
  // 刻意是「合并」语义而不是「覆盖」，原因写在 mergePresetTopics 与文件末尾 onMounted 的注释里。
  presetTopicIds: { type: Array, default: () => [] }
})
const emit = defineEmits(['published'])

const DESTROY_OPTIONS = [
  { value: 24, label: '24 小时后消失' },
  { value: 72, label: '3 天后消失' },
  { value: 168, label: '7 天后消失' }
]
const DRAFT_KEY = 'mindisle_post_draft'

const form = reactive({
  title: '',
  content: '',
  type: props.presetType || 'normal',
  visibility: 'public',
  anonymous: props.presetType === 'hole',
  topicIds: [],
  autoDestroyHours: 168,
  images: []
})
const busy = reactive({ publish: false, upload: false })
const result = ref(null)
const failCode = ref(null)
const savedAt = ref(0)

let preTimer = null
const pre = reactive({
  pending: false, hit: false, hitCount: 0, positions: [], dictVersion: '',
  hotline: '', tip: '', tone: 'warning', title: ''
})

const typeNote = computed(() => {
  if (form.type === 'hole') return '树洞：强制匿名，到期自动销毁，不进任何人的推荐流。'
  if (form.type === 'help') return '求助：卡片上会固定显示 12356 求助入口，回复由人工优先跟进。'
  return '分享：正常出现在广场与推荐流里。'
})
const contentPlaceholder = computed(() =>
  form.type === 'hole'
    ? '有些话只能烂在肚子里——这里写完它就消失了。'
    : '发生了什么？说出来会轻一点。（若出现自伤或伤人的表达，系统会先给你 12356 求助入口）')
const anonTip = computed(() => form.type === 'hole'
  ? '树洞恒匿名，且正文里的手机号等联系方式会被服务端遮罩'
  : '匿名会用「匿名屿民·X」的马甲展示名，作者 id 不会出现在接口响应里')
const draftLine = computed(() => (savedAt.value
  ? '草稿已存在本机浏览器（' + fmtDateTime(savedAt.value) + '）'
  : '输入内容会自动存草稿到本机浏览器'))

function onTypeChange() {
  if (form.type === 'hole') form.anonymous = true
  touch()
}

function buildText() {
  // 与后端 PostService 的机审输入保持同一拼接：标题 + 换行 + 正文，
  // 这样预检给出的命中位置才和真实审核看到的是同一段文本。
  return form.title.trim() + '\n' + form.content
}

function schedulePrecheck() {
  if (preTimer) clearTimeout(preTimer)
  const text = buildText()
  if (text.trim().length < 2) {
    Object.assign(pre, { pending: false, hit: false, hitCount: 0, positions: [], hotline: '', tip: '', title: '' })
    return
  }
  pre.pending = true
  preTimer = setTimeout(runPrecheck, 300)
}

async function runPrecheck() {
  const text = buildText()
  if (text.trim().length < 2) return
  try {
    const data = await precheck(text, 'post')
    const hit = !!(data && data.hit)
    Object.assign(pre, {
      pending: false,
      hit: hit,
      hitCount: data ? data.hitCount : 0,
      positions: (data && data.positions) || [],
      dictVersion: (data && data.dictVersion) || '',
      hotline: (data && data.hotline) || '',
      tip: (data && data.tip) || '',
      tone: hit ? toneOf(data) : 'warning',
      title: hit ? titleOf(data) : ''
    })
  } catch (e) {
    // 预检失败不挡发布：那是「词库服务暂时不可用」，不是「你有问题」。
    pre.pending = false
    if (e.code && e.code !== 90001) console.warn('[precheck] code=' + e.code)
  }
}

function toneOf(data) {
  if (!data) return 'warning'
  if (data.action === 'BLOCK') return 'error'
  if (data.hotline) return 'info'
  return 'warning'
}

function titleOf(data) {
  if (!data) return '内容可能需要调整'
  if (data.action === 'BLOCK') return '这段内容大概率发不出去'
  if (data.action === 'REVIEW') return '这段内容会先进人工审核，只有你自己看得到'
  return '这段话里有让人担心的表达'
}

// 用 computed 包一层：位置数组每次渲染都拼一遍字符串没必要
const positionText = computed(() => {
  const list = pre.positions || []
  if (!list.length) return ''
  const head = list.slice(0, 3).map((p) => p[0] + '-' + p[1]).join('、')
  return list.length > 3 ? head + ' 等 ' + list.length + ' 处' : head
})

watch([() => form.title, () => form.content], schedulePrecheck)

// ---------------- 图片 ----------------
async function doUpload(options) {
  const file = options.file
  if (form.images.length >= 9) {
    ElMessage.warning('一条帖子最多 9 张配图')
    return
  }
  busy.upload = true
  try {
    const data = await uploadImage(file)
    if (data && data.url) {
      form.images.push({ url: data.url, width: data.width, height: data.height })
      touch()
    }
  } catch (e) {
    // 超限时命中容器闸，响应体不是统一格式（没有 code），必须单独特判一次。
    if (e.response && e.response.status === 413) ElMessage.error('单张图片不能超过 5MB')
    else ElMessage.error(e.message || '图片上传失败')
  } finally {
    busy.upload = false
  }
}

function removeImage(i) {
  form.images.splice(i, 1)
  touch()
}

// ---------------- 草稿 ----------------
let saveTimer = null
function touch() {
  schedulePrecheck()
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(saveDraft, 500)
}

function saveDraft() {
  const empty = !form.title.trim() && !form.content.trim() && !form.images.length
  if (empty) {
    localStorage.removeItem(DRAFT_KEY)
    savedAt.value = 0
    return
  }
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({
      title: form.title, content: form.content, type: form.type, visibility: form.visibility,
      anonymous: form.anonymous, topicIds: form.topicIds, autoDestroyHours: form.autoDestroyHours,
      images: form.images, savedAt: Date.now()
    }))
    savedAt.value = Date.now()
  } catch (e) {
    // 隐私模式或配额满：草稿存不下不是错误，但不静默——否则用户以为存了其实丢了。
    ElMessage.warning('本机草稿保存失败（浏览器存储不可用或已满），别关掉页面')
  }
}

function restoreDraft() {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return
    const data = JSON.parse(raw)
    if (!data || typeof data !== 'object') return
    form.title = String(data.title || '')
    form.content = String(data.content || '')
    form.type = POST_TYPES.some((t) => t.value === data.type) ? data.type : 'normal'
    form.visibility = data.visibility === 'private' ? 'private' : 'public'
    form.anonymous = form.type === 'hole' ? true : !!data.anonymous
    form.topicIds = Array.isArray(data.topicIds) ? data.topicIds : []
    form.autoDestroyHours = DESTROY_OPTIONS.some((h) => h.value === data.autoDestroyHours)
      ? data.autoDestroyHours : 168
    form.images = Array.isArray(data.images) ? data.images.slice(0, 9) : []
    savedAt.value = Number(data.savedAt) || 0
    if (form.title || form.content) schedulePrecheck()
  } catch (e) {
    localStorage.removeItem(DRAFT_KEY)
  }
}

// ---------------- 话题预填（任务 T3.8）----------------
// 三件事必须一起做，单独写任何一件都会变成 bug：
// 1) 顺序：调用方解析 ?topic= 要发一次请求，所以合并既要在 restoreDraft 之后跑（否则预填会被草稿覆盖），
//    又要 watch props（否则预填到得比子组件挂载晚，永远合不进来）。两条都得留着。
// 2) 去重：草稿里可能已经挂着同一个话题，重复 id 发给后端会被 resolveTopics 判 10001。
// 3) 上限 3：el-select 的 :multiple-limit 只管手点，程序赋值不受它约束，只能自己截；
//    被挤掉的条数要写在界面上（preset-note），不能悄悄少带一个话题。
const presetDropped = ref(0)
function mergePresetTopics() {
  const preset = []
  for (const raw of props.presetTopicIds || []) {
    const v = Number(raw)
    if (Number.isInteger(v) && v > 0 && preset.indexOf(v) < 0 && preset.length < 3) preset.push(v)
  }
  if (!preset.length) return
  const merged = preset.slice()
  let dropped = 0
  for (const raw of form.topicIds || []) {
    const v = Number(raw)
    if (!Number.isInteger(v) || v <= 0 || merged.indexOf(v) >= 0) continue
    if (merged.length < 3) merged.push(v)
    else dropped += 1
  }
  presetDropped.value = dropped
  form.topicIds = merged
}

function clearDraft() {
  localStorage.removeItem(DRAFT_KEY)
  savedAt.value = 0
  Object.assign(form, {
    title: '', content: '', visibility: 'public', anonymous: form.type === 'hole',
    topicIds: [], autoDestroyHours: 168, images: []
  })
  result.value = null
  Object.assign(pre, { pending: false, hit: false, hitCount: 0, positions: [], hotline: '', tip: '', title: '' })
}

// ---------------- 发布 ----------------
async function doPublish() {
  if (!form.title.trim()) { ElMessage.warning('标题要写点什么才好'); return }
  if (!form.content.trim()) { ElMessage.warning('正文不能为空'); return }
  busy.publish = true
  result.value = null
  failCode.value = null
  try {
    const payload = {
      title: form.title.trim(),
      content: form.content,
      type: form.type,
      visibility: form.visibility,
      anonymous: form.type === 'hole' ? true : !!form.anonymous,
      topicIds: form.topicIds || [],
      images: form.images.map((x) => x.url)
    }
    if (form.type === 'hole' && form.autoDestroyHours) payload.autoDestroyHours = form.autoDestroyHours
    const data = await createPost(payload)
    result.value = describe(data)
    if (data && data.status === 'PUBLISHED') {
      localStorage.removeItem(DRAFT_KEY)
      savedAt.value = 0
      form.title = ''
      form.content = ''
      form.images = []
      emit('published', data)
    }
  } catch (e) {
    failCode.value = e.code || 'network'
    // 配额（10010）与禁言（10003）这类「发不出去」的原因由 StageNotice 明说，
    // 这里只把内容被拦下的两种业务码翻译进结果面板，避免同一个错误弹两次。
    if (e.code === 50001 || e.code === 50002) result.value = describe({ status: 'REJECTED', tip: e.message, hotline: '' })
  } finally {
    busy.publish = false
  }
}

function describe(data) {
  const status = (data && data.status) || ''
  const map = {
    PUBLISHED: { tone: 'success', title: '已发布，屿友们能看到你了' },
    HUMAN_REVIEW: { tone: 'warning', title: '已提交，正在人工审核（当前只有你自己可见）' },
    MACHINE_REVIEW: { tone: 'warning', title: '已提交，机器审核中' },
    REJECTED: { tone: 'error', title: '这条内容没有发出去' },
    DRAFT: { tone: 'info', title: '草稿已保存' }
  }
  const base = map[status] || { tone: 'info', title: '已提交（status=' + status + '）' }
  const extra = []
  if (data && data.autoDestroyAt) extra.push('到期自动销毁：' + fmtDateTime(data.autoDestroyAt))
  if (status === 'PUBLISHED' && data && data.visibility === 'private') extra.push('这条只有你自己看得到')
  return {
    tone: base.tone, title: base.title, tip: (data && data.tip) || '',
    hotline: (data && data.hotline) || '', extra: extra.join('；'), status: status
  }
}

// 先恢复草稿、再合入预填：顺序反过来的话，一分钟前那份草稿里的三个话题会把
// ?topic= 那一个覆盖掉，用户从话题页跳过来却发现发帖框里没带这个话题。
onMounted(() => {
  restoreDraft()
  mergePresetTopics()
})
// PublishView 解析 ?topic= 要等一次接口，通常比子组件挂载晚到，所以这一条 watch 不是可选的。
watch(() => props.presetTopicIds, mergePresetTopics, { deep: true })
onUnmounted(() => {
  if (preTimer) clearTimeout(preTimer)
  if (saveTimer) clearTimeout(saveTimer)
})
</script>

<style scoped>
.composer { display: flex; flex-direction: column; gap: 12px; }
.sec-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
.type-note { margin: 0; font-size: 12px; color: var(--mi-text-dim); }
.preset-note { margin: 0; font-size: 12px; line-height: 1.7; color: var(--mi-warn, #e0a33e); }
.title-input { max-width: 620px; }
.pre-line { margin: 0; }
.pre-body { margin: 4px 0 0; font-size: 13px; line-height: 1.7; }
.pre-meta { margin: 4px 0 0; font-size: 12px; color: var(--mi-text-dim); }
.row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.actions { margin-top: 4px; }
.w260 { width: 260px; }
.w160 { width: 160px; }
.switch-line { display: flex; align-items: center; gap: 8px; }
.switch-label { font-size: 13px; color: var(--mi-text-dim); }
.hint { font-size: 12px; color: var(--mi-text-dim); }
.imgs { display: flex; gap: 10px; flex-wrap: wrap; }
.thumb { position: relative; width: 84px; text-align: center; }
.thumb-img { width: 84px; height: 84px; border-radius: 10px; border: 1px solid var(--mi-border); }
.rm { position: absolute; top: -6px; right: -4px; width: 20px; height: 20px; border-radius: 50%; border: none; background: var(--mi-primary); color: #1b1206; cursor: pointer; line-height: 18px; }
.dim { display: block; font-size: 11px; color: var(--mi-text-dim); }
.result { margin-top: 4px; }
.res-tip { margin: 6px 0 0; font-size: 13px; line-height: 1.7; }
.res-extra { margin: 4px 0 0; font-size: 12px; color: var(--mi-text-dim); }
</style>
