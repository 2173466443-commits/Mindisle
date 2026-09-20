<template>
  <el-alert v-if="visible" :type="tone" show-icon :closable="false" class="stage">
    <template #title>
      <span class="t">{{ headline }}</span>
    </template>
    <div class="b">
      <p class="line">{{ detail }}</p>
      <p v-if="traceId" class="trace">traceId {{ traceId }}</p>
      <p v-if="hint" class="hint">{{ hint }}</p>
    </div>
  </el-alert>
</template>

<script setup>
import { computed } from 'vue'
import { CODE, textOf } from '@/api/errorCode'

// 统一表达「这个功能为什么没有数据」：后端码 -> 人话。手册 §5.8 第 1 条禁止静默失败。
const props = defineProps({
  code: { type: [Number, String], default: null },
  stage: { type: String, default: '' },
  apiName: { type: String, default: '' },
  traceId: { type: String, default: '' },
  extra: { type: String, default: '' }
})

const DB_HINT = '本地数据库还没建：执行 powershell -NoProfile -ExecutionPolicy Bypass -File docs/init-db.ps1，再把口令写进 backend/.env 的 DB_PASSWORD 并重启后端。'

const visible = computed(() => props.code !== null && props.code !== undefined && props.code !== '')
const tone = computed(() => (Number(props.code) === CODE.NOT_IMPLEMENTED ? 'warning' : 'error'))
const headline = computed(() => {
  if (Number(props.code) === CODE.NOT_IMPLEMENTED) return '阶段 ' + (props.stage || '?') + ' 未实现'
  return textOf(props.code)
})
const detail = computed(() => {
  if (props.extra) return props.extra
  if (Number(props.code) === CODE.NOT_IMPLEMENTED) {
    return (props.apiName || '该接口') + ' 后端当前直接返回 90001，界面上不会展示内容；这是按排期尚未开工，不是故障。'
  }
  return textOf(props.code)
})
const hint = computed(() => (Number(props.code) === CODE.DB_UNAVAILABLE ? DB_HINT : ''))
</script>

<style scoped>
.stage { margin: 12px 0; }
.t { font-weight: 700; }
.b .line { margin: 6px 0 0; font-size: 13px; line-height: 1.8; }
.b .trace { margin: 4px 0 0; font-size: 12px; color: var(--mi-text-dim); }
.b .hint { margin: 6px 0 0; font-size: 12px; color: var(--mi-mist); }
</style>
