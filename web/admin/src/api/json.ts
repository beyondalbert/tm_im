/**
 * 保真的 JSON 解析。
 *
 * <p><b>为什么 {@code JSON.parse} 不够</b>：本系统的所有主键都是 64 位
 * （Snowflake，18–19 位十进制），而 JS 的 {@code number} 只能精确表示到
 * {@code 2^53 - 1}（16 位）。真机上的证据：
 *
 * <pre>
 *   原文（服务端返回的 admin_id） 362810375490994176
 *   JSON.parse 之后              362810375490994180   ← 最后两位被改写
 * </pre>
 *
 * <p>后果不是「显示得难看」，而是**拿这个数字去请求会查不到东西**：
 * `PATCH /v1/admin/accounts/{id}/status` 会回 40400、`GET /v1/admin/actors/{id}`
 * 会回 40401，而界面上的表现是「这个人不存在」——一个看起来像数据问题的
 * 客户端缺陷。它在类型检查、单元测试（用小数字）与代码评审里都不会暴露，
 * 只有拿真实服务端的响应跑一次才会出现（{@code tests/live/} 就是这么发现的）。
 *
 * <p>修法是最小侵入的那一种：**在解析前把整数形式的超长数字字面量加引号**，
 * 让它们变成字符串（JSON 的数字是十进制文本，我们的改写不改变语义，
 * 只改变类型）。于是：
 *
 * <ul>
 *   <li>id 在 TS 里是 {@code string}（见 {@code BigId}），逐字节保真；</li>
 *   <li>计数、码值、比例这些真正的小数字仍是 {@code number}，不受影响；</li>
 *   <li>带小数点或指数的字面量（{@code 1.5}、{@code 1e3}）不动——它们不可能是 id；
 *       若只按「位数多」判断，{@code 1234567890123456.5} 会被截成两半，
 *       得到一个语法都不合法的 JSON，那是比精度更糟的失败。</li>
 * </ul>
 *
 * <p><b>为什么不在服务端把 id 序列化成字符串</b>：那会改动用户端（H5 / Agent SDK）
 * 已经在用的契约（{@code docs/integration/03-rest-api.md} 里的示例都是数字）。
 * 正确的长期做法是服务端显式声明这一点，而那是另一次契约变更；
 * 在那之前，前端必须自己做对——不管用哪种写法。
 */

/** 64 位整数在 JS 里的表示：字符串。18-19 位，超出 number 的精确范围。 */
export type BigId = string

/** 至少 16 位十进制才算「超出精确范围」。{@code 2^53 - 1} 是 16 位，所以从 16 位起处理。 */
const BIG_INT_DIGITS = 16

const NUMBER_LITERAL = /^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/

/** 整数形式的超长数字字面量 → 加引号；其余字节原样保留。 */
export function quoteBigIntegers(text: string): string {
  let out = ''
  let index = 0
  let inString = false

  while (index < text.length) {
    const char = text[index] as string

    if (inString) {
      out += char
      if (char === '\\') {
        // 转义序列整体跳过（否则 \" 会被当成字符串的结束）
        out += text[index + 1] ?? ''
        index += 2
        continue
      }
      if (char === '"') {
        inString = false
      }
      index += 1
      continue
    }

    if (char === '"') {
      inString = true
      out += char
      index += 1
      continue
    }

    if (char === '-' || (char >= '0' && char <= '9')) {
      const match = NUMBER_LITERAL.exec(text.slice(index))
      if (match) {
        const literal = match[0]
        const isInteger = !literal.includes('.') && !/[eE]/.test(literal)
        const digits = literal.replace(/^-/, '').length
        out += isInteger && digits >= BIG_INT_DIGITS ? `"${literal}"` : literal
        index += literal.length
        continue
      }
    }

    out += char
    index += 1
  }

  return out
}

/** 解析时把超长整数保真成字符串，其余与 {@code JSON.parse} 完全一致。 */
export function parseJson<T>(text: string): T {
  return JSON.parse(quoteBigIntegers(text)) as T
}

/**
 * 把界面上输入的 id 规范成 {@link BigId}（只接受纯数字）。
 *
 * <p>输入框里拿到的永远是字符串，而它绝不能被 {@code Number()} 转一遍
 * ——那正是精度丢失发生的地方。所以「填了非数字」直接当没填（{@code null}），
 * 而不是交给服务端去报 40002：那种报错看起来像服务端的问题。
 */
export function asBigId(value: unknown): BigId | null {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value > 0 ? String(value) : null
  }
  if (typeof value !== 'string') {
    return null
  }
  const trimmed = value.trim()
  return /^\d{1,20}$/.test(trimmed) && trimmed !== '0' ? trimmed : null
}
