
<template>
  <div class="emo">
    <section class="mi-card">
      <div class="sec-head">
        <h2>今天感觉怎么样</h2>
        <span class="dim">每天一次打卡，情绪档案从阶段 3 开始累积</span>
      </div>
      <div class="picks">
        <button v-for="e in EMOTIONS" :key="e.value" type="button"
                class="pick" :class="{ on: form.emotion === e.value }" @click="form.emotion = e.value">
          <span class="face">{{ e.face }}</span>
          <emotion-pill :value="e.value" />
          <span class="val">valence {{ e.valence.toFixed(2) }}</span>
        </button>
      </div>

      <div class="row">
        <span class="lab">强度</span>
        <el-slider v-model="form.intensity" :min="1" :max="5" :step="1" show-stops class="slider" />
        <b class="num">{{ form.intensity }}</b>
      </div>
      <div class="row">
        <span class="lab">睡得怎么样</span>
        <el-radio-group v-model="form.sleepBucket">
          <el-radio-button v-for="h in SLEEPS" :key="h.value" :value="h.value">{{ h.label }}</el-radio-button>
        </el-radio-group>
      </div>
      <el-input v-model="form.note" type="textarea" :rows="2" maxlength="200" show-word-limit
                placeholder="一句话就好，写给未来的自己（可选，不会公开展示）" />
      <el-button type="primary" class="submit" :loading="busy" @click="submit">完成打卡</el-button>
      <stage-notice :code="code" stage="3" api-name="POST /api/emotions/checkin"
                    extra="情绪打卡要先落 emotion_checkin 表并驱动词典 + LLM 级联识别（阶段 3 起）；目前后端还没有 /api/emotions/** 任何路由，所以这里必然是 90001。" />
    </section>

    <section class="mi-card">
      <div class="sec-head">
        <h2>近 7 天情绪曲线</h2>
        <el-tag size="small" type="info">图表排在阶段 3（ECharts 6.1.0 已装）</el-tag>
      </div>
      <ul v-if="recent.length" class="log">
        <li v-for="r in recent" :key="r.id">
          <span class="dim">{{ r.createdAt }}</span>
          <emotion-pill :value="r.emotionLabel || r.emotion" />
          <span>强度 {{ r.intensity }}</span>
        </li>
      </ul>
      <el-empty v-else description="暂无打卡记录" />
      <stage-notice v-if="listCode" :code="listCode" stage="3" api-name="GET /api/emotions/checkins" />
    </section>

    <section class="mi-card">
      <h2>这份情绪数据会被怎么用（答辩口径）</h2>
      <ol class="steps">
        <li>打卡文本先过<strong>混合式中文情绪识别</strong>：情绪词典出价，置信度 &lt; 0.55 或命中风险词才升级送 LLM（创新点 1）。</li>
        <li>结果写入 emotion_checkin / user_emotion_profile，形成 valence_now(u)。</li>
        <li>心情为负向时启用<strong>情绪感知加权协同过滤</strong>：emotion_match(u, i) = 1 − | valence_now(u) − comfort_valence(i) |（创新点 2）。</li>
        <li>连续负向或命中危机词触发 <strong>L0–L3 分级响应</strong>，置顶 12356 求助卡片（创新点 3）。</li>
      </ol>
      <p class="dim">心屿只做陪伴与倾诉，不做医学诊断；风险判断按「宁可误报不可漏报」的代价敏感策略处理。</p>
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import http from '@/api/http'
import EmotionPill from '@/components/EmotionPill.vue'
import StageNotice from '@/components/StageNotice.vue'

// valence 取值对齐需求 §1.5 创新点 2 的 comfort_valence 口径：正向 0~1、负向 -1~0。
const EMOTIONS = [
  { value: 'joy', face: '🙂', valence: 0.8 },
  { value: 'trust', face: '😌', valence: 0.5 },
  { value: 'neutral', face: '😐', valence: 0.0 },
  { value: 'sadness', face: '😔', valence: -0.6 },
  { value: 'fear', face: '😨', valence: -0.6 },
  { value: 'anger', face: '😠', valence: -0.6 },
  { value: 'disgust', face: '🙁', valence: -0.4 }
]
const SLEEPS = [
  { value: 0, label: '不足 4h' },
  { value: 1, label: '4-6h' },
  { value: 2, label: '6-8h' },
  { value: 3, label: '8h 以上' }
]

const form = reactive({ emotion: 'neutral', intensity: 3, sleepBucket: 2, note: '' })
const busy = ref(false)
const code = ref(null)
const listCode = ref(null)
const recent = ref([])

async function submit() {
  busy.value = true
  code.value = null
  try {
    // 后端阶段 3 才提供该路由。这里选择真的去调一次，让「未实现」以真实错误码呈现，而不是前端假装成功。
    await http.post('/emotions/checkin', {
      emotion: form.emotion,
      intensity: form.intensity,
      sleepBucket: form.sleepBucket,
      note: form.note
    })
    recent.value.unshift({ id: Date.now(), emotion: form.emotion, intensity: form.intensity, createdAt: '刚刚' })
    form.note = ''
  } catch (e) {
    code.value = e.code || 'network'
  } finally {
    busy.value = false
  }
}

async function loadRecent() {
  listCode.value = null
  try {
    const data = await http.get('/emotions/checkins', { params: { days: 7 }, silent: true })
    recent.value = Array.isArray(data) ? data : []
  } catch (e) {
    listCode.value = e.code || 'network'
    recent.value = []
  }
}

onMounted(loadRecent)
</script>

<style scoped>
.emo { display: flex; flex-direction: column; gap: 18px; }
.sec-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }
h2 { margin: 0 0 10px; font-size: 16px; color: var(--mi-mist); letter-spacing: 1px; }
.dim { font-size: 12px; color: var(--mi-text-dim); }
.picks { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px; margin-bottom: 16px; }
.pick { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 12px 6px; cursor: pointer;
        background: rgba(127, 167, 196, 0.06); border: 1px solid var(--mi-border); border-radius: 12px; color: var(--mi-text-dim); }
.pick.on { border-color: var(--mi-primary); background: rgba(240, 135, 107, 0.12); color: var(--mi-text); }
.face { font-size: 26px; }
.val { font-size: 11px; font-family: Consolas, monospace; }
.row { display: flex; align-items: center; gap: 14px; margin: 10px 0; flex-wrap: wrap; }
.lab { width: 96px; font-size: 13px; color: var(--mi-text-dim); flex: none; }
.slider { flex: 1; min-width: 220px; }
.num { width: 20px; text-align: center; }
.submit { margin-top: 14px; }
.log { list-style: none; margin: 0; padding: 0; font-size: 13px; }
.log li { display: flex; gap: 14px; align-items: center; padding: 6px 0; border-bottom: 1px dashed var(--mi-border); }
.steps { margin: 0; padding-left: 20px; line-height: 2; font-size: 13px; }
</style>
