import { describe, expect, it, vi } from 'vitest'

import { AdminClient, ApiError } from '@/api/client'
import type { Endpoint } from '@/api/endpoints'
import { createMemoryTokenStore } from '@/api/tokens'

/**
 * 客户端的行为。
 *
 * <p>这里的每一条都对应一个**服务端的真实性质**（见 client.ts 的类注释）：
 * 业务失败也是 HTTP 200、非 200 也带同一个信封、会话失效要清本地凭证。
 * 这些性质一旦漂移，界面上的表现统统是「点了没反应」或「某个筛选没生效」，
 * 所以值得钉在单元测试里，而不是等人在浏览器里发现。
 */

/** 简写：测试里只关心方法与路径，权限标记不参与客户端行为。 */
function ep(method: Endpoint['method'], path: string): Endpoint {
  return { method, path, superOnly: false }
}

/** 一个记录调用的假 fetch：返回预设响应，同时把请求留给我们断言。 */
function fakeFetch(responses: Array<{ status?: number; body?: string }>) {
  const calls: Array<{ url: string; init: RequestInit | undefined }> = []
  const impl = (async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init })
    const next = responses.shift() ?? { status: 200, body: '{"code":0,"message":"ok","data":null}' }
    const response = {
      status: next.status ?? 200,
      text: async () => next.body ?? '',
    }
    return response as unknown as Response
  }) as typeof fetch
  return { impl, calls }
}

function envelope(code: number, message: string, data: unknown): string {
  return JSON.stringify({ code, message, data })
}

describe('AdminClient', () => {
  it('code=0 时返回 data（而不是整个信封）', async () => {
    const { impl } = fakeFetch([{ body: envelope(0, 'ok', { admin_id: 7 }) }])
    const client = new AdminClient({ tokens: createMemoryTokenStore(), fetchImpl: impl })

    await expect(client.request(ep('GET', '/x'))).resolves.toEqual({ admin_id: 7 })
  })

  it('业务失败走 HTTP 200，成功判据只能是 code', async () => {
    const { impl } = fakeFetch([{ status: 200, body: envelope(40302, 'permission denied', null) }])
    const client = new AdminClient({ tokens: createMemoryTokenStore(), fetchImpl: impl })

    const error = await client.request(ep('GET', '/x')).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(40302)
    // HTTP 是 200：这正是「不能拿状态码当成功判据」的证据
    expect((error as ApiError).httpStatus).toBe(200)
    expect((error as ApiError).message).toContain('SUPER')
  })

  it('非 200 也读同一个信封：40101 连 HTTP 状态一起暴露出来', async () => {
    const { impl } = fakeFetch([{ status: 401, body: envelope(40101, 'unauthorized', null) }])
    const client = new AdminClient({ tokens: createMemoryTokenStore(), fetchImpl: impl })

    const error = (await client.request(ep('POST', '/x')).catch((e: unknown) => e)) as ApiError
    expect(error.code).toBe(40101)
    expect(error.httpStatus).toBe(401)
  })

  it('会话失效会清掉本地凭证并通知上层', async () => {
    const tokens = createMemoryTokenStore('adm_stale')
    const onSessionLost = vi.fn()
    const { impl } = fakeFetch([{ status: 401, body: envelope(40102, 'invalid token format', null) }])
    const client = new AdminClient({ tokens, fetchImpl: impl, onSessionLost })

    await client.request(ep('GET', '/x')).catch(() => undefined)
    expect(tokens.get()).toBeNull()
    expect(onSessionLost).toHaveBeenCalledTimes(1)
  })

  it('40302 不清凭证：权限不够不代表会话失效', async () => {
    const tokens = createMemoryTokenStore('adm_live')
    const onSessionLost = vi.fn()
    const { impl } = fakeFetch([{ body: envelope(40302, 'permission denied', null) }])
    const client = new AdminClient({ tokens, fetchImpl: impl, onSessionLost })

    await client.request(ep('GET', '/x')).catch(() => undefined)
    expect(tokens.get()).toBe('adm_live')
    expect(onSessionLost).not.toHaveBeenCalled()
  })

  it('响应不是信封（例如代理返回 HTML）→ 50000，且把 HTTP 状态带出来', async () => {
    const { impl } = fakeFetch([{ status: 502, body: '<html>Bad Gateway</html>' }])
    const client = new AdminClient({ tokens: createMemoryTokenStore(), fetchImpl: impl })

    const error = (await client.request(ep('GET', '/x')).catch((e: unknown) => e)) as ApiError
    expect(error.code).toBe(50000)
    expect(error.httpStatus).toBe(502)
    expect(error.message).toContain('Bad Gateway')
  })

  it('网络层失败 → 码 -1（不可能与服务端的错误码空间混淆）', async () => {
    const failing = (async () => {
      throw new TypeError('fetch failed')
    }) as typeof fetch
    const client = new AdminClient({ tokens: createMemoryTokenStore(), fetchImpl: failing })

    const error = (await client.request(ep('GET', '/x')).catch((e: unknown) => e)) as ApiError
    expect(error.code).toBe(-1)
    expect(error.httpStatus).toBe(0)
  })

  it('默认带 Bearer 凭证；withCredential=false（登录）时不带', async () => {
    const tokens = createMemoryTokenStore('adm_abc')
    const { impl, calls } = fakeFetch([{ body: envelope(0, 'ok', null) }, { body: envelope(0, 'ok', null) }])
    const client = new AdminClient({ tokens, fetchImpl: impl })

    await client.request(ep('GET', '/x'))
    await client.request(ep('POST', '/login'), { withCredential: false })

    const header = (init?: RequestInit) => (init?.headers as Record<string, string> | undefined) ?? {}
    expect(header(calls[0]?.init).Authorization).toBe('Bearer adm_abc')
    expect(header(calls[1]?.init).Authorization).toBeUndefined()
  })

  it('查询串丢掉 null / undefined / 空串，但保留 0 与 false', () => {
    const client = new AdminClient({ tokens: createMemoryTokenStore(), fetchImpl: fakeFetch([]).impl })

    const url = client.url('/v1/admin/actors', {
      query: { limit: 0, status: null, actor_type: undefined, handle_prefix: '', flag: false },
    })
    expect(url).toBe('/v1/admin/actors?limit=0&flag=false')
  })

  it('路径参数按 URL 编码，缺参数当场报错（不发一个拼坏的请求出去）', () => {
    const client = new AdminClient({ tokens: createMemoryTokenStore(), fetchImpl: fakeFetch([]).impl })

    expect(client.url('/v1/admin/actors/{actorId}', { pathParams: { actorId: 42 } })).toBe('/v1/admin/actors/42')
    expect(() => client.url('/v1/admin/actors/{actorId}')).toThrow(/缺少路径参数/)
  })

  it('JSON 请求体带上 Content-Type（服务端对没有它的 POST 回 40000）', async () => {
    const { impl, calls } = fakeFetch([{ body: envelope(0, 'ok', null) }])
    const client = new AdminClient({ tokens: createMemoryTokenStore(), fetchImpl: impl })

    await client.request(ep('POST', '/x'), { body: { a: 1 } })
    const headers = (calls[0]?.init?.headers as Record<string, string> | undefined) ?? {}
    expect(headers['Content-Type']).toBe('application/json')
    expect(calls[0]?.init?.body).toBe('{"a":1}')
  })
})
