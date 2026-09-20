<template>
  <div>
    <h2 class="h">运营看板 <el-tag size="small" type="warning" effect="plain">FR5 · 阶段 5</el-tag></h2>

    <el-row :gutter="14">
      <el-col v-for="m in metrics" :key="m.key" :xs="24" :sm="12" :lg="6">
        <div class="mi-card metric">
          <div class="label">{{ m.label }}</div>
          <div class="value">{{ stat ? stat[m.key] : '—' }}</div>
          <div class="desc">{{ m.desc }}</div>
        </div>
      </el-col>
    </el-row>

    <StageNotice :code="code" stage="5" api-name="GET /api/admin/stats/overview"
                 extra="看板统计依赖 user / post / emotion_record / audit_task 四张表的聚合查询，按排期在阶段 5 施工；当前不会展示任何编造数字。" />

    <div class="mi-card real">
      <div class="rt">
        <span>后端真实可读信息（阶段 2 已实现，非占位）</span>
        <el-button size="small" text :loading="busy" @click="loadAll">重新拉取</el-button>
      </div>
      <el-descriptions v-if="info" :column="2" border size="small">
        <el-descriptions-item label="应用">{{ info.app }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ info.version }}</el-descriptions-item>
        <el-descriptions-item label="Profile">{{ info.profiles }}</el-descriptions-item>
        <el-descriptions-item label="缓存模式">{{ info.cacheMode }}</el-descriptions-item>
        <el-descriptions-item label="LLM 供应商">{{ info.llmProvider }}</el-descriptions-item>
        <el-descriptions-item label="情绪词典">{{ info.wordlibVersion || '未返回' }}</el-descriptions-item>
      </el-descriptions>
      <p v-else class="dim">读不到 /api/system/info：后端可能未启动（8080），或代理未生效。</p>

      <el-table v-if="cfgRows.length" :data="cfgRows" size="small" class="tbl">
        <el-table-column prop="key" label="公开参数键（sys_config 白名单）" width="240" />
        <el-table-column prop="value" label="值" />
        <el-table-column prop="valueType" label="类型" width="110" />
      </el-table>
      <p v-else-if="cfgCode" class="dim">公开参数读取失败：{{ cfgErr }}（数据库未建时预期返回 90002）</p>
    </div>

    <div class="mi-card links">
      <span class="rt">接口自查入口</span>
      <ul>
        <li><a href="/swagger-ui.html" target="_blank" rel="noopener">Swagger UI · /swagger-ui.html</a> —— 当前真实注册的全部端点，前端不得调用不存在的接口</li>
        <li><a href="/v3/api-docs" target="_blank" rel="noopener">OpenAPI JSON · /v3/api-docs</a> —— 20 个 path，含 5 个 Controller</li>
        <li><span class="dim">未接入：/api/emotions/** · /api/posts/** · /api/ai/**（阶段 3/4）</span></li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import StageNotice from '@/components/StageNotice.vue'
import { publicConfigs, statsOverview } from '@/api/admin'
import { systemInfo } from '@/api/system'

const metrics = [
  { key: 'todayUsers', label: '今日活跃用户', desc: '近 24h 有打卡或发帖的去重用户数' },
  { key: 'todayPosts', label: '今日新增树洞', desc: 'post 表当日创建量' },
  { key: 'pendingAudit', label: '待审核任务', desc: 'audit_task 队列 PENDING 条数' },
  { key: 'crisisCases', label: '危机转介次数', desc: 'risk_level = L2/L3 触发求助卡片次数' }
]

const stat = ref(null)
const code = ref(null)
const info = ref(null)
const cfgRows = ref([])
const cfgCode = ref(null)
const cfgErr = ref('')
const busy = ref(false)

async function loadAll() {
  busy.value = true
  code.value = null
  stat.value = null
  try {
    stat.value = await statsOverview()
  } catch (e) {
    code.value = e.code || 'network'
    stat.value = null
  }
  try {
    info.value = await systemInfo()
  } catch (e) {
    info.value = null
  }
  try {
    const data = await publicConfigs()
    cfgRows.value = Array.isArray(data) ? data : []
    cfgCode.value = null
  } catch (e) {
    cfgRows.value = []
    cfgCode.value = e.code || 'network'
    cfgErr.value = e.message || String(e)
  }
  busy.value = false
}

onMounted(loadAll)
</script>

<style scoped>
.h { margin: 0 0 14px; font-size: 18px; }
.metric { height: 100%; }
.metric .label { font-size: 13px; color: var(--mi-text-dim); }
.metric .value { font-size: 30px; font-weight: 700; color: var(--mi-primary); margin: 6px 0; }
.metric .desc { font-size: 11px; color: var(--mi-text-dim); line-height: 1.6; }
.real { margin-top: 14px; }
.rt { display: flex; align-items: center; justify-content: space-between; font-size: 14px; font-weight: 700; margin-bottom: 10px; }
.tbl { margin-top: 12px; }
.links { margin-top: 14px; }
.links ul { margin: 8px 0 0; padding-left: 18px; font-size: 13px; line-height: 2; }
.dim { color: var(--mi-text-dim); font-size: 12px; }
</style>
