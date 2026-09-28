import { describe, expect, it } from 'vitest'

import { ADMIN_ERROR_MESSAGES, SESSION_LOST_CODES, describeCode, isSessionLost } from '@/api/errors'

/**
 * 错误码表。
 *
 * <p>这里只钉两件事：未知码也必须把码露出来（否则界面显示「未知错误」而没人能查）、
 * 以及「哪些码意味着会话没了」这个集合不能随手改大（改大的后果是一个权限错误
 * 把操作员踢回登录页）。
 *
 * <p>「服务端会抛哪些码、表里有没有漏」是 {@code tools/verify_admin_spa.py} 的活：
 * 它从 {@code AdminService} / {@code tm-api-admin} 里扫出错误码再与这个文件比对——
 * 漏一个的表现是界面上出现「未知错误 (40904)」，而 40904 正是
 * 「不能停用自己」这种必须说清楚的事。
 */
describe('错误码文案', () => {
  it('每一个已知码都有一句可执行的中文，而不是英文短语', () => {
    for (const [code, text] of Object.entries(ADMIN_ERROR_MESSAGES)) {
      expect(text.length, `码 ${code}`).toBeGreaterThan(4)
      expect(text, `码 ${code} 不该照抄服务端的英文 message`).not.toMatch(/^[a-z ]+$/)
    }
  })

  it('未知码把码本身露出来，并附上服务端文案（便于查）', () => {
    expect(describeCode(40999)).toContain('40999')
    expect(describeCode(40999, 'weird thing')).toContain('weird thing')
  })

  it('0 是成功，不是错误', () => {
    expect(describeCode(0)).toBe('成功')
  })

  it('会话失效只认 401xx 三兄弟', () => {
    expect([...SESSION_LOST_CODES]).toEqual([40101, 40102, 40103])
    expect(isSessionLost(40102)).toBe(true)
    // 40302（权限不够）不是会话失效：它的正确反应是「这一页别点」，不是回登录页
    expect(isSessionLost(40302)).toBe(false)
    expect(isSessionLost(42901)).toBe(false)
  })
})
