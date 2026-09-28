import type { TokenStore } from './client'

/**
 * 浏览器里的凭证存放处。
 *
 * <p>用 {@code localStorage} 而不是 Cookie：{@code adm_} 是**库里的会话**，
 * 它没有 JWT 的签名可校验，也没有 {@code HttpOnly} 这一层保护可借
 * ——后台与用户端是同一套前端工程习惯，而 Cookie 方案会引入 CSRF 这个
 * 全新的问题（后台的每一个写动作都得带 CSRF token）。
 *
 * <p>代价是 XSS 可以把它读走，所以这个工程里没有 {@code v-html}
 * （见 {@code tools/verify_admin_spa.py} 的一条断言：它扫源码里是否出现
 * 能把服务端字符串当 HTML 渲染的写法，审计的 {@code detail} 正是那种字符串）。
 *
 * <p>key 带应用前缀：后台与用户端将来可能同域部署（不同路径），
 * 不带前缀的 {@code token} 会互相覆盖。
 */
export const ADMIN_TOKEN_KEY = 'tm.admin.token'

export function createBrowserTokenStore(storage: Storage = globalThis.localStorage): TokenStore {
  return {
    get: () => storage.getItem(ADMIN_TOKEN_KEY),
    set: (token: string) => storage.setItem(ADMIN_TOKEN_KEY, token),
    clear: () => storage.removeItem(ADMIN_TOKEN_KEY),
  }
}

/** 内存实现：Node 里的集成测试与单测用它，避免为了一次登录去装 jsdom。 */
export function createMemoryTokenStore(initial: string | null = null): TokenStore {
  let token = initial
  return {
    get: () => token,
    set: (value: string) => {
      token = value
    },
    clear: () => {
      token = null
    },
  }
}
