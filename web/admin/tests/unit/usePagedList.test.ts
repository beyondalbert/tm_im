import { describe, expect, it, vi } from 'vitest'

import { usePagedList, type Page } from '@/composables/usePagedList'

/**
 * 游标翻页的组合子。
 *
 * <p>三条断言对应三个**真实的界面缺陷**（见 composables/usePagedList.ts 的注释）：
 * 连点两下「加载更多」会把同一页追加两次；改了筛选还在旧游标上翻；
 * 一次网络抖动把已经拿到的行清空。三者都不报错，只是让人对着表格发懵。
 */

function page<T>(items: T[], next: string | null, hasMore: boolean): Page<T> {
  return { items, next_cursor: next, has_more: hasMore }
}

describe('usePagedList', () => {
  it('第一页替换、后续页追加，并记住游标', async () => {
    const fetchPage = vi
      .fn<(cursor: string | null) => Promise<Page<number>>>()
      .mockResolvedValueOnce(page([1, 2], 'c1', true))
      .mockResolvedValueOnce(page([3, 4], null, false))
    const list = usePagedList<number>(fetchPage)

    await list.reload()
    expect(list.items.value).toEqual([1, 2])
    expect(list.cursor.value).toBe('c1')
    expect(list.hasMore.value).toBe(true)

    await list.loadMore()
    expect(list.items.value).toEqual([1, 2, 3, 4])
    expect(list.hasMore.value).toBe(false)
    expect(fetchPage).toHaveBeenLastCalledWith('c1')
  })

  it('has_more=false 时 loadMore 不再发请求', async () => {
    const fetchPage = vi.fn(async () => page([1], null, false))
    const list = usePagedList<number>(fetchPage)

    await list.reload()
    await list.loadMore()
    expect(fetchPage).toHaveBeenCalledTimes(1)
  })

  it('请求进行中忽略重复调用（连点两下「加载更多」不会出现重复行）', async () => {
    let release: (value: Page<number>) => void = () => undefined
    const pending = new Promise<Page<number>>((resolve) => {
      release = resolve
    })
    const fetchPage = vi
      .fn<(cursor: string | null) => Promise<Page<number>>>()
      .mockResolvedValueOnce(page([1], 'c1', true))
      .mockReturnValueOnce(pending)
    const list = usePagedList<number>(fetchPage)

    await list.reload()
    const first = list.loadMore()
    const second = list.loadMore()
    release(page([2], null, false))
    await Promise.all([first, second])

    expect(fetchPage).toHaveBeenCalledTimes(2)
    expect(list.items.value).toEqual([1, 2])
  })

  it('reload 丢掉旧游标与旧行（否则会拿到「新筛选的第一页 + 旧筛选的第二页」）', async () => {
    const fetchPage = vi
      .fn<(cursor: string | null) => Promise<Page<number>>>()
      .mockResolvedValueOnce(page([1, 2], 'c1', true))
      .mockResolvedValueOnce(page([9], null, false))
    const list = usePagedList<number>(fetchPage)

    await list.reload()
    await list.reload()
    expect(list.items.value).toEqual([9])
    expect(list.cursor.value).toBeNull()
    expect(fetchPage).toHaveBeenLastCalledWith(null)
  })

  it('翻页失败保留已有行，只记错误（一次网络抖动不该清空工作面）', async () => {
    const fetchPage = vi
      .fn<(cursor: string | null) => Promise<Page<number>>>()
      .mockResolvedValueOnce(page([1, 2], 'c1', true))
      .mockRejectedValueOnce(new Error('boom'))
    const list = usePagedList<number>(fetchPage)

    await list.reload()
    await list.loadMore()
    expect(list.items.value).toEqual([1, 2])
    expect(list.error.value).toBe('boom')
  })

  it('第一页就失败时清空并记错误：服务端说「没有数据」与「没拿到数据」不能混为一谈', async () => {
    const fetchPage = vi.fn(async () => {
      throw new Error('503')
    })
    const list = usePagedList<number>(fetchPage)

    await list.reload()
    expect(list.items.value).toEqual([])
    expect(list.hasMore.value).toBe(false)
    expect(list.error.value).toBe('503')
  })
})
