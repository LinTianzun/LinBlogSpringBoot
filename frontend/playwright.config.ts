import { defineConfig, devices } from '@playwright/test'

/**
 * E2E（§8.3，12 条冒烟）。
 *
 * 运行方式：
 * - CI：e2e job 先 `docker compose up`（deploy/ci.env → .env，Caddy :80），
 *   设 `E2E_BASE_URL=http://localhost`，再 `pnpm exec playwright test`。
 * - 本地：默认 E2E_BASE_URL 未设 → 全部用例 skip（不需要完整栈，不阻塞本地开发/CI 前端 job）。
 *   若想本地跑，起 preview + 后端后设 E2E_BASE_URL=http://localhost:4173。
 *
 * 种子超管 admin@linlog.com 首次登录 mustChangePassword=1，会被强制跳到 /change-password；
 * helpers.ensureAdminReady() 会在 beforeAll 里完成首改，后续用例复用已改的密码。
 */
const baseURL = process.env.E2E_BASE_URL || 'http://localhost:4173'
const hasStack = !!process.env.E2E_BASE_URL

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  // 冒烟用例涉及共享后台状态（超管、权限矩阵），串行执行保证确定性
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] }, testIgnore: /mobile\.spec\.ts/ },
    {
      name: 'mobile',
      use: { ...devices['iPhone 13'], viewport: { width: 375, height: 667 } },
      testMatch: /mobile\.spec\.ts/,
    },
  ],
  // 本地无完整栈时（未设 E2E_BASE_URL）不启 webServer，用例自行 skip
  webServer: hasStack
    ? undefined
    : undefined,
})
