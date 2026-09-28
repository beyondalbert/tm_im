<script setup lang="ts">
/**
 * 404 页。
 *
 * <p>它也是「SPA 路由回退」的另一面：只要后端把非 {@code /v1} 的 GET 回退到
 * {@code index.html}（DESIGN §5.3），这个页面就一定能显示出来；反之刷新
 * 任何子路由都会看到 Tomcat 的 404 页面，而那种页面看起来像「服务挂了」。
 */
</script>

<template>
  <div class="tm-page">
    <el-result icon="error" title="页面不存在" sub-title="路径写错了，或者这个页面已经不在这个版本里">
      <template #extra>
        <el-button type="primary" @click="$router.replace({ name: 'actors' })">回到参与者列表</el-button>
      </template>
    </el-result>
  </div>
</template>
