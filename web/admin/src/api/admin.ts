import { AdminClient } from './client'
import { ENDPOINTS } from './endpoints'
import type { BigId } from './json'
import type {
  ActorAdminView,
  ActorPageView,
  ActorStatusRequest,
  AdminCreateRequest,
  AdminDeletedView,
  AdminLoginRequest,
  AdminLoginView,
  AdminPageView,
  AdminStatusRequest,
  AdminView,
  AuditLogPageView,
  PostPageView,
} from './types'

/**
 * 后台 API 的**类型化调用**层：一个函数对一个端点。
 *
 * <p>契约上的三件事刻意写在这一层而不是各视图里：
 *
 * <ul>
 *   <li><b>查询参数名与线上一致</b>（{@code actor_type} / {@code handle_prefix} /
 *       {@code target_type} / {@code target_id} / {@code admin_id}）。拼错的后果是
 *       <b>筛选被静默忽略</b>——界面显示「已筛选 AGENT」，实际拿到的是全部参与者；
 *       而服务端把 {@code actor_type=3} 这种越界值回成 40002 是有意的（见
 *       {@code AdminParams}），说明「不认识的参数」它并不总是能拒绝。
 *       这一层被 {@code tools/verify_admin_spa.py} 与服务端 {@code @RequestParam} 比对。</li>
 *   <li><b>id 参数是 string（{@link BigId}）</b>。把 18-19 位的 Snowflake id
 *       先放到 JS 的 number 里再发出去，服务端拿到的是另一个 id——
 *       它会回 40400/40401，而界面上的表现是「目标不存在」。
 *       这一层被 {@code tools/verify_admin_spa.py} 与服务端 {@code @PathVariable}
 *       比对——比的是**参数名与位置**，而类型由 `api/json.ts` 保证。</li>
 *   <li><b>封禁必须带理由</b>。{@code reason} 会进审计；没有它的记录事后只能靠
 *       「谁可能知道口令」推断（README「管理后台」一节）。这里把参数设成
 *       {@code string | null} 而不是可选，是为了让调用方显式说出「我没写理由」。</li>
 *   <li><b>删帖的理由走查询串</b>，与用户端一致：{@code DELETE} 带 body 会在部分
 *       代理上被丢掉，而丢掉的恰好是「为什么删」。</li>
 * </ul>
 *
 * <p>分页参数一律是 {@code limit} + {@code cursor}，返回 {@code next_cursor} /
 * {@code has_more}；{@code limit} 由服务端夹取（要 1000 给 100），所以这里
 * 不校验上限——校验上限会让前端的数字与服务端的不一致。
 */

export interface ActorListQuery {
  limit?: number | null
  cursor?: string | null
  /** 1=HUMAN 2=AGENT */
  actorType?: number | null
  /** 1=ACTIVE 2=SUSPENDED */
  status?: number | null
  handlePrefix?: string | null
}

export interface PostListQuery {
  limit?: number | null
  cursor?: string | null
}

export interface AuditLogQuery {
  limit?: number | null
  cursor?: string | null
  adminId?: BigId | null
  action?: string | null
  targetType?: string | null
  targetId?: BigId | null
}

export interface AdminListQuery {
  limit?: number | null
  cursor?: string | null
}

export function createAdminApi(client: AdminClient) {
  return {
    // ---------------------------- 认证 ----------------------------
    async login(username: string, password: string): Promise<AdminLoginView> {
      const body: AdminLoginRequest = { username, password }
      return client.request<AdminLoginView>(ENDPOINTS.login, {
        body,
        withCredential: false,
      })
    },

    /** 登出不做鉴权（服务端注释）：凭证已失效也回成功，否则客户端不敢清本地状态。 */
    async logout(): Promise<void> {
      await client.request<null>(ENDPOINTS.logout)
    },

    async me(): Promise<AdminView> {
      return client.request<AdminView>(ENDPOINTS.me)
    },

    // ---------------------------- 参与者 ----------------------------
    async listActors(query: ActorListQuery = {}): Promise<ActorPageView> {
      return client.request<ActorPageView>(ENDPOINTS.listActors, {
        query: {
          limit: query.limit,
          cursor: query.cursor,
          actor_type: query.actorType,
          status: query.status,
          handle_prefix: query.handlePrefix,
        },
      })
    },

    async getActor(actorId: BigId): Promise<ActorAdminView> {
      return client.request<ActorAdminView>(ENDPOINTS.getActor, {
        pathParams: { actorId },
      })
    },

    async setActorStatus(
      actorId: BigId,
      status: number,
      reason: string | null,
    ): Promise<ActorAdminView> {
      const body: ActorStatusRequest = { status, reason }
      return client.request<ActorAdminView>(ENDPOINTS.setActorStatus, {
        pathParams: { actorId },
        body,
      })
    },

    // ---------------------------- 内容 ----------------------------
    async listPosts(query: PostListQuery = {}): Promise<PostPageView> {
      return client.request<PostPageView>(ENDPOINTS.listPosts, {
        query: {
          limit: query.limit,
          cursor: query.cursor,
        },
      })
    },

    async deletePost(postId: BigId, reason: string | null): Promise<AdminDeletedView> {
      return client.request<AdminDeletedView>(ENDPOINTS.deletePost, {
        pathParams: { postId },
        query: {
          reason,
        },
      })
    },

    // ---------------------------- 审计 ----------------------------
    async listAuditLogs(query: AuditLogQuery = {}): Promise<AuditLogPageView> {
      return client.request<AuditLogPageView>(ENDPOINTS.listAuditLogs, {
        query: {
          limit: query.limit,
          cursor: query.cursor,
          admin_id: query.adminId,
          action: query.action,
          target_type: query.targetType,
          target_id: query.targetId,
        },
      })
    },

    // ------------------------ 后台账号（SUPER） ------------------------
    async listAdmins(query: AdminListQuery = {}): Promise<AdminPageView> {
      return client.request<AdminPageView>(ENDPOINTS.listAdmins, {
        query: {
          limit: query.limit,
          cursor: query.cursor,
        },
      })
    },

    async getAdmin(adminId: BigId): Promise<AdminView> {
      return client.request<AdminView>(ENDPOINTS.getAdmin, {
        pathParams: { adminId },
      })
    },

    async createAdmin(input: {
      username: string
      password: string
      displayName: string | null
      role: number | null
    }): Promise<AdminView> {
      const body: AdminCreateRequest = {
        username: input.username,
        password: input.password,
        display_name: input.displayName,
        role: input.role,
      }
      return client.request<AdminView>(ENDPOINTS.createAdmin, { body })
    },

    async setAdminStatus(adminId: BigId, status: number): Promise<AdminView> {
      const body: AdminStatusRequest = { status }
      return client.request<AdminView>(ENDPOINTS.setAdminStatus, {
        pathParams: { adminId },
        body,
      })
    },
  }
}

export type AdminApi = ReturnType<typeof createAdminApi>
