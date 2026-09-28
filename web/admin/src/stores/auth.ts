import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { api, tokenStore } from '@/api'
import type { AdminView } from '@/api/types'

/**
 * 后台身份。
 *
 * <p>{@code role} 来自服务端（{@code GET /v1/admin/me}），前端的菜单与按钮
 * 都从它派生。<b>它不是一道权限</b>——真正的判据在 {@code AdminService#requireSuper}
 * 里，前端的隐藏只是不给操作员一条注定 40302 的路径（README「管理后台」一节）。
 *
 * <p>为什么要在启动时先拉一次 {@code me} 而不是把角色也塞进 localStorage：
 * 角色是**可被改的**（停用/降级由 SUPER 操作），而 localStorage 里的副本
 * 会在下次登录前一直撒谎。所以本地只放凭证，身份每次启动从服务端取。
 */
export const useAuthStore = defineStore('auth', () => {
  const admin = ref<AdminView | null>(null)
  const token = ref<string | null>(tokenStore.get())
  const loading = ref(false)

  const isLoggedIn = computed(() => token.value !== null)
  const isSuper = computed(() => admin.value?.role === 1)
  const displayName = computed(() => admin.value?.display_name || admin.value?.username || '')

  function acceptLogin(t: string, me: AdminView): void {
    tokenStore.set(t)
    token.value = t
    admin.value = me
  }

  async function loadMe(): Promise<AdminView> {
    loading.value = true
    try {
      const me = await api.me()
      admin.value = me
      return me
    } finally {
      loading.value = false
    }
  }

  /** 只清本地：用在「会话失效」的回调里（此时再调登出接口只会再失败一次）。 */
  function forget(): void {
    tokenStore.clear()
    token.value = null
    admin.value = null
  }

  /** 主动登出：先告诉服务端删掉那一行，再清本地。登出接口幂等，失败也照清。 */
  async function logout(): Promise<void> {
    try {
      await api.logout()
    } finally {
      forget()
    }
  }

  return { admin, token, loading, isLoggedIn, isSuper, displayName, acceptLogin, loadMe, logout, forget }
})
