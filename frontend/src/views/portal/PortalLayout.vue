<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
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

function onCommand(command: string): void {
  if (command === 'admin') void router.push('/admin')
  else if (command === 'logout') void handleLogout()
}
</script>

<template>
  <div class="lb-portal-layout">
    <header class="lb-portal-header">
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
            <el-dropdown v-if="session.user" @command="onCommand">
              <el-button link>
                {{ session.user.nickname || session.user.username }}
                <el-icon class="lb-user-caret"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item disabled>
                    {{ session.user.nickname || session.user.username }}
                  </el-dropdown-item>
                  <el-dropdown-item v-if="adminGate" command="admin">进入后台</el-dropdown-item>
                  <el-dropdown-item command="logout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <RouterLink v-else to="/login-register">
            <el-button size="small" type="primary" plain>登录 / 注册</el-button>
          </RouterLink>
        </div>
      </div>
    </header>

    <main class="lb-portal-content">
      <div class="lb-container">
        <router-view />
      </div>
    </main>

    <footer class="lb-portal-footer">
      <div class="lb-container lb-muted">
        © {{ new Date().getFullYear() }} LinBlog · Powered by Vue3 + SpringBoot
      </div>
    </footer>
  </div>
</template>

<style scoped>
.lb-portal-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--lb-bg);
}

.lb-portal-header {
  background: var(--lb-card-bg);
  border-bottom: 1px solid var(--lb-border);
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
  background: color-mix(in srgb, var(--lb-primary) 10%, transparent);
  font-weight: 600;
}

.lb-user-caret {
  margin-left: 4px;
}

.lb-portal-content {
  flex: 1;
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
