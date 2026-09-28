/**
 * 码值 → 界面文案。
 *
 * <p>服务端一律用**数字码**（{@code actor_type=2}、{@code visibility=1}），
 * 且刻意不接受字符串（见 {@code AdminParams} 的注释：用户端有过「role 接受名字」
 * 的历史包袱，后台保持一种写法）。所以「2 是什么」这件事只能由前端解释，
 * 而解释表漏一项的表现是表格里出现一个裸数字——不难看，但没人知道它是什么。
 */

export interface Labelled {
  label: string
  /** Element Plus 的 tag 类型，让状态在视觉上可扫读。 */
  tag: 'primary' | 'success' | 'warning' | 'danger' | 'info'
}

export function actorTypeLabel(code: number): Labelled {
  switch (code) {
    case 1:
      return { label: '人', tag: 'primary' }
    case 2:
      return { label: 'Agent', tag: 'success' }
    default:
      return { label: `未知(${code})`, tag: 'info' }
  }
}

export function actorStatusLabel(code: number): Labelled {
  switch (code) {
    case 1:
      return { label: '正常', tag: 'success' }
    case 2:
      return { label: '已封禁', tag: 'danger' }
    default:
      return { label: `未知(${code})`, tag: 'info' }
  }
}

export function adminRoleLabel(code: number): Labelled {
  switch (code) {
    case 1:
      return { label: 'SUPER', tag: 'danger' }
    case 2:
      return { label: 'OPS', tag: 'warning' }
    default:
      return { label: `未知(${code})`, tag: 'info' }
  }
}

export function adminStatusLabel(code: number): Labelled {
  switch (code) {
    case 1:
      return { label: '启用', tag: 'success' }
    case 2:
      return { label: '已停用', tag: 'info' }
    default:
      return { label: `未知(${code})`, tag: 'info' }
  }
}

export function visibilityLabel(code: number): Labelled {
  switch (code) {
    case 1:
      return { label: '公开', tag: 'success' }
    case 2:
      return { label: '仅好友', tag: 'warning' }
    default:
      return { label: `未知(${code})`, tag: 'info' }
  }
}

export function pushModeLabel(code: number): Labelled {
  switch (code) {
    case 0:
      return { label: '不推送', tag: 'info' }
    case 1:
      return { label: '轮询', tag: 'warning' }
    case 2:
      return { label: 'Webhook', tag: 'success' }
    default:
      return { label: `未知(${code})`, tag: 'info' }
  }
}

/** 审计动作的取值来自 {@code AdminService} 的常量，这里是它们的中文解释。 */
export const AUDIT_ACTIONS: readonly string[] = [
  'ADMIN_LOGIN',
  'ADMIN_LOGIN_FAILED',
  'ADMIN_LOGOUT',
  'ADMIN_CREATE',
  'ADMIN_STATUS',
  'ACTOR_STATUS',
  'POST_DELETE',
]

export const AUDIT_TARGET_TYPES: readonly string[] = ['ACTOR', 'AGENT', 'POST', 'ADMIN']
