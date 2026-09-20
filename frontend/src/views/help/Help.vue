<template>
  <div class="help mi-card">
    <h1>当你现在很难受</h1>
    <p class="lead">你不需要一个人扛着。如果此刻有伤害自己的想法，请立即联系专业渠道：</p>
    <ul class="lines">
      <li><b>全国心理援助热线 12356</b>（24 小时，免费）</li>
      <li><b>北京心理危机研究与干预中心 010-82951332</b></li>
      <li><b>生命热线 400-161-9995</b></li>
      <li>情况紧急请拨打 <b>120 / 110</b></li>
    </ul>
    <p class="tip">心屿是陪伴与倾诉的社区，<b>不是</b>医疗诊断工具，也不能替代危机干预。系统检测到高风险表达时会触发 L0–L3 分级响应并提示上述渠道。</p>
    <router-link to="/login">返回登录</router-link> ·
    <router-link to="/feed">去广场看看</router-link>
  </div>
</template>

<script setup>
// /help 免登录可访问：危机场景下用户可能连密码都不记得（需求 §7.2 BR / FR10）
</script>

<style scoped>
.help { max-width: 640px; margin: 8vh auto; }
h1 { color: var(--mi-primary); }
.lead { font-size: 15px; line-height: 1.9; }
.lines { line-height: 2.1; padding-left: 20px; }
.lines b { color: var(--mi-anger); font-size: 17px; }
.tip { font-size: 13px; color: var(--mi-text-dim); border-top: 1px dashed var(--mi-border); padding-top: 14px; }
</style>
