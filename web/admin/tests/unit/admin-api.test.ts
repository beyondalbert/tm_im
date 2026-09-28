import { beforeEach, describe, expect, it } from 'vitest'

import { createAdminApi } from '@/api/admin'
import { AdminClient } from '@/api/client'
import { createMemoryTokenStore } from '@/api/tokens'

/**
 * **线上参数名的钉子。**
 *
 * <p>每个用例都在回答同一个问题：这一段发出去的字节，与服务端
 * {@code @RequestParam(name = "...")} / 记录组件名是否逐字相同。
 * 参数名写错的后果是**筛选被静默忽略**（界面显示「已筛选 Agent」，
 * 实际拿到全部参与者），而它不会报错、不会 4xx——
 * 因为服务端对不认识的参数只能忽略（Spring 的默认行为）。
 *
 * <p>静态的逐字段比对在 {@code tools/verify_admin_spa.py} 里；这里补的是
 * **运行时**的形状：参数有没有真的进 URL、DELETE 的理由是不是走查询串、
 * 请求体的字段名是不是 snake_case。
 */

interface Recorded {
  url: string
  init: RequestInit | undefined
}

function recordingClient(): { api: ReturnType<typeof createAdminApi>; calls: Recorded[]; client: AdminClient } {
  const calls: Recorded[] = []
  const impl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init })
    return {
      status: 200,
      text: async () => JSON.stringify({ code: 0, message: 'ok', data: { items: [], next_cursor: null, has_more: false } }),
    } as Response
  }) as typeof fetch
  const client = new AdminClient({ tokens: createMemoryTokenStore('adm_t'), fetchImpl: impl })
  return { api: createAdminApi(client), calls, client }
}

let context: ReturnType<typeof recordingClient>

beforeEach(() => {
  context = recordingClient()
})

describe('后台 API 的线上形状', () => {
  it('登录：POST /v1/admin/auth/login，体是 {username, password}', async () => {
    await context.api.login('alice', 'pw')
    expect(context.calls[0]?.url).toBe('/v1/admin/auth/login')
    expect(context.calls[0]?.init?.method).toBe('POST')
    expect(context.calls[0]?.init?.body).toBe('{"username":"alice","password":"pw"}')
  })

  it('参与者列表：actor_type / handle_prefix / status 的名字与顺序无害但名字必须对', async () => {
    await context.api.listActors({ limit: 20, cursor: 'c1', actorType: 2, status: 1, handlePrefix: 'it_' })
    const url = new URL(context.calls[0]?.url ?? '', 'http://x')
    expect(url.pathname).toBe('/v1/admin/actors')
    expect(url.searchParams.get('actor_type')).toBe('2')
    expect(url.searchParams.get('status')).toBe('1')
    expect(url.searchParams.get('handle_prefix')).toBe('it_')
    expect(url.searchParams.get('limit')).toBe('20')
    expect(url.searchParams.get('cursor')).toBe('c1')
  })

  it('封禁：PATCH /v1/admin/actors/{id}/status，体里是 status 与 reason（服务端缺 status 回 40001）', async () => {
    await context.api.setActorStatus('362810375490994176', 2, '刷屏')
    expect(context.calls[0]?.url).toBe('/v1/admin/actors/362810375490994176/status')
    expect(context.calls[0]?.init?.method).toBe('PATCH')
    expect(context.calls[0]?.init?.body).toBe('{"status":2,"reason":"刷屏"}')
  })

  it('删帖：理由是**查询参数**而不是请求体（请求体在部分代理上会被丢掉）', async () => {
    await context.api.deletePost('362810375490994177', '违规')
    expect(context.calls[0]?.url).toBe('/v1/admin/posts/362810375490994177?reason=%E8%BF%9D%E8%A7%84')
    expect(context.calls[0]?.init?.method).toBe('DELETE')
    expect(context.calls[0]?.init?.body).toBeUndefined()
  })

  it('审计：四个过滤参数名与 DB 索引一一对应，且 id 以字符串原样发出（19 位不丢精度）', async () => {
    await context.api.listAuditLogs({
      adminId: '362810375490994176',
      action: 'ACTOR_STATUS',
      targetType: 'ACTOR',
      targetId: '362810375490994177',
    })
    const url = new URL(context.calls[0]?.url ?? '', 'http://x')
    expect(url.pathname).toBe('/v1/admin/audit-logs')
    expect(url.searchParams.get('admin_id')).toBe('362810375490994176')
    expect(url.searchParams.get('action')).toBe('ACTOR_STATUS')
    expect(url.searchParams.get('target_type')).toBe('ACTOR')
    expect(url.searchParams.get('target_id')).toBe('362810375490994177')
  })

  it('建后台账号：体是 snake_case 的 display_name（写成 displayName 会被静默忽略）', async () => {
    await context.api.createAdmin({ username: 'ops1', password: 'x'.repeat(12), displayName: '张运维', role: 2 })
    expect(context.calls[0]?.url).toBe('/v1/admin/accounts')
    expect(context.calls[0]?.init?.body).toBe(
      '{"username":"ops1","password":"xxxxxxxxxxxx","display_name":"张运维","role":2}',
    )
  })

  it('停用账号：PATCH /v1/admin/accounts/{id}/status', async () => {
    await context.api.setAdminStatus('362810375490994176', 2)
    expect(context.calls[0]?.url).toBe('/v1/admin/accounts/362810375490994176/status')
    expect(context.calls[0]?.init?.method).toBe('PATCH')
    expect(context.calls[0]?.init?.body).toBe('{"status":2}')
  })

  it('登出带凭证（服务端就是靠这个头找到要删的行）', async () => {
    await context.api.logout()
    const headers = (context.calls[0]?.init?.headers as Record<string, string> | undefined) ?? {}
    expect(context.calls[0]?.url).toBe('/v1/admin/auth/logout')
    expect(headers.Authorization).toBe('Bearer adm_t')
  })

  it('不传的筛选条件不进 URL（否则服务端会把空串绑成 40002/或变成空匹配）', async () => {
    await context.api.listActors({ limit: 20, handlePrefix: null, actorType: null })
    expect(context.calls[0]?.url).toBe('/v1/admin/actors?limit=20')
  })
})
