import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { NDialogProvider, NMessageProvider } from 'naive-ui'
import { ApiError } from '@/api/request'
import LoginRegister from '@/views/portal/LoginRegister.vue'

/**
 * LoginRegister（§8.3）：登录/注册 Tab、密码强度、40001 fieldErrors 逐字段标红、
 * 确认密码一致性、成功后 session.reload + redirect 回跳。
 */

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  me: vi.fn(),
  logout: vi.fn(async () => undefined),
  requestPasswordReset: vi.fn(async () => undefined),
  resetPassword: vi.fn(async () => undefined),
  changePassword: vi.fn(async () => undefined),
}))

import { login as apiLogin, register as apiRegister } from '@/api/auth'

const mockedLogin = vi.mocked(apiLogin)
const mockedRegister = vi.mocked(apiRegister)

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.clearAllMocks()
  mockedLogin.mockResolvedValue(undefined)
  mockedRegister.mockResolvedValue(undefined)
})

const ROUTES = [
  { path: '/', name: 'home', component: { template: '<div>home</div>' } },
  { path: '/login-register', name: 'login-register', component: LoginRegister },
  { path: '/admin', name: 'admin', component: { template: '<div>admin</div>' } },
]

function inputOf(wrapper: ReturnType<typeof mount>, testId: string) {
  const host = wrapper.find(`[data-testid="${testId}"]`)
  return host.element.tagName === 'INPUT' ? host : host.find('input')
}

async function mountAtLogin(redirect?: string): Promise<{ wrapper: ReturnType<typeof mount>; router: ReturnType<typeof createRouter> }> {
  const router = createRouter({ history: createMemoryHistory(), routes: ROUTES })
  await router.push(redirect ? `/login-register?redirect=${redirect}` : '/login-register')
  await router.isReady()

  const wrapper = mount({
    components: { LoginRegister, NMessageProvider, NDialogProvider },
    template: '<n-message-provider><n-dialog-provider><login-register /></n-dialog-provider></n-message-provider>',
  }, { global: { plugins: [router] } })
  return { wrapper, router }
}

describe('登录', () => {
  it('成功登录：调 login、session 重拉、按 redirect 回跳', async () => {
    const { wrapper, router } = await mountAtLogin('/admin')

    await inputOf(wrapper, 'login-account').setValue('lin')
    await inputOf(wrapper, 'login-password').setValue('secret123')
    await wrapper.find('[data-testid="login-submit"]').trigger('click')
    await flushPromises()

    expect(mockedLogin).toHaveBeenCalledWith({ account: 'lin', password: 'secret123' })
    expect(router.currentRoute.value.path).toBe('/admin')
  })

  it('40001 fieldErrors 逐字段展示', async () => {
    mockedLogin.mockRejectedValueOnce(
      new ApiError(40001, '字段校验失败', { fieldErrors: [{ field: 'account', message: '账号或密码错误' }] }),
    )
    const { wrapper, router } = await mountAtLogin()

    await inputOf(wrapper, 'login-account').setValue('lin')
    await inputOf(wrapper, 'login-password').setValue('wrong-pass')
    await wrapper.find('[data-testid="login-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('账号或密码错误')
    expect(router.currentRoute.value.path).toBe('/login-register')
  })
})

describe('注册', () => {
  it('两次密码不一致：本地拦截，不调接口', async () => {
    const { wrapper } = await mountAtLogin()

    // 切到注册 Tab
    const tabs = wrapper.findAll('.n-tabs-tab')
    await tabs[1]?.trigger('click')

    await inputOf(wrapper, 'register-username').setValue('newbie')
    await inputOf(wrapper, 'register-email').setValue('n@example.com')
    await inputOf(wrapper, 'register-password').setValue('Abcdef12')
    await inputOf(wrapper, 'register-confirm').setValue('Abcdef13')
    await wrapper.find('[data-testid="register-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('两次输入的密码不一致')
    expect(mockedRegister).not.toHaveBeenCalled()
  })

  it('40001 字段级报错映射到对应输入', async () => {
    mockedRegister.mockRejectedValueOnce(
      new ApiError(40001, '字段校验失败', { fieldErrors: [{ field: 'username', message: '用户名已被占用' }] }),
    )
    const { wrapper } = await mountAtLogin()

    const tabs = wrapper.findAll('.n-tabs-tab')
    await tabs[1]?.trigger('click')

    await inputOf(wrapper, 'register-username').setValue('lin')
    await inputOf(wrapper, 'register-email').setValue('lin@example.com')
    await inputOf(wrapper, 'register-password').setValue('Abcdef12')
    await inputOf(wrapper, 'register-confirm').setValue('Abcdef12')
    await wrapper.find('[data-testid="register-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('用户名已被占用')
  })
})
