/**
 * 错误码 → 运维能看懂的一句话。
 *
 * <p>服务端的 {@code message} 是**英文短语**（{@code "permission denied"}），
 * 它对排查有价值的是**错误码**，而代码（{@code 40302}）只有查表才知道含义。
 * 后台的使用者是运营，所以这里必须给出一句能指导下一步动作的话，
 * 而不是把英文短语原样贴在界面上。
 *
 * <p>这份表由 {@code tools/verify_admin_spa.py} 与服务端对齐：它从
 * {@code AdminService} / {@code tm-api-admin} 里扫出「后台这条路径上可能出现的
 * 错误码」，再要求本表一个不漏。理由很直白——漏一个的后果是那个错误在界面上
 * 显示成「未知错误 (40904)」，而它恰好是「不能停用自己」这种必须说清楚的事。
 *
 * <p>文案里的「15 分钟」「8 小时」不是硬编码的承诺：它们对应
 * {@code tm.admin.lockout-duration} 与 {@code tm.admin.session-ttl} 的默认值，
 * 改配置时这句话会过期——所以句子写成「请稍后重试」，数字放在括号里。
 */

/** 认证类失败的三个码：出现即意味着本地会话已不可用，必须回登录页。 */
export const SESSION_LOST_CODES: readonly number[] = [40101, 40102, 40103]

export const ADMIN_ERROR_MESSAGES: Record<number, string> = {
  // ---- 参数 ----
  40000: '请求格式不对（服务端无法解析这次请求）',
  40001: '缺少必填参数',
  40002: '参数取值不合法（例如 actor_type / status 只能是 1 或 2）',
  // ---- 认证与权限 ----
  40101: '用户名或口令不正确；也可能是会话已失效，请重新登录',
  40102: '后台凭证无效（不是 adm_ 开头的会话，或该会话已被登出）',
  40103: '后台会话已过期（默认 8 小时），请重新登录',
  40301: '该后台账号已被停用（或该参与者已被封禁）',
  40302: '需要 SUPER 权限才能执行这个操作',
  // ---- 目标不存在 ----
  40400: '目标不存在或已被删除',
  40401: '参与者不存在（actor_id 可能写错了）',
  40404: '这条动态不存在（可能已经被删过一次）',
  // ---- 冲突 ----
  40904: '不能对自己执行这个操作（例如停用自己）',
  40909: '后台用户名已被占用',
  // ---- 限流与服务端 ----
  42901: '登录失败次数过多，账号已被临时锁定，请稍后重试（默认 15 分钟）',
  50000: '服务端内部错误，请看服务端日志（响应里不带详情，是有意为之）',
}

/** 给界面用的错误描述：已知码给中文，未知码也**必须把码露出来**（否则没法查）。 */
export function describeCode(code: number, serverMessage?: string | null): string {
  const known = ADMIN_ERROR_MESSAGES[code]
  if (known) {
    return known
  }
  if (code === 0) {
    return '成功'
  }
  const suffix = serverMessage ? `（服务端文案：${serverMessage}）` : ''
  return `未知错误码 ${code}${suffix}`
}

export function isSessionLost(code: number): boolean {
  return SESSION_LOST_CODES.includes(code)
}
