import { ref, type Ref } from 'vue'

/**
 * 游标翻页。
 *
 * <p>后台的列表接口只有 {@code next_cursor} / {@code has_more}，没有总数
 * （见 09-admin-api.md 的「为什么没有总数」）。所以界面上不存在"第 3 页"，
 * 只有「加载更多」——这个组合子就是它的全部状态。
 *
 * <p>三处细节是**缺陷驱动**的，不是风格问题：
 *
 * <ul>
 *   <li>{@code loading} 期间忽略重复调用。翻页按钮被点两下就会把同一页
 *       追加两次，而服务端的游标是幂等的（同一游标取同一页），所以
 *       重复请求不会报错，只会让列表里出现重复行——看起来像「数据重复」。</li>
 *   <li>{@code reload()} 一定要丢掉旧游标与旧行。筛选条件变了却还在
 *       旧的游标上继续翻，得到的是「新筛选条件的第一页 + 旧筛选条件的第二页」。</li>
 *   <li>失败**不**清空列表，只置 error。把已经拿到的行丢掉，等于让一次
 *       网络抖动把操作员的工作面清空。</li>
 * </ul>
 */

export interface Page<T> {
  items: T[]
  next_cursor: string | null
  has_more: boolean
}

export interface PagedList<T> {
  items: Ref<T[]>
  cursor: Ref<string | null>
  hasMore: Ref<boolean>
  loading: Ref<boolean>
  error: Ref<string | null>
  loaded: Ref<boolean>
  reload(): Promise<void>
  loadMore(): Promise<void>
}

export function usePagedList<T>(
  fetchPage: (cursor: string | null) => Promise<Page<T>>,
): PagedList<T> {
  const items = ref([]) as Ref<T[]>
  const cursor = ref<string | null>(null)
  const hasMore = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const loaded = ref(false)

  async function page(nextCursor: string | null): Promise<void> {
    if (loading.value) {
      return
    }
    loading.value = true
    error.value = null
    try {
      const result = await fetchPage(nextCursor)
      items.value = nextCursor === null ? result.items : [...items.value, ...result.items]
      cursor.value = result.next_cursor
      hasMore.value = result.has_more
      loaded.value = true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : String(cause)
      if (nextCursor === null) {
        items.value = []
        hasMore.value = false
        cursor.value = null
      }
    } finally {
      loading.value = false
    }
  }

  return {
    items,
    cursor,
    hasMore,
    loading,
    error,
    loaded,
    reload: () => page(null),
    loadMore: () => (hasMore.value ? page(cursor.value) : Promise.resolve()),
  }
}

export function pageSize(): number {
  return 20
}
