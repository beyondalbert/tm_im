<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { api } from '@/api'
import { ApiError } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const form = reactive({ username: '', password: '' })
const error = ref<string | null>(null)
const submitting = ref(false)

/**
 * 登录失败的原因**不细分**：服务端把「用户名不存在」与「口令不对」
 * 都回 40101（否则这个接口就成了用户名枚举器）。界面上同样不去猜
 * （不写「这个用户不存在」），那是同一类信息泄漏。
 */
async function submit(): Promise<void> {
  if (!form.username || !form.password) {
    error.value = '请填写用户名与口令'
    return
  }
  submitting.value = true
  error.value = null
  try {
    const view = await api.login(form.username, form.password)
    auth.acceptLogin(view.token, view.admin)
    ElMessage.success(`欢迎，${view.admin.display_name || view.admin.username}`)
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' ? redirect : { name: 'actors' })
  } catch (cause) {
    // ApiError 的 message 已经是「错误码 → 能指导下一步的中文」（见 api/errors.ts）。
    error.value = cause instanceof ApiError ? cause.message : String(cause)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <el-card class="box">
      <template #header>
        <div class="title">tm_im 管理后台</div>
      </template>

      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="alert" />

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            autocomplete="username"
            placeholder="后台账号（不是用户 handle）"
          />
        </el-form-item>
        <el-form-item label="口令">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            show-password
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" :loading="submitting" class="submit" @click="submit">登录</el-button>
      </el-form>

      <div class="note">
        <p>这是<b>管理后台</b>的入口，与用户端账号体系无关（后台账号在 admin_user 表里）。</p>
        <p>首个账号由 tm.admin.bootstrap.* 在表为空时创建一次。</p>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.box {
  width: 380px;
}

.title {
  font-weight: 600;
}

.alert {
  margin-bottom: 12px;
}

.submit {
  width: 100%;
}

.note {
  margin-top: 16px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.note p {
  margin: 4px 0;
}
</style>
