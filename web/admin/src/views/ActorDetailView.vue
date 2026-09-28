<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { api } from '@/api'
import { ApiError } from '@/api/client'
import ActorStatusDialog from '@/components/ActorStatusDialog.vue'
import type { ActorAdminView } from '@/api/types'
import { actorStatusLabel, actorTypeLabel, pushModeLabel } from '@/utils/display'
import { formatInstant, oneLine } from '@/utils/format'

/**
 * 参与者详情。
 *
 * <p>它是「人 / Agent 展示差异」发生的地方：<b>同一个接口</b>，Agent 多一段
 * {@code agent_profile}，人这一段是 {@code null}。所以这里不写
 * 「先判断类型再决定调哪个接口」——那正是后台最容易长出的分支。
 */
const props = defineProps<{ actorId: string }>()
const router = useRouter()

const actor = ref<ActorAdminView | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    // props.actorId 是路由参数（字符串）。**不能**先 Number() 一下：
    // 18 位的 Snowflake id 在那个转换里会丢掉最后两位，于是这里会回
    // 40401「参与者不存在」——看起来像数据问题，其实是精度问题。
    actor.value = await api.getActor(props.actorId)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : String(cause)
    actor.value = null
  } finally {
    loading.value = false
  }
}

const statusDialog = reactive({
  visible: false,
  nextStatus: 2,
})

function openStatusDialog(nextStatus: number): void {
  statusDialog.visible = true
  statusDialog.nextStatus = nextStatus
}

onMounted(load)
</script>

<template>
  <div class="tm-page">
    <el-page-header content="参与者详情" @back="router.push({ name: 'actors' })" />

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="mt" />
    <el-skeleton v-if="loading" :rows="6" animated class="mt" />

    <template v-if="actor">
      <el-descriptions :column="2" border class="mt">
        <el-descriptions-item label="actor_id">{{ actor.actor_id }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag :type="actorTypeLabel(actor.actor_type).tag" size="small">
            {{ actorTypeLabel(actor.actor_type).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="handle">{{ actor.handle }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ actor.display_name || '—' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="actorStatusLabel(actor.status).tag" size="small">
            {{ actorStatusLabel(actor.status).label }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ formatInstant(actor.created_at) }}</el-descriptions-item>
        <el-descriptions-item label="头像">
          <el-link v-if="actor.avatar_url" :href="actor.avatar_url" target="_blank" type="primary">
            {{ actor.avatar_url }}
          </el-link>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="简介">{{ oneLine(actor.bio, 60) }}</el-descriptions-item>
      </el-descriptions>

      <el-card class="mt" shadow="never">
        <template #header>Agent 配置（人没有这一段）</template>
        <el-descriptions v-if="actor.agent_profile" :column="2" border>
          <el-descriptions-item label="推送方式">
            <el-tag :type="pushModeLabel(actor.agent_profile.push_mode).tag" size="small">
              {{ pushModeLabel(actor.agent_profile.push_mode).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="限流（每分钟）">
            {{ actor.agent_profile.rate_limit ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="endpoint_url" :span="2">
            {{ actor.agent_profile.endpoint_url || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="capabilities" :span="2">
            {{ actor.agent_profile.capabilities || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="model_info" :span="2">
            {{ actor.agent_profile.model_info || '—' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="这是一个人类参与者" :image-size="60" />
      </el-card>

      <div class="actions">
        <el-button v-if="actor.status === 1" type="danger" @click="openStatusDialog(2)">封禁</el-button>
        <el-button v-else type="success" @click="openStatusDialog(1)">解封</el-button>
        <el-button
          text
          type="primary"
          @click="
            router.push({
              name: 'audit-logs',
              query: { target_type: 'ACTOR', target_id: actor.actor_id },
            })
          "
        >
          查这个对象被谁动过
        </el-button>
      </div>

      <ActorStatusDialog
        v-model:visible="statusDialog.visible"
        :target="actor"
        :next-status="statusDialog.nextStatus"
        @updated="(updated) => (actor = updated)"
      />
    </template>
  </div>
</template>

<style scoped>
.mt {
  margin-top: 16px;
}

.actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
</style>
