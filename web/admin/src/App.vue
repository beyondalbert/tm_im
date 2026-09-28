<script setup lang="ts">
// 根组件只承担一件事：挂载点。布局在 AdminLayout（登录页不需要它，
// 而登录页也是一条路由——所以布局不能放在根组件里）。
</script>

<template>
  <router-view />
</template>
