<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import { adminRoleLabel } from '@/utils/display'

/**
 * 「这一页存在，但你的角色看不到」。
 *
 * <p>为什么不直接重定向回上一页：重定向之后的界面是完全正常的，操作员
 * 会以为自己点错了链接，而真正的原因是权限——那件事必须说出来，
 * 否则下一个人还会去问「后台账号那一页是不是挂了」。
 */
const auth = useAuthStore()
</script>

<template>
  <div class="tm-page">
    <el-result icon="warning" title="没有权限" sub-title="这一页要求 SUPER 角色">
      <template #extra>
        <p>
          当前身份：<b>{{ auth.displayName }}</b>
          <span v-if="auth.admin">（{{ adminRoleLabel(auth.admin.role).label }}）</span>
        </p>
        <p class="muted">
          判据在服务端（<code>AdminService#requireSuper</code>），这一页只是不给一条注定 40302 的路径。
          需要这项权限请让 SUPER 调整你的角色，或新建一个 SUPER 账号。
        </p>
        <el-button type="primary" @click="$router.replace({ name: 'actors' })">回到参与者列表</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.muted {
  color: #909399;
  font-size: 13px;
}
</style>
