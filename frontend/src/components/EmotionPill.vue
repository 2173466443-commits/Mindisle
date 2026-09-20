<template>
  <span class="pill" :style="style">{{ label }}</span>
</template>

<script setup>
import { computed } from 'vue'

// 七色情绪标签（与后端 emotion_label / theme.css 色板一一对应，不在组件里另写一套颜色值）。
const EMOTION = {
  joy: ['喜悦', 'var(--mi-joy)'],
  trust: ['信任', 'var(--mi-trust)'],
  anger: ['愤怒', 'var(--mi-anger)'],
  sadness: ['难过', 'var(--mi-sadness)'],
  fear: ['恐惧', 'var(--mi-fear)'],
  disgust: ['厌恶', 'var(--mi-disgust)'],
  neutral: ['平静', 'var(--mi-neutral)']
}

const props = defineProps({ value: { type: String, default: 'neutral' } })
const zh = computed(() => (EMOTION[props.value] ? EMOTION[props.value][0] : props.value || '未记录'))
const color = computed(() => (EMOTION[props.value] ? EMOTION[props.value][1] : 'var(--mi-neutral)'))
const label = computed(() => zh.value)
const style = computed(() => ({ borderColor: color.value, color: color.value }))
</script>

<style scoped>
.pill {
  display: inline-block; padding: 1px 10px; border-radius: 999px;
  border: 1px solid currentColor; font-size: 12px; line-height: 20px;
}
</style>
