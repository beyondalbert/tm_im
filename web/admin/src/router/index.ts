import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import ActorDetailView from '@/views/ActorDetailView.vue'
import ActorListView from '@/views/ActorListView.vue'
import AdminAccountListView from '@/views/AdminAccountListView.vue'
import AuditLogListView from '@/views/AuditLogListView.vue'
import ForbiddenView from '@/views/ForbiddenView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import PostListView from '@/views/PostListView.vue'

/**
 * 路由即菜单。
 *
 * <p>侧边栏由 {@link NAV} 渲染，而它的名字必须能在路由表里找到——这样
 * 「有了页面但忘了加菜单」在结构上就不可能发生。{@code superOnly} 出现在
 * 路由 meta 与菜单两处是**一处多余**，但它的代价是一次类型检查，
 * 收益是「漏标一处」不会静默放行：守卫读 meta、菜单读 NAV，
 * 两边都读到同一个 {@code superOnly} 语义。
 *
 * <p>history 模式 + {@code base} 必须与构建的 {@code base: '/admin/'}
 * 一致（`vite.config.ts`）。不一致的表现是刷新页面回到登录页——
 * 因为路由把 {@code /admin/actors} 解析成了它不认识的一段路径。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { public: true, title: '登录', menu: false },
  },
  {
    path: '/',
    component: () => import('@/layout/AdminLayout.vue'),
    children: [
      { path: '', redirect: { name: 'actors' } },
      {
        path: 'actors',
        name: 'actors',
        component: ActorListView,
        meta: { title: '参与者', icon: 'User' },
      },
      {
        path: 'actors/:actorId',
        name: 'actor-detail',
        component: ActorDetailView,
        props: true,
        meta: { title: '参与者详情', menu: false, parent: 'actors' },
      },
      {
        path: 'posts',
        name: 'posts',
        component: PostListView,
        meta: { title: '内容审核', icon: 'Document' },
      },
      {
        path: 'audit-logs',
        name: 'audit-logs',
        component: AuditLogListView,
        meta: { title: '审计日志', icon: 'Tickets' },
      },
      {
        path: 'accounts',
        name: 'accounts',
        component: AdminAccountListView,
        meta: { title: '后台账号', icon: 'Lock', superOnly: true },
      },
      {
        path: 'forbidden',
        name: 'forbidden',
        component: ForbiddenView,
        meta: { title: '无权限', menu: false },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: NotFoundView,
    meta: { public: true, title: '页面不存在', menu: false },
  },
]

export interface NavItem {
  name: string
  title: string
  icon: string
  superOnly: boolean
}

/**
 * 侧边栏。**唯一**的菜单清单，且只列「后台的四个工作面」——
 * 登录页与详情页不在菜单里（{@code menu: false}）。
 */
export const NAV: readonly NavItem[] = [
  { name: 'actors', title: '参与者', icon: 'User', superOnly: false },
  { name: 'posts', title: '内容审核', icon: 'Document', superOnly: false },
  { name: 'audit-logs', title: '审计日志', icon: 'Tickets', superOnly: false },
  { name: 'accounts', title: '后台账号', icon: 'Lock', superOnly: true },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    return true
  }
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.admin) {
    try {
      await auth.loadMe()
    } catch {
      // 会话已失效（client 里已清掉本地凭证），回登录页。
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  if (to.meta.superOnly && !auth.isSuper) {
    return { name: 'forbidden' }
  }
  return true
})
