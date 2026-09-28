import { resolvePath, type Endpoint } from './endpoints'
import { parseJson } from './json'
import { describeCode, isSessionLost } from './errors'

/**
 * 后台 API 客户端。
 *
 * <p>它只做四件事，每件都对着服务端的一条契约：
 *
 * <ol>
 *   <li><b>剥信封</b>。响应体永远是 {@code {code, message, data}}，文件里真实的
 *       字段还有服务的 {@code ok}（{@code ApiResponse#isOk} 被 Jackson 序列化了），
 *       客户端不使用它——成功判据只有 {@code code === 0}。
 *       业务失败也是 HTTP 200（{@code ApiExceptionHandler} 的类注释）。
 *       所以「HTTP 通了」不等于「成功了」，判断成功的唯一依据是 {@code code === 0}。
 *       反过来，401/403/429/500 这些非 200 也**带着同一个信封**，
 *       因此这里先解析信封、再回退到 HTTP 状态码——而不是拿状态码当成功判据。</li>
 *   <li><b>带上凭证</b>。{@code Authorization: Bearer adm_…}。凭证来自
 *       {@link TokenStore}（浏览器里是 localStorage，测试里是内存）——
 *       把它做成可注入的接口是为了让「线上跑的那段取 token 的代码」在
 *       Node 里也能跑（见 {@code tests/live/}）。</li>
 *   <li><b>会话失效即清场</b>。40101 / 40102 / 40103 意味着本地这个
 *       {@code adm_} 字符串已经不可用，继续拿着它请求只会得到同一批错误，
 *       所以这里清掉它并通知上层回登录页。</li>
 *   <li><b>保真地解析</b>。所有主键是 64 位整数，而 JS 的 number 只精确到
 *       2^53（19 位 id 的最后两位会被改写）。因此这里用
 *       {@link parseJson}（把超长整数保真成字符串）而不是 {@code JSON.parse}——
 *       这不是洁癖：拿被改写过的 id 去请求会得到 40401「参与者不存在」。</li>
 *   <li><b>路径参数与查询串</b>。查询串里 {@code null} / {@code undefined} /
 *       空串一律**不发**这个参数——发 {@code ?status=} 会让服务端的
 *       {@code Integer} 绑定失败（40002），而「不筛选」与「筛选空值」
 *       在界面上是同一个动作。</li>
 * </ol>
 *
 * <p>分页没有「总数」：服务端只给 {@code next_cursor} / {@code has_more}
 * （游标翻页，见 09-admin-api.md）。所以这里不存在 {@code page/size/total}
 * 的抽象——那会让界面显示一个服务端从来没算过的页码。
 */

export interface Envelope<T> {
  code: number
  message: string
  data: T
}

export interface TokenStore {
  get(): string | null
  set(token: string): void
  clear(): void
}

export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number
  readonly serverMessage: string | null

  constructor(code: number, httpStatus: number, serverMessage: string | null, detail?: string) {
    const text = describeCode(code, serverMessage)
    super(detail ? `${text} — ${detail}` : text)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
    this.serverMessage = serverMessage
  }
}

export interface ClientOptions {
  /** 生产环境留空（同源）；开发期也可留空（Vite 代理）。显式给值只用于测试与跨域调试。 */
  baseUrl?: string
  tokens: TokenStore
  fetchImpl?: typeof fetch
  /** 会话失效时调用一次（清路由、回登录页）。 */
  onSessionLost?: () => void
}

export type QueryValue = string | number | boolean | null | undefined

export interface RequestOptions {
  pathParams?: Record<string, string | number>
  query?: Record<string, QueryValue>
  body?: unknown
  /**
   * 是否附带本地凭证，默认 {@code true}。
   *
   * <p>**登录**是唯一该关掉它的地方（{@code false}）：带着一个已经失效的
   * {@code adm_} 去登录不会改变结果，但会让服务端日志里出现「登录请求带着凭证」
   * 这种看不明白的形状。反过来说，**登出**必须带（{@code true}）——
   * 服务端就是靠这个头找到要删的那一行。
   */
  withCredential?: boolean
}

export class AdminClient {
  private readonly baseUrl: string
  private readonly tokens: TokenStore
  private readonly fetchImpl: typeof fetch
  private readonly onSessionLost: (() => void) | undefined

  constructor(options: ClientOptions) {
    this.baseUrl = options.baseUrl ?? ''
    this.tokens = options.tokens
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis)
    this.onSessionLost = options.onSessionLost
  }

  async request<T>(endpoint: Endpoint, options: RequestOptions = {}): Promise<T> {
    const url = this.url(endpoint.path, options)
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    const withCredential = options.withCredential !== false
    const token = this.tokens.get()
    if (withCredential && token) {
      headers.Authorization = `Bearer ${token}`
    }

    let response: Response
    try {
      response = await this.fetchImpl(url, {
        method: endpoint.method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      })
    } catch (cause) {
      // 网络层失败（服务端没起、端口写错、被代理拦掉）。码给 -1：
      // 它不是服务端错误码空间里的任何一个值，所以不可能与真实错误混淆。
      throw new ApiError(-1, 0, null, cause instanceof Error ? cause.message : String(cause))
    }

    const envelope = await this.readEnvelope<T>(response)
    if (envelope.code === 0) {
      return envelope.data
    }
    if (isSessionLost(envelope.code)) {
      this.tokens.clear()
      this.onSessionLost?.()
    }
    throw new ApiError(envelope.code, response.status, envelope.message)
  }

  /** 请求体是 JSON 文本的接口（审计 detail）用它取原文；当前 UI 直接展示 detail 字段，故未使用。 */
  url(path: string, options: RequestOptions = {}): string {
    const resolved = resolvePath(path, options.pathParams)
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(options.query ?? {})) {
      if (value === null || value === undefined || value === '') {
        continue
      }
      search.append(key, String(value))
    }
    const queryString = search.toString()
    return `${this.baseUrl}${resolved}${queryString ? `?${queryString}` : ''}`
  }

  /**
   * 把响应体读成信封。
   *
   * <p>「读不出来」也必须是一条明确的错误：反向代理返回 HTML、网关返回
   * {@code 502 Bad Gateway}、或者中间有人改了返回结构——这些情况下
   * {@code response.json()} 会抛异常。若把异常直接冒到界面，表现是
   * 「点了按钮什么都没发生」；所以这里统一翻成 {@code 50000} 并说明
   * 「响应不是统一信封」。
   */
  private async readEnvelope<T>(response: Response): Promise<Envelope<T>> {
    const text = await response.text()
    try {
      const parsed = parseJson<Partial<Envelope<T>>>(text)
      if (typeof parsed !== 'object' || parsed === null || typeof parsed.code !== 'number') {
        throw new Error('缺少 code 字段')
      }
      return {
        code: parsed.code,
        message: typeof parsed.message === 'string' ? parsed.message : '',
        data: parsed.data as T,
      }
    } catch (cause) {
      const preview = text.slice(0, 120).replace(/\s+/g, ' ')
      throw new ApiError(
        50000,
        response.status,
        null,
        `HTTP ${response.status} 的响应不是统一信封：${preview}${
          cause instanceof Error ? `（${cause.message}）` : ''
        }`,
      )
    }
  }
}
