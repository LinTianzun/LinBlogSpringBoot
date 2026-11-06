<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  Document,
  Files,
  FolderOpened,
  Key,
  Message,
  Notebook,
  Odometer,
  Picture,
  Setting,
  SwitchButton,
  Tickets,
  User,
  Warning,
  EditPen,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { useSessionStore } from '@/stores/session'
import ThemeSwitcher from '@/components/ThemeSwitcher.vue'
import type { PermissionKey } from '@/types/api'

/**
 * 后台壳（task-14：accent-ladder + Open plane）。
 * 侧栏坐在页面地面（非深色、非独立白卡），一条 1px 竖线分隔；当前项 = 极淡 accent tint + accent 深字胶囊，
 * 无左侧色条；顶部产品标识 + 名称 + 作用域；底部工具（主题切换/退出）+ 用户行。无顶栏，
 * 内容直接坐在画布地面（面包屑并入内容页标题区由各页承载）。
 * 菜单项按 session.canAny() 过滤，空组剔除；路由守卫保证无权限不可达，这里只管可见性。
 */

interface MenuDef {
  label: string
  path: string
  icon: Component
  permission: PermissionKey[]
}

const GROUP_DEFS: Array<{ label: string; items: MenuDef[] }> = [
  {
    label: '运营',
    items: [
      { label: '总览', path: '/admin', icon: Odometer, permission: ['overview.view'] },
      { label: '评论管理', path: '/admin/comments', icon: ChatDotRound, permission: ['comment.viewAll'] },
      { label: '举报处理', path: '/admin/reports', icon: Warning, permission: ['comment.moderate'] },
      { label: '私信', path: '/admin/messages', icon: Message, permission: ['message.private'] },
      { label: '留言墙管理', path: '/admin/guestbook', icon: Tickets, permission: ['message.wallModerate'] },
    ],
  },
  {
    label: '内容',
    items: [
      { label: '文章', path: '/admin/articles', icon: Document, permission: ['article.viewAll', 'article.viewOwn'] },
      { label: '画廊', path: '/admin/gallery', icon: Picture, permission: ['gallery.viewAll', 'gallery.viewOwn'] },
      { label: '日记', path: '/admin/diaries', icon: Notebook, permission: ['diary.viewAll', 'diary.manageOwn'] },
      { label: '标签', path: '/admin/labels', icon: EditPen, permission: ['label.manage'] },
      { label: '合集', path: '/admin/subsets', icon: Files, permission: ['article.viewAll', 'article.viewOwn', 'gallery.viewAll', 'gallery.viewOwn'] },
      { label: '文件', path: '/admin/files', icon: FolderOpened, permission: ['file.view'] },
    ],
  },
  {
    label: '系统',
    items: [
      { label: '用户', path: '/admin/users', icon: User, permission: ['user.view'] },
      { label: '权限', path: '/admin/permissions', icon: Key, permission: ['permission.view'] },
      { label: '日志', path: '/admin/logs', icon: Tickets, permission: ['systemLog.view'] },
      { label: '设置', path: '/admin/settings', icon: Setting, permission: ['system.setting'] },
    ],
  },
]

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const groups = computed(() =>
  GROUP_DEFS.map((group) => ({
    label: group.label,
    items: group.items.filter((item) => session.canAny(item.permission)),
  })).filter((group) => group.items.length > 0),
)

const userName = computed(() => session.user?.nickname || session.user?.username || '')
const userRole = computed(() => (session.user?.username ? `@${session.user.username}` : ''))

const userInitial = computed(() => {
  const name = session.user?.nickname || session.user?.username || ''
  return name ? name.slice(0, 1).toUpperCase() : '?'
})

async function handleLogout(): Promise<void> {
  await session.logout()
  void router.push('/')
}

function goProfile(): void {
  void router.push('/admin/profile')
}
</script>

<template>
  <div class="lb-app lb-admin-layout lb-ed">
    <aside class="lb-admin-sider" aria-label="后台导航">
      <!-- 产品标识 + 名称 + 作用域 -->
      <div class="lb-admin-brand">
        <RouterLink to="/" class="lb-brand-link" title="LinBlog 首页">
          <span class="lb-brand-mark" aria-hidden="true">L</span>
          <span class="lb-brand-text">
            <strong>LinBlog</strong>
            <em>内容后台</em>
          </span>
        </RouterLink>
      </div>

      <nav class="lb-admin-nav">
        <div v-for="group in groups" :key="group.label" class="lb-nav-group">
          <p class="lb-nav-group-label">{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="lb-nav-item"
            :class="{ 'is-active': route.path === item.path }"
          >
            <el-icon class="lb-nav-icon" :size="16"><component :is="item.icon" /></el-icon>
            <span class="lb-nav-text">{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>

      <!-- 底部工具 + 用户行 -->
      <div class="lb-admin-foot">
        <div class="lb-foot-tools">
          <ThemeSwitcher />
        </div>
        <el-dropdown v-if="session.user" trigger="click" placement="top-start" class="lb-user-wrap">
          <button class="lb-user-row" type="button" data-testid="admin-user-menu">
            <span class="lb-user-avatar">
              <img
                v-if="session.user?.avatarMediaId"
                :src="`/api/v1/media/id/${session.user.avatarMediaId}?width=320`"
                alt=""
              />
              <template v-else>{{ userInitial }}</template>
            </span>
            <span class="lb-user-meta">
              <span class="lb-user-name">{{ userName }}</span>
              <span class="lb-user-role">{{ userRole }}</span>
            </span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                <span class="lb-user-role">{{ session.user.username }}</span>
              </el-dropdown-item>
              <el-dropdown-item divided data-testid="admin-user-profile" @click="goProfile">
                <el-icon><User /></el-icon>个人中心
              </el-dropdown-item>
              <el-dropdown-item @click="handleLogout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </aside>

    <main class="lb-admin-content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
/* ============ 亮卡片壳（方案 A：暖米底 + 白卡 + 柔和阴影 + 主题色点缀） ============ */
.lb-admin-layout {
  display: grid;
  grid-template-columns: var(--app-sidebar-width) 1fr;
  min-height: 100vh;
  background: var(--ed-paper);
  color: var(--ed-ink);
}

/* ---------- 侧栏：暖米底，1px 极淡线分隔（非深色） ---------- */
.lb-admin-sider {
  position: sticky;
  top: 0;
  align-self: start;
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 18px 12px 12px;
  border-right: 1px solid var(--ed-rule-soft);
  background: var(--ed-paper);
  overflow-y: auto;
}

.lb-admin-brand {
  padding: 0 6px 20px;
}

.lb-brand-link {
  display: flex;
  align-items: center;
  gap: 11px;
  text-decoration: none;
}

/* 产品 logo：圆角方块 + 主题色渐变 + 白字（对齐 demo .logo） */
.lb-brand-mark {
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-family: var(--ed-font-display);
  font-style: italic;
  font-size: 20px;
  font-weight: 500;
  line-height: 1;
  color: #fff;
  background: linear-gradient(140deg, var(--ed-accent), color-mix(in srgb, var(--ed-accent) 55%, #f0a03c));
  box-shadow: 0 10px 20px -12px var(--ed-accent);
}

.lb-brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  min-width: 0;
}

.lb-brand-text strong {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--ed-ink);
}

.lb-brand-text em {
  font-style: normal;
  font-size: 12px;
  color: var(--ed-ink-soft);
}

/* ---------- 导航 ---------- */
.lb-admin-nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.lb-nav-group {
  display: flex;
  flex-direction: column;
}

.lb-nav-group-label {
  margin: 0 0 6px;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--ed-ink-faint);
}

.lb-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--app-nav-row);
  padding: 0 10px;
  margin: 2px 0;
  border-radius: var(--ed-radius-sm);
  text-decoration: none;
  color: var(--ed-ink-soft);
  font-size: 14px;
  transition: background 160ms ease, color 160ms ease, box-shadow 160ms ease;
}

.lb-nav-item:hover {
  background: color-mix(in srgb, var(--ed-accent) 7%, var(--ed-card));
  color: var(--ed-ink);
}

.lb-nav-icon {
  color: inherit;
  flex: none;
}

.lb-nav-text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 当前项：圆角 pill + 淡主题色 tint + 柔和阴影，无左色条 */
.lb-nav-item.is-active {
  background: var(--ed-accent-soft);
  color: var(--ed-accent-ink);
  font-weight: 600;
  box-shadow: 0 6px 16px -12px color-mix(in srgb, var(--ed-accent) 60%, transparent);
}

.lb-nav-item.is-active .lb-nav-icon {
  color: var(--ed-accent-ink);
}

/* ---------- 侧栏底部 ---------- */
.lb-admin-foot {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ed-rule-soft);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lb-foot-tools {
  display: flex;
  align-items: center;
  padding: 0 4px;
}

.lb-user-wrap {
  width: 100%;
}

.lb-user-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--ed-radius-sm);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition: background 160ms ease;
}

.lb-user-row:hover {
  background: color-mix(in srgb, var(--ed-accent) 7%, var(--ed-card));
}

.lb-user-avatar {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  overflow: hidden;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(140deg, var(--ed-accent), color-mix(in srgb, var(--ed-accent) 55%, #f0a03c));
}

.lb-user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.lb-user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;
}

.lb-user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ed-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-user-role {
  font-size: 11px;
  color: var(--ed-ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 画布：暖米底，统一留白 ---------- */
.lb-admin-content {
  min-width: 0;
  padding: clamp(20px, 2.6vw, 36px);
  background: var(--ed-paper);
}

/* 内容不足一屏时也让画布铺满视口，避免 html/body 冷灰底从底部露出（白边） */
.lb-admin-content {
  min-height: 100vh;
}

/* ---------- 手机：侧栏折叠为抽屉式 ---------- */
@media (max-width: 860px) {
  .lb-admin-layout {
    grid-template-columns: 1fr;
  }

  .lb-admin-sider {
    position: fixed;
    left: 0;
    top: 0;
    z-index: 40;
    width: 240px;
    transform: translateX(-100%);
    transition: transform 200ms ease;
    box-shadow: 0 12px 40px rgba(0, 0, 0, 0.16);
  }

  .lb-admin-content {
    padding: 16px;
  }
}
</style>

<!-- ============================================================
     非 scoped：admin 内容页统一设计层（task-19 亮卡片语言）。
     全部前缀 .lb-admin-layout .lb-admin-content，仅作用于后台内容，
     绝不泄漏到门户。Element Plus 表格/卡片/按钮在此统一为
     「暖白圆角卡 + 柔和阴影 + 主题色点缀」的明快观感。
     ============================================================ -->
<style>
/* 页根：DM Sans + 文本颜色（各内容页最外层自动继承） */
.lb-admin-layout .lb-admin-content {
  font-family: var(--ed-font-text);
  color: var(--ed-ink);
  font-size: var(--ed-step-body);
  line-height: var(--ed-leading-body);
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
}

/* 页面标题行（各页 .lb-page-header） */
.lb-admin-layout .lb-admin-content .lb-page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--app-gap);
  margin-bottom: clamp(18px, 2vw, 26px);
  flex-wrap: wrap;
}

.lb-admin-layout .lb-admin-content .lb-page-header > div:first-child {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

.lb-admin-layout .lb-admin-content .lb-page-header h2 {
  margin: 0;
  font-family: var(--ed-font-text);
  font-size: clamp(1.5rem, 1.2rem + 1vw, 1.875rem);
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--ed-ink);
}

.lb-admin-layout .lb-admin-content .lb-page-header .lb-muted,
.lb-admin-layout .lb-admin-content .lb-page-header .lb-page-sub {
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

/* 卡片：白面 + 22px 圆角 + 柔和阴影（亮卡片语言，允许静态阴影） */
.lb-admin-layout .lb-admin-content .el-card {
  border: 1px solid color-mix(in srgb, var(--ed-ink) 5%, transparent);
  border-radius: var(--ed-radius-lg);
  box-shadow: var(--ed-shadow-sm);
  background: var(--ed-card);
  overflow: hidden;
}

.lb-admin-layout .lb-admin-content .el-card + .el-card {
  margin-top: var(--app-gap);
}

.lb-admin-layout .lb-admin-content .el-card__body {
  padding: clamp(18px, 2vw, 24px);
}

.lb-admin-layout .lb-admin-content .el-card__header {
  padding: 16px clamp(18px, 2vw, 24px);
  border-bottom: 1px solid var(--ed-rule-soft);
  font-family: var(--ed-font-text);
  font-size: var(--ed-step-small);
  font-weight: 600;
  color: var(--ed-ink);
  background: transparent;
}

/* 工具栏 */
.lb-admin-layout .lb-admin-content .lb-manage-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

/* 表格：无斑马、无表头条带、12px 灰体 sentence-case 表头、细微 hover */
.lb-admin-layout .lb-admin-content .el-table {
  --el-table-border-color: var(--ed-rule-soft);
  --el-table-header-bg-color: transparent;
  --el-table-row-hover-bg-color: color-mix(in srgb, var(--ed-accent) 5%, var(--ed-card));
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  font-size: var(--ed-step-small);
  color: var(--ed-ink);
  background: transparent;
}

.lb-admin-layout .lb-admin-content .el-table th.el-table__cell {
  background: transparent;
  color: var(--ed-ink-soft);
  font-size: 12px;
  font-weight: 600;
  text-transform: none;
  letter-spacing: 0.01em;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-admin-layout .lb-admin-content .el-table td.el-table__cell {
  border-bottom: 1px solid var(--ed-rule-soft);
  padding: 12px 0;
}

/* 数字列：右对齐 + 等宽数字（表头同步右对齐） */
.lb-admin-layout .lb-admin-content .el-table .lb-col-num .cell,
.lb-admin-layout .lb-admin-content .el-table th.lb-col-num .cell {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.lb-admin-layout .lb-admin-content .el-table a {
  color: var(--ed-accent-ink);
  font-weight: 600;
  text-decoration: none;
}

.lb-admin-layout .lb-admin-content .el-table a:hover {
  text-decoration: underline;
}

.lb-admin-layout .lb-admin-content .el-table__empty-text {
  color: var(--ed-ink-soft);
  font-size: var(--ed-step-small);
  padding: 28px 0;
}

/* 状态 tag → pale tint pill */
.lb-admin-layout .lb-admin-content .el-tag {
  border-radius: var(--ed-radius-pill);
  font-family: var(--ed-font-text);
  font-weight: 600;
  border: none;
  padding: 0 10px;
}

/* 分页右对齐 */
.lb-admin-layout .lb-admin-content .lb-manage-pagination,
.lb-admin-layout .lb-admin-content .lb-log-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}

/* 提示条间距 */
.lb-admin-layout .lb-admin-content .lb-load-alert,
.lb-admin-layout .lb-admin-content .lb-page-header + .el-alert {
  margin-bottom: 16px;
}

.lb-admin-layout .lb-admin-content .el-alert {
  border-radius: var(--ed-radius-md);
}

/* Element 主按钮 → 主题色（每屏一个实心主按钮）·全圆 pill */
.lb-admin-layout .lb-admin-content .el-button--primary {
  --el-button-bg-color: var(--ed-accent);
  --el-button-border-color: var(--ed-accent);
  --el-button-hover-bg-color: color-mix(in srgb, var(--ed-accent) 88%, #000);
  --el-button-hover-border-color: color-mix(in srgb, var(--ed-accent) 88%, #000);
  --el-button-active-bg-color: color-mix(in srgb, var(--ed-accent) 82%, #000);
  --el-button-text-color: #fff;
  border-radius: var(--ed-radius-pill);
  font-weight: 600;
  box-shadow: 0 12px 24px -14px var(--ed-accent);
}

/* 文字型主按钮（link / text）不能继承实心按钮的白字，否则在白卡片上彻底看不见 */
.lb-admin-layout .lb-admin-content .el-button--primary.is-link,
.lb-admin-layout .lb-admin-content .el-button--primary.is-text {
  --el-button-bg-color: transparent;
  --el-button-border-color: transparent;
  --el-button-hover-bg-color: transparent;
  --el-button-hover-border-color: transparent;
  --el-button-active-bg-color: transparent;
  --el-button-text-color: var(--ed-accent);
  color: var(--ed-accent);
  background: transparent;
  box-shadow: none;
  font-weight: 500;
}

.lb-admin-layout .lb-admin-content .el-button--primary.is-link:hover,
.lb-admin-layout .lb-admin-content .el-button--primary.is-text:hover {
  color: var(--ed-accent-ink);
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* 默认按钮 → 圆角 pill、淡描边 */
.lb-admin-layout .lb-admin-content .el-button {
  border-radius: var(--ed-radius-pill);
  font-family: var(--ed-font-text);
  font-weight: 500;
}

.lb-admin-layout .lb-admin-content .el-button--default {
  border-color: var(--ed-rule-soft);
  background: var(--ed-card);
  color: var(--ed-ink);
}

.lb-admin-layout .lb-admin-content .el-button--default:hover {
  border-color: var(--ed-accent);
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

/* 输入/选择：圆角 + 柔和描边，聚焦用主题色 */
.lb-admin-layout .lb-admin-content .el-input__wrapper,
.lb-admin-layout .lb-admin-content .el-textarea__inner,
.lb-admin-layout .lb-admin-content .el-select__wrapper {
  border-radius: var(--ed-radius-sm);
  box-shadow: 0 0 0 1px var(--ed-rule-soft) inset;
  background: var(--ed-card);
}

.lb-admin-layout .lb-admin-content .el-input__wrapper.is-focus,
.lb-admin-layout .lb-admin-content .el-select__wrapper.is-focused {
  box-shadow: 0 0 0 1px var(--ed-accent) inset;
}

/* 表格容器内工具栏与首行之间的呼吸 */
.lb-admin-layout .lb-admin-content .el-card .lb-manage-filters + .el-table {
  margin-top: 2px;
}

/* 分页控件 pill 化 */
.lb-admin-layout .lb-admin-content .el-pagination.is-background .el-pager li,
.lb-admin-layout .lb-admin-content .el-pagination.is-background .btn-prev,
.lb-admin-layout .lb-admin-content .el-pagination.is-background .btn-next {
  border-radius: var(--ed-radius-sm);
}
</style>
