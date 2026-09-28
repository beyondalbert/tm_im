<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'

import { api } from '@/api'
import { ApiError } from '@/api/client'
import { usePagedList, pageSize } from '@/composables/usePagedList'
import { useAuthStore } from '@/stores/auth'
import type { AdminView } from '@/api/types'
import { adminRoleLabel, adminStatusLabel } from '@/utils/display'
import { formatInstant } from '@/utils/format'

/**
 * 后台账号管理（**整组 SUPER only**）。
 *
 * <p>这一页存在的理由：{@code /v1/admin} 的能力是「封任何人的号」，
 * 所以「谁能进后台」本身必须可管。否则唯一的做法是直接改库，而改库的
 * 后果（忘记给 role、口令哈希格式拼错）都只在下次登录时才发现。
 *
 * <p>界面上<b>禁用</b>了「停用自己」：服务端会回 40904（{@code SELF_OPERATION}），
 * 而那条请求的后果是把自己踢出后台——按钮留着只会让人点一次试试。
 */
const auth = useAuthStore()

const { items, hasMore, loading, error, reload, loadMore } = usePagedList<AdminView>((cursor) =>
  api.listAdmins({ limit: pageSize(), cursor }),
)

const createDialog = reactive({
  visible: false,
  username: '',
  password: '',
  displayName: '',
  role: 2,
  busy: false,
})

function openCreate(): void {
  createDialog.visible = true
  createDialog.username = ''
  createDialog.password = ''
  createDialog.displayName = ''
  // 默认 OPS：新建账号时最需要的是「能看能封」，而 SUPER 是多一个「管账号」的能力。
  // 默认值选小的那个，误选一次的代价是找 SUPER 补一次权限，而不是多一个能改权限的人。
  createDialog.role = 2
}

async function submitCreate(): Promise<void> {
  createDialog.busy = true
  try {
    const created = await api.createAdmin({
      username: createDialog.username.trim(),
      password: createDialog.password,
      displayName: createDialog.displayName.trim() === '' ? null : createDialog.displayName.trim(),
      role: createDialog.role,
    })
    ElMessage.success(`已创建 ${created.username}`)
    createDialog.visible = false
    await reload()
  } catch (cause) {
    ElMessage.error(cause instanceof ApiError ? cause.message : String(cause))
  } finally {
    createDialog.busy = false
  }
}

async function toggleStatus(row: AdminView): Promise<void> {
  const next = row.status === 1 ? 2 : 1
  try {
    const updated = await api.setAdminStatus(row.admin_id, next)
    ElMessage.success(next === 2 ? `已停用 ${updated.username}（其全部会话已删除）` : `已启用 ${updated.username}`)
    await reload()
  } catch (cause) {
    ElMessage.error(cause instanceof ApiError ? cause.message : String(cause))
  }
}

onMounted(reload)
</script>

<template>
  <div class="tm-page">
    <h3>后台账号</h3>
    <p class="hint">
      停用会<b>连带删除</b>该账号的全部会话（同一事务）：语义是「立刻没有权限」，而不是「下次登录会被拒」。
      明文口令只进不回——响应里没有任何口令字段。
    </p>

    <el-button type="primary" class="create" @click="openCreate">新建账号</el-button>
    <el-button :loading="loading" @click="reload">刷新</el-button>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <el-table v-loading="loading" :data="items" border stripe>
      <el-table-column prop="admin_id" label="admin_id" width="170" />
      <el-table-column prop="username" label="用户名" width="180" />
      <el-table-column prop="display_name" label="显示名" width="160" />
      <el-table-column label="角色" width="110">
        <template #default="{ row }">
          <el-tag :type="adminRoleLabel(row.role).tag" size="small">
            {{ adminRoleLabel(row.role).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="adminStatusLabel(row.status).tag" size="small">
            {{ adminStatusLabel(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatInstant(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="最近登录" width="170">
        <template #default="{ row }">{{ formatInstant(row.last_login_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-tooltip :disabled="row.admin_id !== auth.admin?.admin_id" content="不能停用自己">
            <span>
              <el-button
                text
                :type="row.status === 1 ? 'danger' : 'success'"
                :disabled="row.admin_id === auth.admin?.admin_id"
                @click="toggleStatus(row)"
              >
                {{ row.status === 1 ? '停用' : '启用' }}
              </el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <div class="tm-pager">
      <el-button :disabled="!hasMore" :loading="loading" @click="loadMore">加载更多</el-button>
      <span class="muted">已加载 {{ items.length }} 条</span>
    </div>

    <el-dialog v-model="createDialog.visible" title="新建后台账号" width="480px">
      <el-form label-position="top">
        <el-form-item label="用户名（3-32 位，字母/数字/下划线）">
          <el-input v-model="createDialog.username" placeholder="例如 ops_zhang" />
        </el-form-item>
        <el-form-item label="口令（服务端要求至少 12 位）">
          <el-input v-model="createDialog.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="显示名（可选）">
          <el-input v-model="createDialog.displayName" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="createDialog.role">
            <el-radio :value="2">OPS（可封禁、可删帖、可查日志）</el-radio>
            <el-radio :value="1">SUPER（额外可管后台账号）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.busy" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  color: #606266;
  font-size: 13px;
  margin-top: 0;
}

.create {
  margin-bottom: 12px;
}

.muted {
  color: #909399;
  font-size: 12px;
}
</style>
