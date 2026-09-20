<template>
  <div class="wrap">
    <div class="card mi-card">
      <div class="brand">
        <span class="logo">🏝</span>
        <div>
          <h1>心屿管理端 <span class="en">MindIsle Admin</span></h1>
          <p class="slogan">值班审核 · 运营看板 · 参数配置</p>
        </div>
      </div>

      <el-form :model="form" label-position="top" size="large" @submit.prevent>
        <el-form-item label="管理员账号">
          <el-input v-model="form.username" maxlength="32" clearable placeholder="需为 ADMIN / SUPER 角色" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" maxlength="64" show-password
                    placeholder="密码" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item label="图形验证码">
          <div class="cap">
            <el-input v-model="form.captchaCode" maxlength="8" placeholder="点图片可刷新" @keyup.enter="submit" />
            <img v-if="cap.img" :src="cap.img" class="cap-img" alt="图形验证码" title="点击刷新" @click="loadCaptcha" />
            <el-button v-else class="cap-img cap-btn" :loading="cap.loading" @click="loadCaptcha">获取验证码</el-button>
          </div>
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="submit">进入后台</el-button>
      </el-form>

      <el-alert v-if="notice" :title="notice" :type="noticeType" show-icon :closable="false" class="notice" />
      <p class="tip">说明：管理端与用户端共用同一张 user 表与同一套登录锁定策略，权限唯一来源是 user.role，
        不存在「第二套后台口令」。后端接口为 POST /api/admin/auth/login。</p>
    </div>
    <p class="sysline">{{ sysLine }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminLogin, getCaptcha } from '@/api/auth'
import { systemInfo } from '@/api/system'
import { CODE } from '@/api/errorCode'
import { useAdminUserStore } from '@/stores/adminUser'

const form = reactive({ username: '', password: '', captchaId: '', captchaCode: '' })
const cap = reactive({ img: '', loading: false })
const loading = ref(false)
const notice = ref('')
const noticeType = ref('warning')
const sys = ref(null)
const backendUp = ref(true)

const route = useRoute()
const router = useRouter()
const admin = useAdminUserStore()

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
    backendUp.value = true
  } catch (e) {
    cap.img = ''
    backendUp.value = false
    noticeType.value = 'error'
    notice.value = '验证码加载失败：' + (e.message || '未知错误') + '（请确认后端 8080 已启动）'
  } finally {
    cap.loading = false
  }
}

async function submit() {
  if (!form.username || !form.password) { notice.value = '请填写账号与密码'; noticeType.value = 'warning'; return }
  if (!form.captchaCode) { notice.value = '请填写图形验证码'; noticeType.value = 'warning'; return }
  loading.value = true
  notice.value = ''
  try {
    const data = await adminLogin({
      username: form.username,
      password: form.password,
      captchaId: form.captchaId,
      captchaCode: form.captchaCode
    })
    admin.setToken(data.accessToken, data.refreshToken, data.expiresIn)
    admin.setProfile(data.user)
    router.replace(route.query.redirect || '/dashboard')
  } catch (e) {
    noticeType.value = 'error'
    if (e.code === CODE.FORBIDDEN) {
      notice.value = '该账号不是管理员（后端 10003）：user.role 需为 ADMIN 或 SUPER，请换管理员账号或让超级管理员在 user 表里授权'
    } else if (e.code === CODE.CAPTCHA_INVALID || e.code === CODE.CAPTCHA_EXPIRED) {
      notice.value = e.message + '，已为你刷新验证码'
      loadCaptcha()
    } else if (e.code === CODE.DB_UNAVAILABLE) {
      notice.value = '后端已启动但数据库尚未初始化：执行 docs/init-db.ps1 建库，把口令写入 backend/.env 的 DB_PASSWORD 后重启后端'
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

onMounted(() => {
  if (route.query.reason === 'not-admin') {
    noticeType.value = 'warning'
    notice.value = '当前令牌对应的账号不是管理员，已退回登录页'
  }
  loadCaptcha()
  systemInfo().then((d) => { sys.value = d; backendUp.value = true }).catch(() => { backendUp.value = false })
})
</script>

<style scoped>
.wrap { min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; padding: 24px; background: radial-gradient(1000px 520px at 50% -10%, #1b2a4a 0%, var(--mi-bg) 62%); }
.card { width: 430px; max-width: 100%; }
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.logo { font-size: 32px; }
h1 { margin: 0; font-size: 22px; letter-spacing: 2px; color: var(--mi-primary); }
.en { font-size: 12px; color: var(--mi-text-dim); letter-spacing: 1px; }
.slogan { margin: 4px 0 0; font-size: 12px; color: var(--mi-text-dim); }
.cap { display: flex; gap: 10px; width: 100%; }
.cap-img { width: 120px; height: 40px; border-radius: 6px; border: 1px solid var(--mi-border); cursor: pointer; object-fit: cover; background: #fff; }
.cap-btn { font-size: 12px; color: #333; }
.submit { width: 100%; margin-top: 6px; }
.notice { margin-top: 14px; }
.tip { margin: 14px 0 0; font-size: 11px; line-height: 1.8; color: var(--mi-text-dim); }
.sysline { font-size: 12px; color: var(--mi-text-dim); margin: 0; }
</style>
