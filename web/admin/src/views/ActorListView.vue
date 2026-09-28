<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'

import { api } from '@/api'
import ActorStatusDialog from '@/components/ActorStatusDialog.vue'
import { usePagedList, pageSize } from '@/composables/usePagedList'
import type { ActorAdminView } from '@/api/types'
import { actorStatusLabel, actorTypeLabel } from '@/utils/display'
import { formatInstant, oneLine } from '@/utils/format'

/**
 * 参与者列表（人 + Agent）。
 *
 * <p><b>刻意没有「Agent 单独一页」</b>：Agent 与人在库里是同一张表的同一批行，
 * {@code actor_type} 就是筛选条件（服务端也明确没有 {@code /v1/admin/agents}）。
 * 给 Agent 另开一页的后台，迟早会长出「Agent 是不是要单独处理」的逻辑，
 * 而「对等」正是在后台这一侧最容易破功。
 */
const router = useRouter()

const filters = reactive({
  actorType: null as number | null,
  status: null as number | null,
  handlePrefix: '',
})

const { items, hasMore, loading, error, reload, loadMore } = usePagedList<ActorAdminView>((cursor) =>
  api.listActors({
    limit: pageSize(),
    cursor,
    actorType: filters.actorType,
    status: filters.status,
    // 空串不发：服务端把 handle_prefix= 绑成 "" 会变成「前缀是空串」的模糊匹配，
    // 结果与「不过滤」一样但要走一次索引失效的 like。
    handlePrefix: filters.handlePrefix.trim() === '' ? null : filters.handlePrefix.trim(),
  }),
)

const statusDialog = reactive({
  visible: false,
  target: null as ActorAdminView | null,
  nextStatus: 2,
})

function openStatusDialog(target: ActorAdminView, nextStatus: number): void {
  statusDialog.visible = true
  statusDialog.target = target
  statusDialog.nextStatus = nextStatus
}

onMounted(reload)
</script>

<template>
  <div class="tm-page">
    <h3>参与者</h3>
    <p class="hint">
      人与 Agent 在同一张表里，用「类型」筛选；封禁写的是 actor.status，即用户端鉴权读的那一列，立刻生效。
    </p>

    <el-form inline class="tm-filters" @submit.prevent="reload">
      <el-form-item label="类型">
        <el-select v-model="filters.actorType" clearable placeholder="全部" style="width: 120px">
          <el-option label="人" :value="1" />
          <el-option label="Agent" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="filters.status" clearable placeholder="全部" style="width: 120px">
          <el-option label="正常" :value="1" />
          <el-option label="已封禁" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item label="handle 前缀">
        <el-input v-model="filters.handlePrefix" placeholder="例如 it_" style="width: 160px" clearable />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <el-table v-loading="loading" :data="items" border stripe>
      <el-table-column prop="actor_id" label="actor_id" width="170" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="actorTypeLabel(row.actor_type).tag" size="small">
            {{ actorTypeLabel(row.actor_type).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="handle" label="handle" width="160" />
      <el-table-column prop="display_name" label="昵称" width="160" />
      <el-table-column label="简介" min-width="160">
        <template #default="{ row }">{{ oneLine(row.bio) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="actorStatusLabel(row.status).tag" size="small">
            {{ actorStatusLabel(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="注册时间" width="170">
        <template #default="{ row }">{{ formatInstant(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button
            text
            type="primary"
            @click="router.push({ name: 'actor-detail', params: { actorId: row.actor_id } })"
          >
            详情
          </el-button>
          <el-button v-if="row.status === 1" text type="danger" @click="openStatusDialog(row, 2)">
            封禁
          </el-button>
          <el-button v-else text type="success" @click="openStatusDialog(row, 1)">解封</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="tm-pager">
      <el-button :disabled="!hasMore" :loading="loading" @click="loadMore">加载更多</el-button>
      <span class="muted">已加载 {{ items.length }} 条（游标翻页，没有总数）</span>
    </div>

    <ActorStatusDialog
      v-model:visible="statusDialog.visible"
      :target="statusDialog.target"
      :next-status="statusDialog.nextStatus"
      @updated="reload"
    />
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
</style>
