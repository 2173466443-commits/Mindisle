<template>
  <div class="nf">
    <p class="big">404</p>
    <p>这个管理端页面不存在。<router-link to="/dashboard">回到运营看板</router-link></p>
  </div>
</template>

<script setup>
// 管理端没有危机求助页，未匹配路由一律退回看板。
</script>

<style scoped>
.nf { padding: 60px 20px; text-align: center; color: var(--mi-text-dim); }
.big { font-size: 46px; font-weight: 700; color: var(--mi-primary); margin: 0 0 10px; }
</style>
