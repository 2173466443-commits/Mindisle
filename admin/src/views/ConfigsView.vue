<template>
  <div>
    <h2 class="h">系统参数配置 <el-tag size="small" type="warning" effect="plain">FR5 · 阶段 5</el-tag></h2>

    <div class="mi-card">
      <div class="row">
        <span class="t">公开可读参数（GET /api/system/configs · 白名单四项）</span>
        <el-button size="small" text :loading="busy" @click="loadPublic">重新读取</el-button>
      </div>
      <el-table :data="rows" size="small" empty-text="读不到：数据库未建时返回 90002 属预期">
        <el-table-column prop="key" label="cfg_key" width="230" />
        <el-table-column prop="value" label="cfg_value" />
        <el-table-column prop="valueType" label="value_type" width="120" />
      </el-table>
      <p v-if="publicErr" class="dim">读取失败：{{ publicErr }}</p>
      <p class="dim">白名单固定为 prompt.version / prompt.crisis_card / audit.wordlib_version / ai.model，
        传 keys 也只取交集；risk.* 这类阈值参数刻意不在公开清单内，管理端要改必须走鉴权接口。</p>
    </div>

    <StageNotice :code="code" stage="5" api-name="GET /api/admin/configs"
                 extra="参数增删改（含 sys_config 写回、修改留痕、生效版本号自增）在阶段 5 施工，当前后端直接抛 90001。" />

    <div class="mi-card form">
      <div class="t">拟议表单（阶段 5 生效，现在提交会被后端拒绝，故禁用）</div>
      <el-form :model="form" label-width="150px" size="small" disabled>
        <el-form-item label="参数键 cfg_key">
          <el-input v-model="form.key" placeholder="例如 risk.llm_timeout_ms" />
        </el-form-item>
        <el-form-item label="参数值 cfg_value">
          <el-input v-model="form.value" type="textarea" :rows="3" placeholder="按 value_type 校验后写 sys_config" />
        </el-form-item>
        <el-form-item label="值类型 value_type">
          <el-select v-model="form.valueType" style="width: 160px">
            <el-option v-for="v in TYPES" :key="v" :label="v" :value="v" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-tag type="info" size="small">提交按钮在阶段 5 前不可用：不接后端就不做写操作</el-tag>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import StageNotice from '@/components/StageNotice.vue'
import { adminConfigs, publicConfigs } from '@/api/admin'

const TYPES = ['STRING', 'INT', 'JSON', 'BOOLEAN']
const rows = ref([])
const code = ref(null)
const busy = ref(false)
const publicErr = ref('')
const form = reactive({ key: '', value: '', valueType: 'STRING' })

async function loadPublic() {
  busy.value = true
  publicErr.value = ''
  try {
    const data = await publicConfigs()
    rows.value = Array.isArray(data) ? data : []
  } catch (e) {
    rows.value = []
    publicErr.value = (e.code ? e.code + ' · ' : '') + (e.message || String(e))
  } finally {
    busy.value = false
  }
}

async function loadAdmin() {
  code.value = null
  try {
    await adminConfigs()
    rows.value = []
  } catch (e) {
    code.value = e.code || 'network'
  }
}

onMounted(() => { loadPublic(); loadAdmin() })
</script>

<style scoped>
.h { margin: 0 0 14px; font-size: 18px; }
.row { display: flex; align-items: center; justify-content: space-between; }
.t { font-size: 14px; font-weight: 700; margin-bottom: 10px; }
.dim { font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); margin: 8px 0 0; }
.form { margin-top: 14px; }
</style>
