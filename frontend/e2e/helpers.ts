import { expect, type Page, type APIRequestContext } from '@playwright/test'

/**
 * E2E 共享工具。
 *
 * 约定：所有用例在 `E2E_BASE_URL` 未设时 skip（由各 spec 顶部 test.skip 控制），
 * 因此这里的 helper 只在完整栈（CI compose 或本地起好前后端）下被调用。
 *
 * 认证走真实 API：POST /api/v1/auth/login 会 Set-Cookie（linlog_at / linlog_rt），
 * Playwright 的 request context 与 browser context 共享 cookies（同一 storageState），
 * 故用 page.request 登录后，后续 page.goto 即带 Cookie。
 */

export const ADMIN_ACCOUNT = process.env.E2E_ADMIN_ACCOUNT || 'admin@linlog.com'
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'LinBlog@2026'
// 首改后的密码（admin 若 mustChangePassword=1 会被强制改到它）
export const ADMIN_PASSWORD_AFTER = process.env.E2E_ADMIN_PASSWORD_AFTER || '{{SECRET_zk3pq8dm}}'

/** API 登录（写 Cookie 到当前 context）。返回是否成功。 */
export async function loginViaApi(
  page: Page,
  account: string,
  password: string,
): Promise<boolean> {
  const resp = await page.request.post('/api/v1/auth/login', {
    data: { account, password },
  })
  return resp.ok()
}

/**
 * 确保种子超管可用：登录 → 若被强制首改则完成改密 → (再登录一次)。
 * 幂等：若超管已改过密码，用 AFTER 密码登录成功。返回 page 已带登录态。
 */
export async function ensureAdminReady(page: Page): Promise<void> {
  // 先试首改后密码；失败再试初始密码（首次运行）
  let ok = await loginViaApi(page, ADMIN_ACCOUNT, ADMIN_PASSWORD_AFTER)
  if (!ok) {
    ok = await loginViaApi(page, ADMIN_ACCOUNT, ADMIN_PASSWORD)
    expect(ok, '种子超管登录失败（初始密码与首改后密码都不对）').toBeTruthy()
    const me = await page.request.get('/api/v1/auth/me')
    const body = await me.json()
    if (body?.data?.user?.mustChangePassword === 1) {
      // 强制首改：走真实改密端点
      const change = await page.request.post('/api/v1/auth/password/change', {
        data: { oldPassword: ADMIN_PASSWORD, newPassword: ADMIN_PASSWORD_AFTER },
      })
      expect(change.ok(), '超管首改密失败').toBeTruthy()
      // 改密后旧令牌失效，重新登录
      const relogin = await loginViaApi(page, ADMIN_ACCOUNT, ADMIN_PASSWORD_AFTER)
      expect(relogin, '首改密后重新登录失败').toBeTruthy()
    }
  }
}

/** 唯一用户名（注册类用例避免 40900 重名） */
export function uniqueName(prefix = 'e2e'): string {
  return `${prefix}_${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`.slice(0, 20)
}

/** 通过 API 注册一个普通用户（viewer） */
export async function registerUser(
  request: APIRequestContext,
  username: string,
  email: string,
  password: string,
): Promise<boolean> {
  const resp = await request.post('/api/v1/auth/register', {
    data: { username, email, password },
  })
  return resp.ok()
}

/** 通过 API 直接登录并返回 request context 携带的 cookie（供双 context 场景） */
export async function apiLoginRaw(
  request: APIRequestContext,
  account: string,
  password: string,
): Promise<boolean> {
  const resp = await request.post('/api/v1/auth/login', { data: { account, password } })
  return resp.ok()
}
