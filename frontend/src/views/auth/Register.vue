<template>
  <div class="auth">
    <div class="auth-card mi-card">
      <div class="brand">
        <span class="logo">🏝</span>
        <div>
          <h1>加入心屿</h1>
          <p class="slogan">一个可以先说说话的地方</p>
        </div>
      </div>

      <el-form :model="form" label-position="top" size="default" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="form.username" maxlength="32" clearable
                    placeholder="4-32 位字母、数字或下划线" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" maxlength="64" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirm" type="password" maxlength="64" show-password
                    placeholder="再输入一次" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" maxlength="32" clearable placeholder="不填则默认与用户名相同" />
        </el-form-item>
        <div class="row">
          <el-form-item label="年级">
            <el-select v-model="form.grade" placeholder="可选" clearable class="w100">
              <el-option v-for="g in GRADES" :key="g.value" :label="g.label" :value="g.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="学校">
            <el-input v-model="form.school" maxlength="64" placeholder="可选" />
          </el-form-item>
        </div>
        <el-form-item label="图形验证码">
          <div class="cap">
            <el-input v-model="form.captchaCode" maxlength="8" placeholder="看不清可点图片刷新" @keyup.enter="submit" />
            <img v-if="cap.img" :src="cap.img" class="cap-img" alt="图形验证码" title="点击刷新" @click="loadCaptcha" />
            <el-button v-else class="cap-img cap-btn" :loading="cap.loading" @click="loadCaptcha">获取验证码</el-button>
          </div>
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="form.agreeTerms">我已阅读并同意《用户协议》</el-checkbox>
          <el-checkbox v-model="form.agreePrivacy">我已阅读并同意《隐私政策》</el-checkbox>
          <el-checkbox v-model="form.agreeSensitive">单独同意处理我的敏感个人信息（年级、学校等，可不勾）</el-checkbox>
        </el-form-item>

        <el-button type="primary" class="submit" :loading="loading" @click="submit">注册并登录</el-button>
      </el-form>

      <div class="foot">
        <router-link to="/login">已有账号？去登录</router-link>
        <router-link to="/help" class="sos">现在就需要帮助 →</router-link>
      </div>

      <el-alert v-if="notice" :title="notice" :type="noticeType" show-icon :closable="false" class="notice" />
      <stage-notice :code="hardCode" />
      <p class="trace" v-if="traceId">traceId {{ traceId }}</p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCaptcha, register } from '@/api/auth'
import { configs } from '@/api/system'
import { CODE } from '@/api/errorCode'
import { useUserStore } from '@/stores/user'
import StageNotice from '@/components/StageNotice.vue'

// grade 取值必须与 sql/01_account.sql 的 user_profile.grade ENUM 逐字一致
const GRADES = [
  { value: 'FRESH', label: '大一' },
  { value: 'SOPH', label: '大二' },
  { value: 'JUNIOR', label: '大三' },
  { value: 'SENIOR', label: '大四' },
  { value: 'OTHER', label: '其他（研究生/已毕业等）' }
]

const form = reactive({
  username: '', password: '', confirm: '', nickname: '',
  grade: '', school: '', captchaId: '', captchaCode: '',
  agreeTerms: false, agreePrivacy: false, agreeSensitive: false
})
const cap = reactive({ img: '', loading: false })
const loading = ref(false)
const notice = ref('')
const noticeType = ref('warning')
const hardCode = ref(null)
const traceId = ref('')
const consentVersion = ref('')

const router = useRouter()
const user = useUserStore()

async function loadCaptcha() {
  cap.loading = true
  try {
    const data = await getCaptcha()
    cap.img = 'data:image/png;base64,' + data.imageBase64
    form.captchaId = data.captchaId
    form.captchaCode = ''
  } catch (e) {
    cap.img = ''
    hardCode.value = e.code || null
    traceId.value = e.traceId || ''
  } finally {
    cap.loading = false
  }
}

function soft(msg, type) {
  notice.value = msg
  noticeType.value = type || 'warning'
}

async function submit() {
  hardCode.value = null
  traceId.value = ''
  if (!/^[A-Za-z0-9_]{4,32}$/.test(form.username)) { soft('用户名只能为 4-32 位字母、数字或下划线'); return }
  if (form.password.length < 8 || form.password.length > 64) { soft('密码长度需在 8-64 位之间'); return }
  if (form.password !== form.confirm) { soft('两次输入的密码不一致'); return }
  if (!form.captchaCode) { soft('请填写图形验证码'); return }
  if (!form.agreeTerms || !form.agreePrivacy) { soft('注册前必须同意《用户协议》与《隐私政策》'); return }

  loading.value = true
  soft('')
  try {
    const data = await register({
      username: form.username,
      password: form.password,
      nickname: form.nickname || null,
      captchaId: form.captchaId,
      captchaCode: form.captchaCode,
      agreeTerms: form.agreeTerms,
      agreePrivacy: form.agreePrivacy,
      agreeSensitive: form.agreeSensitive,
      consentVersion: consentVersion.value || null,
      grade: form.grade || null,
      school: form.school || null,
      regSource: 'web-register'
    })
    user.setToken(data.accessToken, data.refreshToken)
    user.setProfile(data.user)
    user.setConsent({ privacy: true, sensitive: !!form.agreeSensitive })
    router.replace('/feed')
  } catch (e) {
    traceId.value = e.traceId || ''
    if (e.code === CODE.USERNAME_TAKEN) {
      soft('用户名已被占用，换一个试试'); loadCaptcha()
    } else if (e.code === CODE.CAPTCHA_INVALID || e.code === CODE.CAPTCHA_EXPIRED) {
      soft(e.message + '，已为你刷新验证码'); loadCaptcha()
    } else if (e.code === CODE.PARAM_INVALID || e.code === CODE.PRIVACY_CONSENT_REQUIRED) {
      soft(e.message || '请检查填写内容')
    } else {
      hardCode.value = e.code || null
      if (!e.code) soft('注册请求未能送达后端，请确认 8080 已启动', 'error')
    }
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  loadCaptcha()
  try {
    const rows = await configs('prompt.version', { silent: true })
    if (Array.isArray(rows) && rows.length) consentVersion.value = String(rows[0].value || '')
  } catch (e) {
    // 读不到协议版本不阻断注册：后端会按 sys_config 的兜底版本号落库
  }
})
</script>

<style scoped>
.auth { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 24px; background: radial-gradient(1000px 520px at 50% -10%, #1b2a4a 0%, var(--mi-bg) 62%); }
.auth-card { width: 480px; max-width: 100%; }
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.logo { font-size: 32px; }
h1 { margin: 0; font-size: 22px; letter-spacing: 3px; color: var(--mi-primary); }
.slogan { margin: 4px 0 0; font-size: 12px; color: var(--mi-text-dim); }
.row { display: flex; gap: 14px; }
.row > :deep(.el-form-item) { flex: 1; }
.w100 { width: 100%; }
.cap { display: flex; gap: 10px; width: 100%; }
.cap-img { width: 120px; height: 38px; border-radius: 6px; border: 1px solid var(--mi-border); cursor: pointer; object-fit: cover; background: #fff; }
.cap-btn { font-size: 12px; color: #333; }
.submit { width: 100%; margin-top: 4px; }
.foot { display: flex; justify-content: space-between; margin-top: 14px; font-size: 13px; }
.sos { color: var(--mi-anger); }
.notice { margin-top: 12px; }
.trace { font-size: 12px; color: var(--mi-text-dim); }
</style>
