<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

import { api } from '@/api'
import { usePagedList, pageSize } from '@/composables/usePagedList'
import { asBigId } from '@/api/json'
import type { AuditLogView } from '@/api/types'
import { AUDIT_ACTIONS, AUDIT_TARGET_TYPES } from '@/utils/display'
import { formatInstant, prettyJson } from '@/utils/format'

/**
 * 审计查询 —— M9 验收标准里的「可查日志」。
 *
 * <p>四个过滤条件对应四种真实问题（服务端注释里同一句话）：
 * <b>这个管理员干过什么</b>（{@code admin_id}）、<b>这个对象被谁动过</b>
 * （{@code target_type} + {@code target_id}）、<b>这类操作发生过几次</b>
 * （{@code action}）、<b>最近都发生了什么</b>（都不填）。
 *
 * <p>所以这里不是「一个搜索框 + 用户自己拼条件」，而是这四个输入框 ——
 * 它们与数据库上的两个索引一一对应（{@code idx_admin_time} /
 * {@code idx_target_time}），填得出来的组合就是走得动索引的组合。
 *
 * <p><b>没有删除按钮</b>：能被改动或删掉的审计只能证明「当时大概是这么回事」。
 */
const route = useRoute()

/**
 * id 输入框里放的是**字符串**。
 *
 * <p>曾经用 {@code el-input-number}：它的 v-model 是 JS number，于是 18 位的
 * {@code admin_id} 一进这个控件就被四舍五入，筛选结果空——而界面上看不到
 * 任何错误。一个「查不到」的问题花在排查「是不是没记日志」上的时间，
 * 比修这个控件花的时间多得多。
 */
const filters = reactive({
  adminId: typeof route.query.admin_id === 'string' ? route.query.admin_id : '',
  action: typeof route.query.action === 'string' ? route.query.action : '',
  targetType: typeof route.query.target_type === 'string' ? route.query.target_type : '',
  targetId: typeof route.query.target_id === 'string' ? route.query.target_id : '',
})

const { items, hasMore, loading, error, reload, loadMore } = usePagedList<AuditLogView>((cursor) =>
  api.listAuditLogs({
    limit: pageSize(),
    cursor,
    adminId: asBigId(filters.adminId),
    action: filters.action.trim() === '' ? null : filters.action.trim(),
    targetType: filters.targetType.trim() === '' ? null : filters.targetType.trim(),
    targetId: asBigId(filters.targetId),
  }),
)

/** 从一条审计行跳到「这个对象被谁动过」：这就是排查时最常用的一次跳转。 */
function drillDown(row: AuditLogView): { target_type: string; target_id: string } | null {
  if (!row.target_type || row.target_id === null) {
    return null
  }
  return { target_type: row.target_type, target_id: row.target_id }
}

function applyDrill(row: AuditLogView): void {
  const next = drillDown(row)
  if (!next) {
    return
  }
  filters.targetType = next.target_type
  filters.targetId = next.target_id
  filters.action = ''
  void reload()
}

/**
 * 「查询」按钮。
 *
 * <p>它在提交前先把两个 id 输入框校验一遍：填了非数字时如果直接查，
 * {@code asBigId} 会返回 null（把这一项当没填），于是界面显示的是**全部**审计——
 * 使用者会以为自己看到的是筛选结果。静默降级成「不过滤」是这一页最坏的失败方式。
 */
function search(): void {
  const inputs: ReadonlyArray<readonly [string, string]> = [
    ['管理员 ID', filters.adminId],
    ['目标 ID', filters.targetId],
  ]
  for (const [label, value] of inputs) {
    if (value.trim() !== '' && asBigId(value) === null) {
      ElMessage.warning(`${label} 必须是纯数字（64 位整数，不要用科学计数法）`)
      return
    }
  }
  void reload()
}

function reset(): void {
  filters.adminId = ''
  filters.action = ''
  filters.targetType = ''
  filters.targetId = ''
  void reload()
}

onMounted(reload)
</script>

<template>
  <div class="tm-page">
    <h3>审计日志</h3>
    <p class="hint">
      每个写动作与业务变更在<b>同一个事务</b>里写一行；<code>admin_name</code> 是快照（账号改名或删除后，
      这一行仍然能回答「当时是谁做的」）。审计只写不删——清理属于 DBA 的归档动作，不是这里的一个按钮。
      id 是 64 位整数，所以要按字符串填（用数字控件会被 JS 四舍五入）。
    </p>

    <el-form inline class="tm-filters" @submit.prevent="search">
      <el-form-item label="管理员 ID">
        <el-input v-model="filters.adminId" placeholder="admin_id" style="width: 190px" clearable />
      </el-form-item>
      <el-form-item label="动作">
        <el-select v-model="filters.action" clearable filterable allow-create placeholder="全部" style="width: 190px">
          <el-option v-for="action in AUDIT_ACTIONS" :key="action" :label="action" :value="action" />
        </el-select>
      </el-form-item>
      <el-form-item label="目标类型">
        <el-select v-model="filters.targetType" clearable placeholder="全部" style="width: 140px">
          <el-option v-for="type in AUDIT_TARGET_TYPES" :key="type" :label="type" :value="type" />
        </el-select>
      </el-form-item>
      <el-form-item label="目标 ID">
        <el-input v-model="filters.targetId" placeholder="target_id" style="width: 190px" clearable />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <el-table v-loading="loading" :data="items" border stripe>
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <div class="detail-title">detail（原样透出的 JSON 文本）</div>
            <pre class="tm-mono">{{ prettyJson(row.detail) }}</pre>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatInstant(row.created_at) }}</template>
      </el-table-column>
      <el-table-column prop="admin_name" label="管理员" width="150" />
      <el-table-column prop="action" label="动作" width="170" />
      <el-table-column label="目标" width="230">
        <template #default="{ row }">
          <el-button v-if="drillDown(row)" text type="primary" @click="applyDrill(row)">
            {{ row.target_type }}#{{ row.target_id }}
          </el-button>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column label="备注" min-width="200">
        <template #default="{ row }">
          <span class="tm-mono">{{ row.detail }}</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="tm-pager">
      <el-button :disabled="!hasMore" :loading="loading" @click="loadMore">加载更多</el-button>
      <span class="muted">已加载 {{ items.length }} 条</span>
    </div>
  </div>
</template>

<style scoped>
.hint {
  color: #606266;
  font-size: 13px;
  margin-top: 0;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.detail {
  padding: 8px 16px;
}

.detail-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.detail pre {
  margin: 0;
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
}
</style>
