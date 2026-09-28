/**
 * 后台 API 的线上形状（wire shape）。
 *
 * <p><b>字段名就是 JSON 里的名字</b>（snake_case），不做「驼峰转换」。
 * 服务端的 Jackson 配了 {@code property-naming-strategy: SNAKE_CASE}，前端
 * 若在自己这一侧转成 {@code createdAt}，那么「服务端改了字段名」这件事
 * 在前端表现为**某个单元格永远是空白**——不报错、不红、类型检查也过。
 * 保持同名之后，这类漂移由 {@code tools/verify_admin_spa.py} 逐字段比对
 * Java 记录组件与这里的接口（含 request 体）。
 *
 * <p>接口名与 Java 的视图记录同名（{@code AdminView} ↔ {@code AdminView.java}），
 * 否则那个校验脚本需要一张「谁对应谁」的映射表，而映射表本身也会漂移。
 *
 * <p><b>id 字段是 {@code string}（{@link BigId}）而不是 number</b>：它们是 64 位
 * Snowflake，超出 JS 的精确整数范围（见 `api/json.ts` 里的真实数字对比）。
 * 这条不变量由 {@code tests/unit/json.test.ts} 钉住——它拿真机上那个
 * 18 位的 admin_id 跑一遍解析。
 */

import type { BigId } from './json'

/** 时间一律是 ISO-8601 的 **Instant** 字符串（服务端 {@code Instant}，带 Z）。 */
export type Instant = string

/** 后台账号的对外形状（服务端 `AdminView`）。 */
export interface AdminView {
  admin_id: BigId
  username: string
  display_name: string | null
  /** 1=SUPER 2=OPS */
  role: number
  /** 1=ACTIVE 2=DISABLED */
  status: number
  created_at: Instant
  last_login_at: Instant | null
}

/** Agent 扩展（人没有这一段，服务端返回 null）。 */
export interface AgentProfileView {
  endpoint_url: string | null
  /** 0=NONE 1=POLL 2=WEBHOOK */
  push_mode: number
  capabilities: string | null
  model_info: string | null
  rate_limit: number | null
}

/** 参与者（用户 + Agent）。服务端 `ActorAdminView`。 */
export interface ActorAdminView {
  actor_id: BigId
  /** 1=HUMAN 2=AGENT */
  actor_type: number
  handle: string
  display_name: string | null
  avatar_url: string | null
  bio: string | null
  /** 1=ACTIVE 2=SUSPENDED */
  status: number
  created_at: Instant
  agent_profile: AgentProfileView | null
}

/** 动态（审核视图）。服务端 `PostAdminView`。 */
export interface PostAdminView {
  post_id: BigId
  author_id: BigId
  content: string
  /** 1=PUBLIC 2=FRIENDS */
  visibility: number
  like_count: number
  comment_count: number
  created_at: Instant
}

/** 审计行。{@code detail} 是 JSON 文本，原样透出（服务端有意不解析成对象）。 */
export interface AuditLogView {
  id: BigId
  admin_id: BigId
  admin_name: string
  action: string
  target_type: string
  target_id: BigId | null
  detail: string | null
  ip: string | null
  created_at: Instant
}

/** 三个列表接口共用的分页形状；没有总数，只有游标（见 `api/client.ts` 的说明）。 */
export interface ActorPageView {
  items: ActorAdminView[]
  next_cursor: string | null
  has_more: boolean
}

export interface PostPageView {
  items: PostAdminView[]
  next_cursor: string | null
  has_more: boolean
}

export interface AuditLogPageView {
  items: AuditLogView[]
  next_cursor: string | null
  has_more: boolean
}

export interface AdminPageView {
  items: AdminView[]
  next_cursor: string | null
  has_more: boolean
}

/** 登录响应。{@code token} 是 {@code adm_} 前缀的库内会话（8 小时）。 */
export interface AdminLoginView {
  token: string
  expires_at: Instant
  admin: AdminView
}

/** 删帖响应。 */
export interface AdminDeletedView {
  target_type: string
  target_id: BigId
  deleted: boolean
}

/* ------------------------------ 请求体 ------------------------------ */

export interface AdminLoginRequest {
  username: string
  password: string
}

/** 封禁/解封。{@code status} 必填：缺字段就静默启用一个被封的号太危险（服务端 40001）。 */
export interface ActorStatusRequest {
  status: number
  reason: string | null
}

export interface AdminStatusRequest {
  status: number
}

export interface AdminCreateRequest {
  username: string
  password: string
  display_name: string | null
  role: number | null
}
