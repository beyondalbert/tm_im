<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import { useAuthStore } from '@/stores/auth'
import { NAV } from '@/router'
import { adminRoleLabel } from '@/utils/display'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/**
 * 菜单按角色过滤。
 *
 * <p>这不是权限，而是一条体验规则：OPS 点进「后台账号」只会拿到 40302，
 * 而 40302 在界面上看起来像是「配置错了」。服务端的判据依然在
 * {@code AdminService#requireSuper}（路由守卫是第二道，服务端是唯一的判据）。
 */
const items = computed(() => NAV.filter((item) => !item.superOnly || auth.isSuper))

const role = computed(() => (auth.admin ? adminRoleLabel(auth.admin.role) : null))

const activeMenu = computed(() => {
  const parent = route.meta.parent
  return typeof parent === 'string' ? parent : String(route.name ?? '')
})

async function onLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('退出后需要重新登录（会话仍在服务端保留到过期）', '确认退出？', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  await auth.logout()
  ElMessage.success('已退出登录')
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">tm_im 管理后台</div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item v-for="item in items" :key="item.name" :index="item.name" :route="{ name: item.name }">
          {{ item.title }}
        </el-menu-item>
      </el-menu>
      <div class="footnote">
        <!-- 独立 JAR：这一句是给人看的说明，不是装饰 -->
        本界面由 tm-admin.jar 提供，与用户端不同进程
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="who">
          <span>{{ auth.displayName }}</span>
          <el-tag v-if="role" :type="role.tag" size="small" effect="dark">{{ role.label }}</el-tag>
        </div>
        <el-button text type="danger" @click="onLogout">退出</el-button>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
}

.brand {
  padding: 18px 16px;
  font-weight: 600;
  font-size: 15px;
  border-bottom: 1px solid #e4e7ed;
}

.menu {
  border-right: none;
}

.footnote {
  margin-top: auto;
  padding: 12px 16px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}

.header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.who {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
}

.main {
  padding: 0;
}
</style>
