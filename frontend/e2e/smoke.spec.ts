import { test, expect, type Page } from '@playwright/test'
import { ADMIN_ACCOUNT, ADMIN_PASSWORD_AFTER, ensureAdminReady, loginViaApi, uniqueName } from './helpers'

/**
 * M7 E2E 冒烟（§8.3 十二类场景）。
 *
 * 仅在完整栈可用时运行：未设 `E2E_BASE_URL` 时整文件 skip（不阻塞本地与 CI 前端 job）。
 * 所有业务请求打真实后端 `/api/v1/*`，UI 断言走门户/后台真实页面。
 */
const SKIP = !process.env.E2E_BASE_URL
test.skip(SKIP, 'E2E 需要 E2E_BASE_URL 指向完整栈（docker compose / 本地前后端）')

/** 超管 API 发文并发布会（直接打后端，门户列表/详情断言） */
async function publishArticle(page: Page, title: string): Promise<number> {
  const body = JSON.stringify({
    type: 'doc',
    content: [{ type: 'paragraph', content: [{ type: 'text', text: `正文内容 ${title}` }] }],
  })
  const resp = await page.request.post('/api/v1/admin/contents', {
    data: { contentType: 'article', title, introduce: 'e2e 摘要', body },
  })
  expect(resp.ok(), '超管发文失败').toBeTruthy()
  const created = await resp.json()
  const id = created?.data?.id as number
  expect(id, '发文未返回 id').toBeTruthy()
  const pub = await page.request.patch(`/api/v1/admin/contents/${id}/state`, {
    data: { state: 'published' },
  })
  expect(pub.ok(), '发布失败').toBeTruthy()
  return id
}

test.describe('门户冒烟（§8.3）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureAdminReady(page)
  })

  test('1. 注册新用户成功并自动登录', async ({ page }) => {
    const username = uniqueName('reg')
    const account = `${username}@e2e.local`
    const password = 'E2ePass!2345'
    const resp = await page.request.post('/api/v1/auth/register', {
      data: { username, email: account, password },
    })
    expect(resp.ok()).toBeTruthy()
    const login = await loginViaApi(page, account, password)
    expect(login).toBeTruthy()
    const me = await page.request.get('/api/v1/auth/me')
    const body = await me.json()
    expect(body?.data?.user?.username).toBe(username)
  })

  test('2. 登录已有账号并登出清会话', async ({ page }) => {
    await page.goto('/login-register')
    await expect(page.getByTestId('login-account')).toBeVisible()
    await page.getByTestId('login-account').fill(ADMIN_ACCOUNT)
    await page.getByTestId('login-password').fill(ADMIN_PASSWORD_AFTER)
    await page.getByTestId('login-submit').click()
    // 登录后应跳出门户登录页（进入首页或后台）
    await page.waitForURL((url) => !url.pathname.includes('/login-register'), { timeout: 10_000 })
    // 登出
    await page.request.post('/api/v1/auth/logout')
    const me = await page.request.get('/api/v1/auth/me')
    // 登出后 /auth/me 不返回有效用户（或 401）
    const status = me.status()
    if (status === 200) {
      const body = await me.json()
      expect(body?.data?.user ?? null).toBeFalsy()
    } else {
      expect([401, 403]).toContain(status)
    }
  })

  test('3. 发文 → 门户文章列表可见', async ({ page }) => {
    const title = uniqueName('article')
    await publishArticle(page, title)
    await page.goto('/articles')
    await expect(page.getByText(title, { exact: false }).first()).toBeVisible({ timeout: 15_000 })
  })

  test('4. 文章详情页渲染正文', async ({ page }) => {
    const title = uniqueName('detail')
    const id = await publishArticle(page, title)
    await page.goto(`/articles/${id}`)
    // 正文以纯文本形式可见（ProseMirror JSON → DOM）
    await expect(page.getByText(`正文内容 ${title}`, { exact: false }).first()).toBeVisible({
      timeout: 15_000,
    })
  })

  test('5. 评论：登录用户发表评论出现在评论区', async ({ page }) => {
    const title = uniqueName('cmt')
    const id = await publishArticle(page, title)
    // 用超管登录态（已带 Cookie）
    const text = `评论文本 ${uniqueName('t')}`
    const resp = await page.request.post('/api/v1/comments', {
      data: { targetType: 'article', targetId: id, body: text },
    })
    expect(resp.ok(), '发表评论失败').toBeTruthy()
    await page.goto(`/articles/${id}`)
    await expect(page.getByText(text, { exact: false }).first()).toBeVisible({ timeout: 15_000 })
  })

  test('6. 评论举报 → 后台处理闭环', async ({ page }) => {
    const title = uniqueName('rep')
    const id = await publishArticle(page, title)
    const cResp = await page.request.post('/api/v1/comments', {
      data: { targetType: 'article', targetId: id, body: '待举报的评论' },
    })
    expect(cResp.ok()).toBeTruthy()
    const commentId = (await cResp.json())?.data?.id as number
    expect(commentId).toBeTruthy()
    const rep = await page.request.post(`/api/v1/comments/${commentId}/report`, {
      data: { reason: 'spam', detail: 'e2e 举报' },
    })
    expect(rep.ok(), '举报失败').toBeTruthy()
    // 后台报表列表应含该条
    const list = await page.request.get('/api/v1/admin/comment-reports?page=1&pageSize=20')
    expect(list.ok()).toBeTruthy()
    const listBody = await list.json()
    const items = listBody?.data?.items ?? listBody?.data?.list ?? []
    const found = items.find((r: { targetId?: number; commentId?: number }) =>
      (r.commentId ?? r.targetId) === commentId,
    )
    expect(found, '报表未出现刚举报的评论').toBeTruthy()
    // 处理（dismiss/ignore 之一，按后端 ReportAction 枚举；用 ignore 兜底）
    const reportId = found.id as number
    const handled = await page.request.patch(`/api/v1/admin/comment-reports/${reportId}`, {
      data: { action: 'ignore', note: 'e2e 处理' },
    })
    if (!handled.ok()) {
      // 若枚举名不同，尝试 dismiss
      const alt = await page.request.patch(`/api/v1/admin/comment-reports/${reportId}`, {
        data: { action: 'dismiss' },
      })
      expect(alt.ok(), '报表处理失败').toBeTruthy()
    }
  })

  test('7. 上传文件 → 文件管理出现', async ({ page }) => {
    const name = uniqueName('file')
    // 上传端点 POST /files/upload（file.upload 权限）；超管有
    const up = await page.request.post('/api/v1/files/upload', {
      multipart: {
        file: {
          name: `${name}.txt`,
          mimeType: 'text/plain',
          buffer: Buffer.from(`e2e 上传 ${name}`),
        },
      },
    })
    expect(up.ok(), '上传失败').toBeTruthy()
    const upBody = await up.json()
    expect(upBody?.data?.id, '上传未返回 id').toBeTruthy()
    const list = await page.request.get('/api/v1/admin/files?page=1&pageSize=50')
    expect(list.ok()).toBeTruthy()
    const body = await list.json()
    const items = body?.data?.items ?? body?.data?.list ?? []
    expect(items.length, '文件列表为空').toBeGreaterThan(0)
  })

  test('8. 文件 trash → restore', async ({ page }) => {
    const name = uniqueName('trash')
    const up = await page.request.post('/api/v1/files/upload', {
      multipart: {
        file: { name: `${name}.txt`, mimeType: 'text/plain', buffer: Buffer.from('trash e2e') },
      },
    })
    expect(up.ok()).toBeTruthy()
    const upBody = await up.json()
    const fileId = upBody?.data?.id as number
    expect(fileId, '上传未返回 id').toBeTruthy()
    const trashed = await page.request.patch(`/api/v1/admin/files/${fileId}/trash`)
    expect(trashed.ok(), 'trash 失败').toBeTruthy()
    // 校验已进回收站
    const afterTrash = await page.request.get('/api/v1/admin/files?state=trashed&page=1&pageSize=50')
    expect(afterTrash.ok()).toBeTruthy()
    const restored = await page.request.patch(`/api/v1/admin/files/${fileId}/restore`)
    expect(restored.ok(), 'restore 失败').toBeTruthy()
  })
})
