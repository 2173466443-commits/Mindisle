<template>
  <div class="crisis" :class="'lv-' + (level || 'card')">
    <div class="row">
      <span class="ico">🫂</span>
      <div class="txt">
        <p class="t">{{ text }}</p>
        <p class="d">
          24 小时免费；也可以直接拨打 120 / 110。心屿不提供医学诊断，但有人在意你现在说的话。
        </p>
      </div>
      <a class="tel" :href="'tel:' + hotline">{{ hotline }}</a>
    </div>
  </div>
</template>

<script setup>
// 危机求助卡片（创新点 3 的前端出口）。
// 为什么单独抽一个组件：后端在三处给出同一个 hotline 字段——发帖响应、列表项、详情——
// 三处的处置语义都是「这条内容背后的人可能需要真人」，展示必须一模一样，
// 复制三份样式的地方迟早只改得到一份。
defineProps({
  hotline: { type: String, required: true },
  text: {
    type: String,
    default: '如果你现在很难受，可以马上和专业的人聊聊：全国心理援助热线'
  },
  // card=详情页与发布结果；inline=列表卡片里的一行式，占位要小
  level: { type: String, default: 'card' }
})
</script>

<style scoped>
.crisis { border: 1px solid var(--mi-primary-line); background: var(--mi-primary-soft); border-radius: 12px; padding: 10px 12px; margin-top: 10px; }
.row { display: flex; align-items: flex-start; gap: 10px; }
.ico { font-size: 18px; line-height: 1.4; }
.txt { flex: 1; min-width: 0; }
.t { margin: 0; font-size: 13px; font-weight: 700; color: var(--mi-primary); }
.d { margin: 4px 0 0; font-size: 12px; line-height: 1.7; color: var(--mi-text-dim); }
.tel { flex: none; display: inline-block; padding: 6px 12px; border-radius: 999px; background: var(--mi-primary); color: var(--mi-on-primary); font-weight: 700; text-decoration: none; font-size: 14px; letter-spacing: 1px; }
.lv-inline .d { display: none; }
.lv-inline { padding: 8px 10px; }
.lv-inline .tel { padding: 4px 10px; font-size: 13px; }
</style>
