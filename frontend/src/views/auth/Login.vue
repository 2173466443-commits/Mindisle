<template>
  <div class="auth">
    <div class="auth-card mi-card">
      <div class="brand">
        <span class="logo">🏝</span>
        <div>
          <h1>心屿 <span class="en">MindIsle</span></h1>
          <p class="slogan">校园心理陪伴社区 · 先照顾好自己，再谈其他</p>
        </div>
      </div>

      <el-form :model="form" label-position="top" size="large" @submit.prevent>
        <el-form-item label="用户名">
          <el-input v-model="form.username" maxlength="32" clearable placeholder="你的用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" maxlength="64" show-password
                    placeholder="密码" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item label="图形验证码">
          <div class="cap">
            <el-input v-model="form.captchaCode" maxlength="8" placeholder="看不清可点图片刷新" @keyup.enter="submit" />
            <img v-if="cap.img" :src="cap.img" class="cap-img" alt="图形验证码" title="点击刷新" @click="loadCaptcha" />
            <el-button v-else class="cap-img cap-btn" :loading="cap.loading" @click="loadCaptcha">获取验证码</el-button>
          </div>
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="submit">登录</el-button>
      </el-form>

      <div class="foot">
        <router-link to="/register">还没有账号？注册</router-link>
        <router-link to="/help" class="sos">现在就需要帮助 →</router-link>
      </div>

      <el-alert v-if="notice" :title="notice" type="warning" show-icon :closable="false" class="notice" />
      <el-alert v-if="!backendUp" title="后端未连接：请先启动 backend（mvn spring-boot:run，端口 8080）"
                type="error" show-icon :closable="false" class="notice" />
    </div>
    <p class="sysline">{{ sysLine }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCaptcha, login } from '@/api/auth'
import { systemInfo } from '@/api/system'
import { CODE } from '@/api/errorCode'
import { useUserStore } from '@/stores/user'

const form = reactive({ username: '', password: '', captchaId: '', captchaCode: '' })
const cap = reactive({ img: '', loading: false })
const loading = ref(false)
const notice = ref('')
const backendUp = ref(true)
const sys = ref(null)

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const sysLine = computed(() => {
  if (!sys.value) return ''
  return sys.value.app + ' v' + sys.value.version + ' · ' + sys.value.profiles +
    ' · cache=' + sys.value.cacheMode + ' · llm=' + sys.value.llmProvider
})

async function loadCaptcha() {
  cap.loading = true
  try {
    const data = await getCaptcha()
    cap.img = 'data:image/png;base64,' + data.imageBase64
    form.captchaId = data.captchaId
    form.captchaCode = ''
    notice.value = ''
  } catch (e) {
    cap.img = ''
    backendUp.value = false
    notice.value = '验证码加载失败：' + (e.message || '未知错误')
  } finally {
    cap.loading = false
  }
}

async function submit() {
  if (!form.username || !form.password) { notice.value = '请填写用户名与密码'; return }
  if (!form.captchaCode) { notice.value = '请填写图形验证码'; return }
  loading.value = true
  notice.value = ''
  try {
    const data = await login({
      username: form.username,
      password: form.password,
      captchaId: form.captchaId,
      captchaCode: form.captchaCode
    })
    user.setToken(data.accessToken, data.refreshToken)
    user.setProfile(data.user)
    router.replace(route.query.redirect || '/feed')
  } catch (e) {
    backendUp.value = true
    if (e.code === CODE.CAPTCHA_INVALID || e.code === CODE.CAPTCHA_EXPIRED) {
      notice.value = e.message + '，已为你刷新验证码'
      loadCaptcha()
    } else if (e.code === CODE.DB_UNAVAILABLE) {
      notice.value = '后端已启动但数据库尚未初始化：请执行 docs/init-db.ps1 建库，再把口令写入 backend/.env 的 DB_PASSWORD 并重启后端'
    } else if (e.code === CODE.LOGIN_LOCKED || e.code === CODE.RATE_LIMITED) {
      notice.value = e.message
      loadCaptcha()
    } else {
      notice.value = e.message || '登录失败'
      if (e.code === CODE.LOGIN_FAILED) loadCaptcha()
    }
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  loadCaptcha()
  try {
    sys.value = await systemInfo()
    backendUp.value = true
  } catch (e) {
    backendUp.value = false
  }
})
</script>

<style scoped>
.auth { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; padding: 24px; background: radial-gradient(1000px 520px at 50% -10%, #1b2a4a 0%, var(--mi-bg) 62%); }
.auth-card { width: 420px; max-width: 100%; }
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.logo { font-size: 34px; }
h1 { margin: 0; font-size: 26px; letter-spacing: 3px; color: var(--mi-primary); }
.en { font-size: 13px; color: var(--mi-text-dim); letter-spacing: 1px; }
.slogan { margin: 4px 0 0; font-size: 12px; color: var(--mi-text-dim); }
.cap { display: flex; gap: 10px; width: 100%; }
.cap-img { width: 120px; height: 40px; border-radius: 6px; border: 1px solid var(--mi-border); cursor: pointer; object-fit: cover; background: #fff; }
.cap-btn { font-size: 12px; color: #333; }
.submit { width: 100%; margin-top: 6px; }
.foot { display: flex; justify-content: space-between; margin-top: 14px; font-size: 13px; }
.sos { color: var(--mi-anger); }
.notice { margin-top: 14px; }
.sysline { font-size: 12px; color: var(--mi-text-dim); margin: 0; }
</style>
