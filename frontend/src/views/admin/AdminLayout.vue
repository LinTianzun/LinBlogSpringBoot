<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  Document,
  Expand,
  Files,
  Fold,
  FolderOpened,
  Key,
  Message,
  Notebook,
  Odometer,
  Picture,
  Setting,
  Sunny,
  Moon,
  SwitchButton,
  Tickets,
  User,
  Warning,
  EditPen,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useThemeStore } from '@/stores/theme'
import type { PermissionKey } from '@/types/api'

/**
 * 后台壳（§6.1）：侧栏 运营/内容/系统 分组，菜单项按 session.canAny() 过滤
 * （空组剔除），顶栏 折叠/主题切换/用户菜单（退出）。路由守卫保证无权限不可达，
 * 这里只管可见性。
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
const theme = useThemeStore()
const route = useRoute()
const router = useRouter()
const collapsed = ref(false)

const groups = computed(() =>
  GROUP_DEFS.map((group) => ({
    label: group.label,
    items: group.items.filter((item) => session.canAny(item.permission)),
  })).filter((group) => group.items.length > 0),
)

const pageTitle = computed(() => route.meta.title ?? '')

async function handleLogout(): Promise<void> {
  await session.logout()
  void router.push('/')
}
</script>

<template>
  <el-container class="lb-admin-layout">
    <el-aside :width="collapsed ? '64px' : '220px'" class="lb-admin-sider">
      <div class="lb-admin-brand">
        <RouterLink to="/">{{ collapsed ? 'LB' : 'LinBlog 后台' }}</RouterLink>
      </div>
      <el-menu
        :default-active="route.path"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="lb-admin-menu"
      >
        <template v-for="group in groups" :key="group.label">
          <div class="lb-menu-group-label">{{ collapsed ? '' : group.label }}</div>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.label }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container class="lb-admin-body">
      <el-header class="lb-admin-header" height="56px">
        <el-button
          link
          class="lb-admin-collapse"
          :aria-label="collapsed ? '展开侧栏' : '折叠侧栏'"
          @click="collapsed = !collapsed"
        >
          <el-icon :size="18"><component :is="collapsed ? Expand : Fold" /></el-icon>
        </el-button>
        <span class="lb-admin-title">{{ pageTitle }}</span>
        <div class="lb-admin-header-right">
          <el-button link :aria-label="theme.isDark ? '切换亮色' : '切换暗色'" @click="theme.toggle()">
            <el-icon :size="18"><component :is="theme.isDark ? Sunny : Moon" /></el-icon>
          </el-button>
          <el-dropdown v-if="session.user" trigger="click">
            <span class="lb-admin-user" data-testid="admin-user-menu">
              {{ session.user.nickname || session.user.username }} ▾
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleLogout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="lb-admin-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.lb-admin-layout {
  min-height: 100vh;
}

.lb-admin-sider {
  border-right: 1px solid var(--art-card-border, var(--lb-border));
  background: var(--default-box-color, var(--lb-card-bg));
  transition: width 0.2s;
  overflow-x: hidden;
}

.lb-admin-brand {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
}

.lb-admin-brand a {
  color: var(--main-color, var(--lb-primary));
  text-decoration: none;
}

.lb-admin-menu {
  border-right: none;
}

.lb-menu-group-label {
  padding: 14px 20px 6px;
  font-size: 12px;
  color: var(--lb-text-secondary, #909399);
  white-space: nowrap;
}

.lb-admin-body {
  display: flex;
  flex-direction: column;
}

.lb-admin-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 20px;
  border-bottom: 1px solid var(--art-card-border, var(--lb-border));
  background: var(--default-box-color, var(--lb-surface, #fff));
}

.lb-admin-title {
  font-size: 15px;
  font-weight: 600;
  flex: 1;
}

.lb-admin-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.lb-admin-user {
  cursor: pointer;
  font-size: 14px;
  user-select: none;
  outline: none;
}

.lb-admin-content {
  padding: 20px;
  flex: 1;
}
</style>
