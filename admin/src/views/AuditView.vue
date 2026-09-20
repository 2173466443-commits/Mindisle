<template>
  <div>
    <h2 class="h">内容审核队列 <el-tag size="small" type="warning" effect="plain">FR4 / FR7 · 阶段 5</el-tag></h2>

    <div class="mi-card bar">
      <el-form :inline="true" size="small" @submit.prevent>
        <el-form-item label="状态">
          <el-select v-model="q.status" style="width: 140px">
            <el-option v-for="s in STATUS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select v-model="q.riskLevel" style="width: 120px">
            <el-option v-for="r in LEVEL" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
        <el-form-item label="送审类型">
          <el-select v-model="q.targetType" style="width: 140px">
            <el-option v-for="t in TARGET" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="load">查询</el-button>
        </el-form-item>
      </el-form>
      <p class="rule">
        排序口径按需求 §5.2：L3 优先、其次 sla_at 升序；超时（now &gt; sla_at）在管理端标红。
        处置时限来自 audit_task.sla_at 注释：L2 = +4h、L3 = +30min。审核结论一律写 audit_record 留痕（FR7.7）。
      </p>
    </div>

    <StageNotice :code="code" stage="5" api-name="GET /api/admin/audit/tasks"
                 extra="队列数据来自 audit_task 表（07_audit.sql 已建表），查询与写回在阶段 5 施工；这张表不会用假任务填充。" />

    <el-table :data="rows" size="small" class="mi-card tbl" empty-text="暂无任务（后端未返回数据时不伪造）">
      <el-table-column prop="id" label="#" width="70" />
      <el-table-column prop="targetType" label="送审对象" width="120" />
      <el-table-column prop="source" label="来源" width="100" />
      <el-table-column prop="channel" label="通道" width="90" />
      <el-table-column prop="riskLevel" label="等级" width="80" />
      <el-table-column prop="riskScore" label="风险分" width="90" />
      <el-table-column prop="result" label="机审结论" width="120" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column prop="slaAt" label="处置时限" width="170" />
      <el-table-column prop="createdAt" label="创建时间" min-width="170" />
    </el-table>

    <div class="mi-card note">
      <div class="nt">词库与分级现状</div>
      <ul>
        <li>词库版本：<b>{{ wordlib }}</b>（取自 GET /api/system/configs 白名单键 audit.wordlib_version）</li>
        <li>危机卡片文案版本：<b>{{ promptVer }}</b>（键 prompt.version）；危机热线卡片走 GET /api/system/hotline，读库失败时后端返回内置兜底并标 degraded=true</li>
        <li>敏感词命中明细只在管理端展示，用户端不返回命中词与规则编号（需求 §9 规范 4）</li>
        <li>DFA 机审 + LLM 语义复检 + 图像审核三通道在阶段 4/5 接入；当前后端仅有表结构</li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import StageNotice from '@/components/StageNotice.vue'
import { auditTasks, publicConfigs } from '@/api/admin'
import { hotline } from '@/api/system'

const STATUS = ['PENDING', 'PROCESSING', 'PASSED', 'REJECTED', 'ESCALATED']
const LEVEL = ['L0', 'L1', 'L2', 'L3']
const TARGET = ['post', 'comment', 'pm', 'hole', 'ai_reply', 'image']

const q = reactive({ status: 'PENDING', riskLevel: '', targetType: '' })
const rows = ref([])
const code = ref(null)
const busy = ref(false)
const wordlib = ref('读取中…')
const promptVer = ref('读取中…')

async function load() {
  busy.value = true
  code.value = null
  try {
    const params = {}
    if (q.status) params.status = q.status
    if (q.riskLevel) params.riskLevel = q.riskLevel
    if (q.targetType) params.targetType = q.targetType
    const data = await auditTasks(params)
    rows.value = Array.isArray(data) ? data : (data && Array.isArray(data.records) ? data.records : [])
  } catch (e) {
    rows.value = []
    code.value = e.code || 'network'
  } finally {
    busy.value = false
  }
}

// 后端 /api/system/configs 返回的是数组 [{key,value,valueType}]，必须 find 不能按对象取键。
async function loadCfg() {
  try {
    const data = await publicConfigs('audit.wordlib_version,prompt.version,ai.model')
    const list = Array.isArray(data) ? data : []
    const find = (k) => { const row = list.find((x) => x && x.key === k); return row && row.value ? String(row.value) : '（未返回）' }
    wordlib.value = find('audit.wordlib_version')
    promptVer.value = find('prompt.version')
  } catch (e) {
    wordlib.value = '读取失败：' + (e.code || e.message)
    promptVer.value = '读取失败'
  }
}

onMounted(() => {
  load()
  loadCfg()
  hotline().catch(() => { /* 热线卡片在危机页展示，这里不打断管理端 */ })
})
</script>

<style scoped>
.h { margin: 0 0 14px; font-size: 18px; }
.bar { margin-bottom: 12px; }
.bar :deep(.el-form-item) { margin-bottom: 8px; }
.rule { margin: 0; font-size: 12px; line-height: 1.8; color: var(--mi-text-dim); }
.tbl { margin-top: 12px; padding: 0; }
.note { margin-top: 14px; }
.nt { font-size: 14px; font-weight: 700; margin-bottom: 6px; }
.note ul { margin: 0; padding-left: 18px; font-size: 12px; line-height: 2; color: var(--el-text-color-regular); }
</style>
