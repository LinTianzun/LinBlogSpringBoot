import { createRouter, createWebHistory, type RouterScrollBehavior } from 'vue-router'
import type { PermissionKey } from '@/types/api'
import { useSessionStore } from '@/stores/session'

/**
 * 路由表（§6.1）。所有页面均为路由级动态 import（独立 chunk）。
 * meta.permission 语义为「或」：命中任一键即可进入；超管在 session.can 内短路。
 * 无权限一律重定向 /404，不暴露后台入口存在（§6.1 安全要求）。
 */

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题（afterEach 写 document.title） */
    title: string
    /** 命中任一权限键方可进入；缺省 = 公开 */
    permission?: PermissionKey[]
    /** 认证相关页（/login-register、/reset-password）：noindex + 已登录跳首页 */
    authPage?: boolean
    /** afterEach 写 robots meta（缺省 index,follow） */
    noindex?: boolean
  }
}

const scrollBehavior: RouterScrollBehavior = (to, _from, savedPosition) => {
  if (savedPosition) return savedPosition
  if (to.hash) return { el: to.hash, behavior: 'smooth' }
  return { top: 0 }
}

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior,
  routes: [
    {
      path: '/',
      component: () => import('@/views/portal/PortalLayout.vue'),
      children: [
        { path: '', name: 'portal-home', component: () => import('@/views/portal/PortalHome.vue'), meta: { title: '首页' } },
        { path: 'articles', name: 'article-list', component: () => import('@/views/portal/ArticleList.vue'), meta: { title: '文章' } },
        { path: 'articles/:id(\\d+)', name: 'article-detail', component: () => import('@/views/portal/ArticleDetail.vue'), meta: { title: '文章详情' } },
        { path: 'gallery', name: 'gallery-wall', component: () => import('@/views/portal/GalleryWall.vue'), meta: { title: '画廊' } },
        { path: 'gallery/:id(\\d+)', name: 'gallery-detail', component: () => import('@/views/portal/GalleryDetail.vue'), meta: { title: '画廊详情' } },
        { path: 'diary', name: 'diary-wall', component: () => import('@/views/portal/DiaryWall.vue'), meta: { title: '日记' } },
        { path: 'guestbook', name: 'guestbook-wall', component: () => import('@/views/portal/GuestbookWall.vue'), meta: { title: '留言墙' } },
        { path: 'login-register', name: 'login-register', component: () => import('@/views/portal/LoginRegister.vue'), meta: { title: '登录 / 注册', authPage: true, noindex: true } },
        { path: 'reset-password', name: 'reset-password', component: () => import('@/views/portal/ResetPassword.vue'), meta: { title: '重置密码', authPage: true, noindex: true } },
      ],
    },
    {
      path: '/admin',
      component: () => import('@/views/admin/AdminLayout.vue'),
      children: [
        { path: '', name: 'admin-overview', component: () => import('@/views/admin/OverView.vue'), meta: { title: '总览', permission: ['overview.view'], noindex: true } },
        { path: 'articles', name: 'admin-articles', component: () => import('@/views/admin/ArticleManage.vue'), meta: { title: '文章管理', permission: ['article.viewAll', 'article.viewOwn'], noindex: true } },
        { path: 'articles/edit/:id?', name: 'admin-article-edit', component: () => import('@/views/admin/ArticleEdit.vue'), meta: { title: '文章编辑', permission: ['article.create', 'article.manageOwn'], noindex: true } },
        { path: 'gallery', name: 'admin-gallery', component: () => import('@/views/admin/GalleryManage.vue'), meta: { title: '画廊管理', permission: ['gallery.viewAll', 'gallery.viewOwn'], noindex: true } },
        { path: 'gallery/edit/:id?', name: 'admin-gallery-edit', component: () => import('@/views/admin/GalleryEdit.vue'), meta: { title: '画廊编辑', permission: ['gallery.create', 'gallery.manageOwn'], noindex: true } },
        { path: 'diaries', name: 'admin-diaries', component: () => import('@/views/admin/DiaryManage.vue'), meta: { title: '日记管理', permission: ['diary.viewAll', 'diary.manageOwn'], noindex: true } },
        { path: 'comments', name: 'admin-comments', component: () => import('@/views/admin/CommentManage.vue'), meta: { title: '评论管理', permission: ['comment.viewAll'], noindex: true } },
        { path: 'reports', name: 'admin-reports', component: () => import('@/views/admin/ReportManage.vue'), meta: { title: '举报处理', permission: ['comment.moderate'], noindex: true } },
        { path: 'messages', name: 'admin-messages', component: () => import('@/views/admin/MessageManage.vue'), meta: { title: '私信', permission: ['message.private'], noindex: true } },
        { path: 'guestbook', name: 'admin-guestbook', component: () => import('@/views/admin/GuestbookManage.vue'), meta: { title: '留言墙管理', permission: ['message.wallModerate'], noindex: true } },
        { path: 'files', name: 'admin-files', component: () => import('@/views/admin/FileManage.vue'), meta: { title: '文件管理', permission: ['file.view'], noindex: true } },
        { path: 'users', name: 'admin-users', component: () => import('@/views/admin/UserManage.vue'), meta: { title: '用户管理', permission: ['user.view'], noindex: true } },
        { path: 'permissions', name: 'admin-permissions', component: () => import('@/views/admin/PermissionManage.vue'), meta: { title: '权限管理', permission: ['permission.view'], noindex: true } },
        { path: 'logs', name: 'admin-logs', component: () => import('@/views/admin/LogView.vue'), meta: { title: '系统日志', permission: ['systemLog.view'], noindex: true } },
        { path: 'settings', name: 'admin-settings', component: () => import('@/views/admin/SettingManage.vue'), meta: { title: '系统设置', permission: ['system.setting'], noindex: true } },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/portal/NotFound.vue'),
      meta: { title: '页面不存在' },
    },
  ],
})

/** 已登录访问认证页 → 回门户首页 */
function redirectLoggedInAuthPages(): boolean {
  const session = useSessionStore()
  if (session.isLoggedIn && session.loaded) {
    return { path: '/', replace: true } as unknown as boolean
  }
  return true
}

router.beforeEach(async (to) => {
  const session = useSessionStore()
  await session.init()

  if (to.meta.authPage) return redirectLoggedInAuthPages()

  const required = to.meta.permission
  // 无权限/未登录一律按「不存在」处理，避免暴露后台路由存在
  if (required && required.length > 0 && !session.canAny(required)) {
    return { name: 'not-found', replace: true }
  }
  return true
})

const ROBOTS_INDEX = 'index, follow'
const ROBOTS_NOINDEX = 'noindex, nofollow'

function setRobotsMeta(content: string): void {
  let tag = document.querySelector<HTMLMetaElement>('meta[name="robots"]')
  if (!tag) {
    tag = document.createElement('meta')
    tag.name = 'robots'
    document.head.appendChild(tag)
  }
  tag.content = content
}

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · LinBlog` : 'LinBlog'
  // §6.1：后台与认证页 noindex，其余 index,follow
  const noindex = to.meta.noindex === true || to.path.startsWith('/admin')
  setRobotsMeta(noindex ? ROBOTS_NOINDEX : ROBOTS_INDEX)
})

export default router
