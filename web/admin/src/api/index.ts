import { createAdminApi } from './admin'
import { AdminClient } from './client'
import { createBrowserTokenStore } from './tokens'

/**
 * 应用级的唯一客户端实例。
 *
 * <p>「会话失效」这件事在这里被转成一次回调：{@link AdminClient} 只负责
 * 发现「凭证不可用了」，而**把它变成一次跳转**是路由层的决定
 * （`main.ts` 里注册）。这样 client 不需要知道 Vue Router 的存在，
 * 于是同一份 client 代码可以在 Node 里被集成测试驱动（`tests/live/`）。
 */
export const tokenStore = createBrowserTokenStore()

let sessionLostHandler: (() => void) | null = null

export function setSessionLostHandler(handler: () => void): void {
  sessionLostHandler = handler
}

export const client = new AdminClient({
  tokens: tokenStore,
  onSessionLost: () => {
    sessionLostHandler?.()
  },
})

export const api = createAdminApi(client)
