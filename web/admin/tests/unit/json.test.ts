import { describe, expect, it } from 'vitest'

import { asBigId, parseJson, quoteBigIntegers } from '@/api/json'

/**
 * 64 位整数的保真解析。
 *
 * <p>下面第一个用例里的数字**不是编的**：它是 2026-09-28 那次联调里
 * tm-admin.jar 真实返回的 {@code admin_id}
 * （`{"code":0,...,"admin_id":362810375490994176,...}`）。
 * 而 {@code JSON.parse} 把它读成 {@code 362810375490994180}——
 * 拿着那个数字去请求会回 40400，界面上显示「目标不存在」。
 *
 * <p>这条测试是**唯一**能在没有服务端的情况下钉住它的地方：类型检查看不见
 * （两边都是 number）、用小数字的单测也看不见。
 */
describe('quoteBigIntegers / parseJson', () => {
  it('真机上的 18 位 admin_id：原样保留（JSON.parse 会改写成 ...180）', () => {
    const text = '{"code":0,"data":{"admin_id":362810375490994176}}'

    // 先证明确实会坏：这是「为什么需要这个模块」的证据，而不是注释里的说法
    expect(JSON.parse(text).data.admin_id).toBe(362810375490994180)

    const parsed = parseJson<{ data: { admin_id: string } }>(text)
    expect(parsed.data.admin_id).toBe('362810375490994176')
  })

  it('嵌套结构、数组与 null 里都一样处理', () => {
    const text = '{"a":[362810375490994176,1,null],"b":{"c":[{"id":9999999999999999999}]}}'
    const parsed = parseJson<{ a: unknown[]; b: { c: { id: string }[] } }>(text)
    expect(parsed.a).toEqual(['362810375490994176', 1, null])
    expect(parsed.b.c[0]?.id).toBe('9999999999999999999')
  })

  it('小整数、小数与指数不动（它们不可能是 id）', () => {
    const text = '{"n":42,"f":1.5,"e":1e3,"neg":-7,"big_float":1234567890123456.5}'
    const parsed = parseJson<Record<string, number>>(text)
    expect(parsed.n).toBe(42)
    expect(parsed.f).toBe(1.5)
    expect(parsed.e).toBe(1000)
    expect(parsed.neg).toBe(-7)
    // 关键：带小数点的长数字不能被截成 "1234567890123456" + ".5"
    // （那样得到的是一段语法都不合法的 JSON，比精度问题更糟）
    expect(parsed.big_float).toBe(1234567890123456.5)
  })

  it('字符串里的数字不动（包括转义引号与看起来像字段名的内容）', () => {
    const text = '{"s":"362810375490994176","q":"他说\\"362810375490994176\\"","k":"a:b,c"}'
    const parsed = parseJson<Record<string, string>>(text)
    expect(parsed.s).toBe('362810375490994176')
    expect(parsed.q).toBe('他说"362810375490994176"')
    expect(parsed.k).toBe('a:b,c')
  })

  it('不改动其余字节：键名、空白、转义序列逐字保留', () => {
    const text = '{\n  "a": 362810375490994176,\n  "b": "x\\ny"\n}'
    const quoted = quoteBigIntegers(text)
    expect(quoted).toBe('{\n  "a": "362810375490994176",\n  "b": "x\\ny"\n}')
  })

  it('不是 JSON 的内容照样抛错（不悄悄吞掉坏响应）', () => {
    expect(() => parseJson('<html>Bad Gateway</html>')).toThrow()
  })
})

describe('asBigId', () => {
  it('接受纯数字字符串，去掉空白', () => {
    expect(asBigId(' 362810375490994176 ')).toBe('362810375490994176')
    expect(asBigId('1')).toBe('1')
  })

  it('拒绝非数字、0 与超出 20 位的输入（交给服务端只会得到 40002，看起来像服务端的问题）', () => {
    expect(asBigId('')).toBeNull()
    expect(asBigId('  ')).toBeNull()
    expect(asBigId('0')).toBeNull()
    expect(asBigId('12a')).toBeNull()
    expect(asBigId('-1')).toBeNull()
    expect(asBigId('1'.repeat(21))).toBeNull()
    expect(asBigId(undefined)).toBeNull()
    expect(asBigId(null)).toBeNull()
  })

  it('number 只在安全整数范围内才接受（避免了「先变成 number 再修正」的思路）', () => {
    expect(asBigId(42)).toBe('42')
    expect(asBigId(362810375490994176)).toBeNull()
  })
})
