<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { api } from '@/api'
import { ApiError } from '@/api/client'
import { usePagedList, pageSize } from '@/composables/usePagedList'
import type { PostAdminView } from '@/api/types'
import { visibilityLabel } from '@/utils/display'
import { formatInstant, oneLine } from '@/utils/format'

/**
 * 内容审核。
 *
 * <p>只有两个动作：看最新的、删掉某一条。**没有编辑**——审核时改动用户内容，
 * 会让「谁说的」这件事失去意义（服务端注释里同一句话）。
 *
 * <p>删除走的是作者删帖那条级联路径，所以收件箱/点赞/评论会一起清掉；
 * 界面上因此不需要「一并删除相关数据」的勾选项——那种勾选项会让人以为
 * 不勾就不会清。
 */
const { items, hasMore, loading, error, reload, loadMore } = usePagedList<PostAdminView>((cursor) =>
  api.listPosts({ limit: pageSize(), cursor }),
)

const dialog = reactive({
  visible: false,
  target: null as PostAdminView | null,
  reason: '',
  busy: false,
})

function openDeleteDialog(post: PostAdminView): void {
  dialog.visible = true
  dialog.target = post
  dialog.reason = ''
}

async function submitDelete(): Promise<void> {
  const target = dialog.target
  if (!target) {
    return
  }
  const reason = dialog.reason.trim()
  if (reason === '') {
    ElMessage.warning('删除需要填写理由（它会进审计）')
    return
  }
  dialog.busy = true
  try {
    const result = await api.deletePost(target.post_id, reason)
    if (!result.deleted) {
      // 服务端回 deleted=false 只有一种可能：这条动态已经不存在了。
      // 报「成功」会让操作员以为是自己这一次删掉的。
      ElMessage.warning('服务端未确认删除，请刷新列表确认')
    } else {
      ElMessage.success(`已删除动态 ${result.target_id}`)
    }
    dialog.visible = false
    await reload()
  } catch (cause) {
    const message = cause instanceof ApiError ? cause.message : String(cause)
    await ElMessageBox.alert(message, '删除失败', { confirmButtonText: '知道了' }).catch(() => undefined)
  } finally {
    dialog.busy = false
  }
}

onMounted(reload)
</script>

<template>
  <div class="tm-page">
    <h3>内容审核</h3>
    <p class="hint">
      按发布时间倒序。删除是不可逆的，并且会连带清掉收件箱、点赞与评论（与作者删帖同一条路径）。
    </p>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-button class="refresh" :loading="loading" @click="reload">刷新</el-button>

    <el-table v-loading="loading" :data="items" border stripe>
      <el-table-column prop="post_id" label="post_id" width="170" />
      <el-table-column prop="author_id" label="author_id" width="170" />
      <el-table-column label="内容" min-width="260">
        <template #default="{ row }">{{ oneLine(row.content, 100) }}</template>
      </el-table-column>
      <el-table-column label="可见性" width="100">
        <template #default="{ row }">
          <el-tag :type="visibilityLabel(row.visibility).tag" size="small">
            {{ visibilityLabel(row.visibility).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="like_count" label="点赞" width="80" />
      <el-table-column prop="comment_count" label="评论" width="80" />
      <el-table-column label="发布时间" width="170">
        <template #default="{ row }">{{ formatInstant(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button text type="danger" @click="openDeleteDialog(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="tm-pager">
      <el-button :disabled="!hasMore" :loading="loading" @click="loadMore">加载更多</el-button>
      <span class="muted">已加载 {{ items.length }} 条</span>
    </div>

    <el-dialog v-model="dialog.visible" title="删除动态" width="520px">
      <p v-if="dialog.target">
        post_id={{ dialog.target.post_id }} / author_id={{ dialog.target.author_id }}
      </p>
      <p v-if="dialog.target" class="content">{{ oneLine(dialog.target.content, 200) }}</p>
      <el-form label-position="top">
        <el-form-item label="理由（必填，进审计）">
          <el-input v-model="dialog.reason" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="dialog.busy" @click="submitDelete">确认删除</el-button>
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

.refresh {
  margin-bottom: 12px;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.content {
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  font-size: 13px;
}
</style>
