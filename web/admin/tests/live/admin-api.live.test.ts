import { afterAll, beforeAll, describe, expect, it } from 'vitest'

import { createAdminApi, type AdminApi } from '@/api/admin'
import { AdminClient, ApiError } from '@/api/client'
import { ENDPOINTS } from '@/api/endpoints'
import { createMemoryTokenStore } from '@/api/tokens'
import type { AdminView } from '@/api/types'

/**
 * **真实 tm-admin.jar 的集成测试。**
 *
 * <p>它驱动的是线上那段客户端代码（{@code src/api/}），只换掉两样东西：
 * {@code localStorage} → 内存，{@code baseUrl} → {@code TM_ADMIN_URL}。
 * 因此它验证的是「前端的字段名 / 参数名 / 信封假设」与**真实服务端**一致：
 *
 * <ul>
 *   <li>参数名写错 → 筛选被静默忽略（这里的断言会看到「筛了 Agent 却拿到人」）；</li>
 *   <li>字段名写错 → 某个字段永远是 {@code undefined}（这里的断言会看到 null）；</li>
 *   <li>信封假设错了 → 所有请求都像失败（这里的断言会当场炸）。</li>
 * </ul>
 *
 * <p><b>依赖的三件事</b>（缺任何一个就整体跳过，默认 {@code npm test} 不受影响）：
 * {@code TM_ADMIN_URL}、{@code TM_ADMIN_USERNAME}、{@code TM_ADMIN_PASSWORD}
 * ——后者必须是 **SUPER**，因为它要创建账号。首次启动的 tm-admin 用
 * {@code TM_ADMIN_BOOTSTRAP_*} 创建的就是 SUPER（表为空时生效一次）。
 *
 * <p><b>数据卫生</b>：所有账号用 {@code it_spa_} 前缀。后台没有「删账号」接口
 * （刻意没有），所以残留由 {@code tools/clean_it_leftovers.py} 按前缀清掉——
 * 它同时会清掉这些账号的会话与审计行。
 */

const ADMIN_URL = process.env.TM_ADMIN_URL ?? ''
const ADMIN_USERNAME = process.env.TM_ADMIN_USERNAME ?? ''
const ADMIN_PASSWORD = process.env.TM_ADMIN_PASSWORD ?? ''
const SUFFIX = `${Date.now().toString(16).slice(-6)}`

const suite = ADMIN_URL && ADMIN_USERNAME && ADMIN_PASSWORD ? describe : describe.skip

function newApi(token: string | null = null): { api: AdminApi; tokens: ReturnType<typeof createMemoryTokenStore> } {
  const tokens = createMemoryTokenStore(token)
  const client = new AdminClient({ baseUrl: ADMIN_URL, tokens })
  return { api: createAdminApi(client), tokens }
}

async function code(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise
  } catch (cause) {
    if (cause instanceof ApiError) {
      return cause
    }
    throw cause
  }
  throw new Error('期望这一次调用失败，但它成功了')
}

suite('后台 SPA 的 API 层（真实 tm-admin）', () => {
  let superApi: AdminApi
  let superTokens: ReturnType<typeof createMemoryTokenStore>
  let opsApi: AdminApi
  let opsAdmin: AdminView | null = null
  const opsUsername = `it_spa_ops_${SUFFIX}`
  const opsPassword = `it-spa-ops-${SUFFIX}`

  beforeAll(() => {
    const created = newApi()
    superApi = created.api
    superTokens = created.tokens
  })

  afterAll(() => {
    // 刻意不做「删账号」：后台没有这个接口，也不该有。残留由
    // tools/clean_it_leftovers.py 按 it_ 前缀清（见文件头的数据卫生一节）。
  })

  it('口令错 → 40101（HTTP 401），且不区分「用户名不存在」', async () => {
    const { api } = newApi()
    const error = await code(api.login(ADMIN_USERNAME, 'definitely-not-the-password'))
    expect(error.code).toBe(40101)
    expect(error.httpStatus).toBe(401)
  })

  it('登录成功：token 是库内会话（adm_ 前缀），角色来自服务端', async () => {
    const view = await superApi.login(ADMIN_USERNAME, ADMIN_PASSWORD)
    expect(view.token.startsWith('adm_')).toBe(true)
    expect(view.admin.username).toBe(ADMIN_USERNAME)
    expect(view.admin.role).toBe(1)
    // expires_at 必须是能解析的 Instant，并且在未来（8 小时后）
    const expiresAt = Date.parse(view.expires_at)
    expect(Number.isNaN(expiresAt)).toBe(false)
    expect(expiresAt).toBeGreaterThan(Date.now())
    superTokens.set(view.token)
  })

  it('GET /v1/admin/me 带上凭证即可读到「我是谁」（字段名逐个对得上）', async () => {
    const me = await superApi.me()
    expect(me.username).toBe(ADMIN_USERNAME)
    // id 是 64 位 Snowflake：必须以字符串形式到达（number 会丢掉最后两位）
    expect(me.admin_id).toMatch(/^\d{17,19}$/)
    expect(typeof me.display_name === 'string' || me.display_name === null).toBe(true)
    expect(typeof me.role).toBe('number')
    expect(typeof me.status).toBe('number')
    expect(Number.isNaN(Date.parse(me.created_at))).toBe(false)
  })

  it('把读到的 64 位 id 原样发回去能查到同一行（精度丢失在这里会表现成 40400）', async () => {
    const me = await superApi.me()
    const again = await superApi.getAdmin(me.admin_id)
    expect(again.username).toBe(ADMIN_USERNAME)
    expect(again.admin_id).toBe(me.admin_id)
  })

  it('没有凭证 → 40101/40102（而不是 500 或空 200）', async () => {
    const { api } = newApi()
    const error = await code(api.me())
    expect([40101, 40102]).toContain(error.code)
    expect(error.httpStatus).toBe(401)
  })

  it('actor_type=2 真的只回 Agent（参数名写错的话这里会拿到人）', async () => {
    const page = await superApi.listActors({ limit: 5, actorType: 2 })
    expect(Array.isArray(page.items)).toBe(true)
    expect(typeof page.has_more).toBe('boolean')
    for (const row of page.items) {
      expect(row.actor_type).toBe(2)
      expect(row.agent_profile, `agent_profile 应该非空：${row.handle}`).not.toBeNull()
    }
  })

  it('actor_type=1 的 agent_profile 是 null（人没有 Agent 扩展）', async () => {
    const page = await superApi.listActors({ limit: 5, actorType: 1 })
    for (const row of page.items) {
      expect(row.actor_type).toBe(1)
      expect(row.agent_profile).toBeNull()
    }
  })

  it('limit 被服务端夹取（要 1000 条不会真的回 1000 条），游标可继续翻页', async () => {
    const first = await superApi.listActors({ limit: 1000 })
    expect(first.items.length).toBeLessThanOrEqual(1000)
    if (first.has_more) {
      expect(first.next_cursor).not.toBeNull()
      const second = await superApi.listActors({ limit: 1000, cursor: first.next_cursor })
      const firstIds = new Set(first.items.map((row) => row.actor_id))
      for (const row of second.items) {
        // 游标翻页不该出现重复行（重复 = 服务端的游标条件写错了）
        expect(firstIds.has(row.actor_id)).toBe(false)
      }
    }
  })

  it('越界的筛选码值 → 40002（不是静默当成「不过滤」）', async () => {
    const error = await code(superApi.listActors({ actorType: 9 }))
    expect(error.code).toBe(40002)
  })

  it('不存在的参与者 → 40401，而不是空对象', async () => {
    const error = await code(superApi.getActor('9000000000000000000'))
    expect(error.code).toBe(40401)
  })

  it('审计可按动作过滤，admin_name 是快照', async () => {
    const page = await superApi.listAuditLogs({ limit: 10, action: 'ADMIN_LOGIN' })
    expect(page.items.length).toBeGreaterThan(0)
    for (const row of page.items) {
      expect(row.action).toBe('ADMIN_LOGIN')
      expect(row.admin_name.length).toBeGreaterThan(0)
      expect(Number.isNaN(Date.parse(row.created_at))).toBe(false)
    }
  })

  it('账号列表：role/status 是码值，口令相关字段不存在', async () => {
    const page = await superApi.listAdmins({ limit: 50 })
    const found = page.items.find((row) => row.username === ADMIN_USERNAME)
    expect(found, '列表里应该有刚登录的那个账号').toBeDefined()
    const raw = JSON.stringify(page.items)
    expect(raw).not.toContain('password')
    expect(raw).not.toContain('failed_attempts')
    expect(raw).not.toContain('locked_until')
  })

  it('建账号：display_name 走 snake_case，口令只进不回', async () => {
    opsAdmin = await superApi.createAdmin({
      username: opsUsername,
      password: opsPassword,
      displayName: '运维（SPA 联调）',
      role: 2,
    })
    expect(opsAdmin.username).toBe(opsUsername)
    expect(opsAdmin.display_name).toBe('运维（SPA 联调）')
    expect(opsAdmin.role).toBe(2)
    expect(JSON.stringify(opsAdmin)).not.toContain(opsPassword)
  })

  it('重名 → 40909（而不是 500 或静默成功）', async () => {
    const error = await code(
      superApi.createAdmin({ username: opsUsername, password: opsPassword, displayName: null, role: 2 }),
    )
    expect(error.code).toBe(40909)
  })

  it('OPS 能干活（列参与者），但读不到账号管理 → 40302', async () => {
    const { api, tokens } = newApi()
    const view = await api.login(opsUsername, opsPassword)
    expect(view.admin.role).toBe(2)
    tokens.set(view.token)

    // 「日常那一页」必须能用：否则分级就变成了「OPS 什么都做不了」
    const actors = await api.listActors({ limit: 1 })
    expect(Array.isArray(actors.items)).toBe(true)

    const denied = await code(api.listAdmins())
    expect(denied.code).toBe(40302)
    expect(denied.httpStatus).toBe(403)

    // 会话留着给下一条用例用（它要验证「停用立刻生效」）
    opsApi = api
  })

  it('SUPER 停用 OPS → 该 OPS 的会话立刻不可用（不是「下次登录才被拒」）', async () => {
    expect(opsAdmin).not.toBeNull()
    const updated = await superApi.setAdminStatus(opsAdmin!.admin_id, 2)
    expect(updated.status).toBe(2)

    const error = await code(opsApi.me())
    expect([40102, 40301]).toContain(error.code)
    expect(error.httpStatus).toBe(401)
  })

  it('审计里能查到这次封禁的前后经过（target_type=ADMIN 的两次动作）', async () => {
    const page = await superApi.listAuditLogs({
      limit: 20,
      targetType: 'ADMIN',
      targetId: opsAdmin!.admin_id,
    })
    const actions = page.items.map((row) => row.action)
    expect(actions).toContain('ADMIN_CREATE')
    expect(actions).toContain('ADMIN_STATUS')
    for (const row of page.items) {
      expect(row.target_type).toBe('ADMIN')
      expect(row.target_id).toBe(opsAdmin!.admin_id)
      // detail 是 JSON 文本（服务端刻意不解析成对象）
      expect(row.detail).not.toBeNull()
    }

    // 这个对象上的行分两类，而两类都是对的：
    //   ① 别人改它（建号 / 停用）——admin_name 是操作者；
    //   ② 它自己的登录——ADMIN_LOGIN 的目标就是管理员自己。
    // 第一版断言写了「所有行的 admin_name 都等于 SUPER」，就是漏了第 ② 类：
    // 停用之后它还能登录一次（停用前签发的那次），那行同样落在这个 target 上。
    const bySuper = page.items.filter((row) => row.admin_name === ADMIN_USERNAME).map((row) => row.action)
    expect(bySuper).toEqual(expect.arrayContaining(['ADMIN_CREATE', 'ADMIN_STATUS']))
    expect(page.items.some((row) => row.admin_name === opsUsername)).toBe(true)
  })

  it('不能停用自己 → 40904（界面上那个按钮是禁用的，但服务端也必须拦）', async () => {
    const me = await superApi.me()
    const error = await code(superApi.setAdminStatus(me.admin_id, 2))
    expect(error.code).toBe(40904)
  })

  it('登出之后旧凭证立刻失效（库里的那一行被删掉了）', async () => {
    const { api, tokens } = newApi()
    const view = await api.login(ADMIN_USERNAME, ADMIN_PASSWORD)
    tokens.set(view.token)
    await api.me()

    await api.logout()
    const error = await code(api.me())
    expect(error.code).toBe(40102)
  })

  it('会话失效会清掉本地凭证（客户端自己的清场逻辑，在真实 401 上验证一次）', async () => {
    const tokens = createMemoryTokenStore('adm_this_is_not_a_session')
    const client = new AdminClient({ baseUrl: ADMIN_URL, tokens })
    await client.request(ENDPOINTS.me).catch(() => undefined)
    expect(tokens.get()).toBeNull()
  })
})
