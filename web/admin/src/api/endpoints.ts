/**
 * **前端唯一的一份端点清单。**
 *
 * <p>它存在的理由不是「集中管理」这种好处，而是可校验：这个文件被
 * {@code tools/verify_admin_spa.py} 解析，与
 * ① 服务端控制器的 {@code @RequestMapping + @GetMapping/...}、
 * ② {@code docs/integration/09-admin-api.md} 的端点表
 * 三方比对。任何一处多一个/少一个端点、路径写错、方法写错都会失败。
 *
 * <p>为什么值得为此加一个校验脚本：后台前端的错误表现是**静默**的——
 * 路径拼错的后果是 404（服务端回 {@code code=40400}，与「资源不存在」共用
 * 一个码），而权限写错的后果是「这一页对 OPS 也不显示」。两者都不会让
 * 构建失败。
 *
 * <p>{@code superOnly} 记的是「服务端是否要求 SUPER」，**不是**「前端是否要
 * 隐藏入口」。前者是事实（在 {@code AdminService#requireSuper} 里），
 * 后者是体验；两者的关系只能是一个方向：服务端要求 SUPER 的，前端必须
 * 隐藏或降级展示，反之不成立。
 */

export interface Endpoint {
  readonly method: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  readonly path: string
  readonly superOnly: boolean
}

export const ENDPOINTS = {
  login: { method: 'POST', path: '/v1/admin/auth/login', superOnly: false },
  logout: { method: 'POST', path: '/v1/admin/auth/logout', superOnly: false },
  me: { method: 'GET', path: '/v1/admin/me', superOnly: false },

  listActors: { method: 'GET', path: '/v1/admin/actors', superOnly: false },
  getActor: { method: 'GET', path: '/v1/admin/actors/{actorId}', superOnly: false },
  setActorStatus: { method: 'PATCH', path: '/v1/admin/actors/{actorId}/status', superOnly: false },

  listPosts: { method: 'GET', path: '/v1/admin/posts', superOnly: false },
  deletePost: { method: 'DELETE', path: '/v1/admin/posts/{postId}', superOnly: false },

  listAuditLogs: { method: 'GET', path: '/v1/admin/audit-logs', superOnly: false },

  listAdmins: { method: 'GET', path: '/v1/admin/accounts', superOnly: true },
  getAdmin: { method: 'GET', path: '/v1/admin/accounts/{adminId}', superOnly: true },
  createAdmin: { method: 'POST', path: '/v1/admin/accounts', superOnly: true },
  setAdminStatus: { method: 'PATCH', path: '/v1/admin/accounts/{adminId}/status', superOnly: true },
} as const satisfies Record<string, Endpoint>

export type EndpointName = keyof typeof ENDPOINTS

/** 路径参数替换：{@code /v1/admin/actors/{actorId}} + {@code {actorId: 7}} → {@code /v1/admin/actors/7}。 */
export function resolvePath(path: string, params: Record<string, string | number> = {}): string {
  return path.replace(/\{(\w+)\}/g, (_match, name: string) => {
    const value = params[name]
    if (value === undefined || value === null) {
      throw new Error(`缺少路径参数 ${name}（端点 ${path}）`)
    }
    return encodeURIComponent(String(value))
  })
}
