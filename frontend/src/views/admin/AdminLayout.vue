<script setup lang="ts">
import { computed, h, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import type { MenuOption } from 'naive-ui'
import { useSessionStore } from '@/stores/session'
import type { PermissionKey } from '@/types/api'

/**
 * 后台壳（§6.1）：侧栏 运营/内容/系统 分组，菜单项按 session.can()/canAny() 过滤，
 * 顶栏用户菜单（退出）。路由守卫保证无权限不可达，这里只管可见性。
 * 菜单图标暂用文本前缀，避免引入整包图标依赖（unplugin-icons 按需图标后续里程碑接）。
 */

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const collapsed = ref(false)

interface MenuDef {
  label: string
  path: string
  glyph: string
  permission: PermissionKey[]
}

const GROUP_DEFS: Array<{ label: string; items: MenuDef[] }> = [
  {
    label: '运营',
    items: [
      { label: '总览', path: '/admin', glyph: '📊', permission: ['overview.view'] },
      { label: '评论管理', path: '/admin/comments', glyph: '💬', permission: ['comment.viewAll'] },
      { label: '举报处理', path: '/admin/reports', glyph: '🛡️', permission: ['comment.moderate'] },
      { label: '私信', path: '/admin/messages', glyph: '✉️', permission: ['message.private'] },
      { label: '留言墙管理', path: '/admin/guestbook', glyph: '📝', permission: ['message.wallModerate'] },
    ],
  },
  {
    label: '内容',
    items: [
      { label: '文章', path: '/admin/articles', glyph: '📄', permission: ['article.viewAll', 'article.viewOwn'] },
      { label: '画廊', path: '/admin/gallery', glyph: '🖼️', permission: ['gallery.viewAll', 'gallery.viewOwn'] },
      { label: '日记', path: '/admin/diaries', glyph: '📖', permission: ['diary.viewAll', 'diary.manageOwn'] },
      { label: '文件', path: '/admin/files', glyph: '🗂️', permission: ['file.view'] },
    ],
  },
  {
    label: '系统',
    items: [
      { label: '用户', path: '/admin/users', glyph: '👥', permission: ['user.view'] },
      { label: '权限', path: '/admin/permissions', glyph: '🔑', permission: ['permission.view'] },
      { label: '日志', path: '/admin/logs', glyph: '🧾', permission: ['systemLog.view'] },
      { label: '设置', path: '/admin/settings', glyph: '⚙️', permission: ['system.setting'] },
    ],
  },
]

const menuOptions = computed<MenuOption[]>(() =>
  GROUP_DEFS.map((group) => {
    const children = group.items
      .filter((item) => session.canAny(item.permission))
      .map((item) => ({
        label: () => h(RouterLink, { to: item.path }, { default: () => `${item.glyph} ${item.label}` }),
        key: item.path,
      }))
    return { type: 'group' as const, label: group.label, key: group.label, children }
  }).filter((group) => (group.children ?? []).length > 0),
)

const activeKey = computed(() => route.path)

async function handleLogout(): Promise<void> {
  await session.logout()
  void router.push('/')
}
</script>

<template>
  <n-layout has-sider class="lb-admin-layout">
    <n-layout-sider
      bordered
      collapse-mode="width"
      :collapsed="collapsed"
      :collapsed-width="64"
      :width="220"
      show-trigger
      @collapse="collapsed = true"
      @expand="collapsed = false"
    >
      <div class="lb-admin-brand">
        <RouterLink to="/">{{ collapsed ? 'LB' : 'LinBlog 后台' }}</RouterLink>
      </div>
      <n-menu :collapsed="collapsed" :options="menuOptions" :value="activeKey" />
    </n-layout-sider>

    <n-layout>
      <n-layout-header bordered class="lb-admin-header">
        <n-space justify="end" align="center" size="medium">
          <n-dropdown
            v-if="session.user"
            :options="[
              { label: session.user.nickname || session.user.username, key: 'name', disabled: true },
              { label: '退出登录', key: 'logout' },
            ]"
            @select="(key: string) => (key === 'logout' ? handleLogout() : undefined)"
          >
            <n-button quaternary size="small">
              {{ session.user.nickname || session.user.username }}
            </n-button>
          </n-dropdown>
        </n-space>
      </n-layout-header>
      <n-layout-content class="lb-admin-content">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>

<style scoped>
.lb-admin-layout {
  min-height: 100vh;
}

.lb-admin-brand {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
  color: var(--lb-primary);
}

.lb-admin-header {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  background: var(--lb-card-bg);
}

.lb-admin-content {
  padding: 20px;
  background: var(--lb-bg);
}
</style>
