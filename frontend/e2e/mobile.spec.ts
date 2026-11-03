import { test, expect } from '@playwright/test'

/**
 * M7 E2E 冒烟（§8.3，移动端视口门户冒烟）。
 * 由 playwright.config.ts 的 `mobile` project（iPhone 13 375×667）匹配运行。
 * 与其余 spec 同门禁：未设 E2E_BASE_URL 时跳过。
 */
const SKIP = !process.env.E2E_BASE_URL
test.skip(SKIP, 'E2E 需要 E2E_BASE_URL 指向完整栈（docker compose / 本地前后端）')

test.describe('移动端视口门户冒烟', () => {
  test('移动端首页渲染导航与品牌', async ({ page }) => {
    const resp = await page.goto('/')
    expect(resp?.status()).toBeLessThan(400)
    // 品牌标志可见
    await expect(page.getByText('LinBlog', { exact: false }).first()).toBeVisible()
    // 视口内宽度不溢出（无横向滚动条）
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow, '移动端出现横向溢出').toBeLessThanOrEqual(2)
  })

  test('移动端文章列表可达', async ({ page }) => {
    const resp = await page.goto('/articles')
    expect(resp?.status()).toBeLessThan(400)
    await expect(page.getByText('LinBlog', { exact: false }).first()).toBeVisible()
  })
})
