<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { api } from '@/api'
import { ApiError } from '@/api/client'
import type { ActorAdminView } from '@/api/types'

/**
 * 封禁 / 解封对话框。
 *
 * <p>抽成组件是因为列表页与详情页都要用它，而这里有一条**必须一致**的规则：
 * 封禁要填理由。两处各写一遍的话，漏掉的那处就是「审计里查不到为什么封」——
 * 它不会报错，只会在某天需要回答「这个人为什么被封了」时才发现。
 */
const props = defineProps<{
  visible: boolean
  target: ActorAdminView | null
  /** 1=解封 2=封禁 */
  nextStatus: number
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'updated', actor: ActorAdminView): void
}>()

const reason = ref('')
const busy = ref(false)

watch(
  () => props.visible,
  (open) => {
    if (open) {
      reason.value = ''
    }
  },
)

const suspending = computed(() => props.nextStatus === 2)
const title = computed(() => (suspending.value ? '封禁参与者' : '解封参与者'))
const reasonLabel = computed(() => (suspending.value ? '理由（必填，进审计）' : '理由（可选）'))

async function submit(): Promise<void> {
  const target = props.target
  if (!target) {
    return
  }
  const text = reason.value.trim()
  if (suspending.value && text === '') {
    ElMessage.warning('封禁需要填写理由（它会进审计）')
    return
  }
  busy.value = true
  try {
    const updated = await api.setActorStatus(target.actor_id, props.nextStatus, text === '' ? null : text)
    ElMessage.success(suspending.value ? `已封禁 ${updated.handle}` : `已解封 ${updated.handle}`)
    emit('updated', updated)
    emit('update:visible', false)
  } catch (cause) {
    ElMessage.error(cause instanceof ApiError ? cause.message : String(cause))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    width="480px"
    @update:model-value="emit('update:visible', $event)"
  >
    <p v-if="target">
      <b>{{ target.handle }}</b>
      <span class="muted">（actor_id={{ target.actor_id }}，当前状态 {{ target.status }}）</span>
    </p>
    <el-form label-position="top">
      <el-form-item :label="reasonLabel">
        <el-input v-model="reason" type="textarea" :rows="3" maxlength="200" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button :type="suspending ? 'danger' : 'primary'" :loading="busy" @click="submit">确认</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.muted {
  color: #909399;
  font-size: 12px;
}
</style>
