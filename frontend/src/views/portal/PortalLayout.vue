<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const navItems = [
  { label: '首页', to: '/' },
  { label: '文章', to: '/articles' },
  { label: '画廊', to: '/gallery' },
  { label: '日记', to: '/diary' },
  { label: '留言墙', to: '/guestbook' },
]

/** 顶栏「进入后台」仅对持有任一后台权限的用户展示（§6.1 不暴露入口） */
const adminGate = computed(() =>
  session.canAny([
    'overview.view',
    'article.viewAll',
    'article.viewOwn',
    'gallery.viewAll',
    'gallery.viewOwn',
    'diary.viewAll',
    'diary.manageOwn',
    'comment.viewAll',
    'comment.moderate',
    'message.private',
    'message.wallModerate',
    'file.view',
    'user.view',
    'permission.view',
    'systemLog.view',
    'system.setting',
  ]),
)

async function handleLogout(): Promise<void> {
  await session.logout()
  if (route.path !== '/') void router.push('/')
}
</script>

<template>
  <n-layout class="lb-portal-layout">
    <n-layout-header bordered class="lb-portal-header">
      <div class="lb-container lb-header-inner">
        <RouterLink to="/" class="lb-logo">LinBlog</RouterLink>
        <nav class="lb-nav">
          <RouterLink
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="lb-nav-link"
            :class="{ active: route.path === item.to || (item.to !== '/' && route.path.startsWith(item.to)) }"
          >
            {{ item.label }}
          </RouterLink>
        </nav>
        <div class="lb-user-entry">
          <template v-if="session.isLoggedIn">
            <n-dropdown
              v-if="session.user"
              :options="[
                { label: session.user.nickname || session.user.username, key: 'name', disabled: true },
                ...(adminGate ? [{ label: '进入后台', key: 'admin' }] : []),
                { label: '退出登录', key: 'logout' },
              ]"
              @select="(key: string) => (key === 'admin' ? router.push('/admin') : key === 'logout' ? handleLogout() : undefined)"
            >
              <n-button quaternary size="small">
                {{ session.user.nickname || session.user.username }}
              </n-button>
            </n-dropdown>
          </template>
          <RouterLink v-else to="/login-register">
            <n-button size="small" type="primary" ghost>登录 / 注册</n-button>
          </RouterLink>
        </div>
      </div>
    </n-layout-header>

    <n-layout-content class="lb-portal-content">
      <div class="lb-container">
        <router-view />
      </div>
    </n-layout-content>

    <footer class="lb-portal-footer">
      <div class="lb-container lb-muted">
        © {{ new Date().getFullYear() }} LinBlog · Powered by Vue3 + SpringBoot
      </div>
    </footer>
  </n-layout>
</template>

<style scoped>
.lb-portal-layout {
  min-height: 100vh;
  background: var(--lb-bg);
}

.lb-portal-header {
  background: var(--lb-card-bg);
}

.lb-header-inner {
  display: flex;
  align-items: center;
  gap: 24px;
  height: 56px;
}

.lb-logo {
  font-size: 20px;
  font-weight: 700;
  color: var(--lb-primary);
}

.lb-nav {
  display: flex;
  gap: 4px;
  flex: 1;
}

.lb-nav-link {
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 14px;
  color: var(--lb-text-secondary);
}

.lb-nav-link.active {
  color: var(--lb-primary);
  background: rgba(24, 160, 88, 0.1);
  font-weight: 600;
}

.lb-portal-content {
  padding: 24px 0 40px;
  background: transparent;
}

.lb-portal-footer {
  padding: 16px 0;
  text-align: center;
  background: var(--lb-card-bg);
  border-top: 1px solid var(--lb-border);
}
</style>
