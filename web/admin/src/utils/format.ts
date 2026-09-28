/**
 * 时间与 JSON 的展示。
 *
 * <p><b>时间为什么要在前端再格式化一次</b>：服务端给的是 ISO-8601 的 UTC
 * （{@code 2026-09-28T02:41:00Z}）。直接显示它有两个问题：运营要自己减 8 小时；
 * 而 UTC 与「库里的墙上时间」不同——库里的 {@code created_at} 是按
 * {@code tm.time.zone}（默认 Asia/Shanghai）写的墙上时间，服务端把它转成了
 * Instant。前端显示时用它所在机器的时区还原，这在开发机与运营机上一致
 * （都在 +08:00）；真要跨时区办公，这个函数是唯一需要改的地方。
 */

export function formatInstant(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    // 解析不了就原样显示：伪造一个「1970」比露出原始字符串更难排查
    return value
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ` +
    `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`
  )
}

/** 审计 {@code detail} 是 JSON 文本：能美化就美化，不能就原样。 */
export function prettyJson(text: string | null | undefined): string {
  if (!text) {
    return '—'
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

/** 动态内容在表格里只显示一行：换行会把行高撑开，几百行之后就没法扫读了。 */
export function oneLine(text: string | null | undefined, max = 80): string {
  if (!text) {
    return '—'
  }
  const flat = text.replace(/\s+/g, ' ').trim()
  return flat.length > max ? `${flat.slice(0, max)}…` : flat
}
