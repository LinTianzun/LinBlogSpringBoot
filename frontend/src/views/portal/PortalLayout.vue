<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'
import ThemeSwitcher from '@/components/ThemeSwitcher.vue'

/**
 * 门户骨架（editorial）：无边框导航条 —— 字标 + 少量纯文字链接 + 一个行动。
 * 首屏 / 内容 / 页脚共享同一左右等宽边距（--ed-margin）。页脚是设计出来的落款。
 * 4 套主题 + 暗色由 --main-color / html.dark 推导；导航不使用任何渐变/阴影/玻璃。
 */
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

function isActive(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path === to || route.path.startsWith(to + '/')
}
</script>

<template>
  <div class="lb-ed lb-portal-shell">
    <header class="lb-shell-bar">
      <div class="lb-ed-wrap lb-bar-inner">
        <RouterLink to="/" class="lb-bar-mark">LinBlog</RouterLink>

        <nav class="lb-bar-nav" aria-label="主导航">
          <RouterLink
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="lb-bar-link"
            :class="{ 'is-active': isActive(item.to) }"
          >
            {{ item.label }}
          </RouterLink>
        </nav>

        <div class="lb-bar-user">
          <ThemeSwitcher class="lb-shell-theme" />
          <template v-if="session.isLoggedIn">
            <el-dropdown v-if="session.user" trigger="click" @command="onCommand">
              <button type="button" class="lb-bar-account">
                {{ session.user.nickname || session.user.username }}
                <el-icon class="lb-bar-caret"><ArrowDown /></el-icon>
              </button>
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
          <RouterLink v-else to="/login-register" class="lb-ed-btn lb-bar-login">登录 / 注册</RouterLink>
        </div>
      </div>
    </header>

    <main class="lb-shell-main">
      <router-view />
    </main>

    <footer class="lb-shell-footer">
      <div class="lb-ed-wrap lb-footer-inner">
        <p class="lb-footer-mark">LinBlog</p>
        <p class="lb-ed-caption lb-footer-note">
          写作即产品 · 由 Vue3 + SpringBoot 驱动 · © {{ new Date().getFullYear() }}
        </p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.lb-portal-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* ---------- 导航条：无边框、无阴影、无玻璃 ---------- */
.lb-shell-bar {
  padding: 22px 0;
}

.lb-bar-inner {
  display: flex;
  align-items: center;
  gap: clamp(20px, 4vw, 56px);
}

.lb-bar-mark {
  font-family: var(--ed-font-display);
  font-size: 1.35rem;
  font-weight: 500;
  letter-spacing: -0.02em;
  color: var(--ed-ink);
  text-decoration: none;
  flex-shrink: 0;
}

.lb-bar-nav {
  display: flex;
  align-items: center;
  gap: clamp(14px, 2.4vw, 34px);
  flex: 1;
  flex-wrap: wrap;
}

.lb-bar-link {
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
  text-decoration: none;
  transition: color 160ms ease;
}

.lb-bar-link:hover {
  color: var(--ed-ink);
}

.lb-bar-link.is-active {
  color: var(--ed-ink);
  font-weight: 600;
}

.lb-bar-user {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}

.lb-bar-account {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  padding: 0;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  color: var(--ed-ink);
  cursor: pointer;
}

.lb-bar-caret {
  font-size: 12px;
  color: var(--ed-ink-soft);
}

.lb-bar-login {
  min-height: 38px;
  padding: 0 18px;
}

/* ---------- 主区 ---------- */
.lb-shell-main {
  flex: 1;
  padding-bottom: clamp(80px, 10vw, 160px);
}

/* ---------- 页脚：全幅落款 ---------- */
.lb-shell-footer {
  padding: clamp(56px, 8vw, 110px) 0 clamp(28px, 4vw, 48px);
}

.lb-footer-inner {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.lb-footer-mark {
  margin: 0;
  font-family: var(--ed-font-display);
  font-size: clamp(2.4rem, 6vw, 4.5rem);
  font-weight: 300;
  letter-spacing: -0.04em;
  line-height: 0.9;
  color: var(--ed-ink);
}

.lb-footer-note {
  margin: 0;
  padding-bottom: 0.4em;
}

@media (max-width: 720px) {
  .lb-bar-inner { flex-wrap: wrap; row-gap: 14px; }
  .lb-bar-nav { order: 3; width: 100%; flex: 1 1 100%; }
}
</style>
