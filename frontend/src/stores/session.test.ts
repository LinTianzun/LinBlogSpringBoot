import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import type { MeDTO, PermissionKey, UserDTO } from '@/types/api'

/**
 * session store：init 成功/认证失败/网络失败语义、can() 超管短路、
 * init 单飞、reload 重拉、快照持久化（§4.3/§7.2，继承原项目「刷新失败保留旧快照」）。
 */

vi.mock('@/api/auth', () => ({
  me: vi.fn(),
  logout: vi.fn(async () => undefined),
  login: vi.fn(async () => undefined),
  register: vi.fn(async () => undefined),
  requestPasswordReset: vi.fn(async () => undefined),
  resetPassword: vi.fn(async () => undefined),
  changePassword: vi.fn(async () => undefined),
}))

import { logout as apiLogout, me as apiMe } from '@/api/auth'

const mockedMe = vi.mocked(apiMe)
const mockedLogout = vi.mocked(apiLogout)

const USER: UserDTO = {
  id: 1,
  username: 'lin',
  email: 'lin@example.com',
  nickname: '小林',
  avatarMediaId: null,
  roleCode: 'admin',
  isSuperAdmin: false,
  status: 1,
  createdAt: '2026-01-01T00:00:00',
}

function meResult(overrides: Partial<MeDTO> = {}): MeDTO {
  return {
    user: USER,
    permissions: ['article.create', 'article.manageOwn'] as PermissionKey[],
    isSuperAdmin: false,
    ...overrides,
  }
}

beforeEach(async () => {
  localStorage.clear()
  setActivePinia(createPinia())
  mockedMe.mockReset()
  mockedLogout.mockReset().mockResolvedValue(undefined)
  // session.ts 的 initPromise 是模块级单飞缓存，跨用例残留；用 logout 复位（logout 内部置 null）
  await useSessionStore().logout()
})

describe('init()', () => {
  it('成功：填充 user/permissions/isSuperAdmin 并写快照', async () => {
    mockedMe.mockResolvedValueOnce(meResult())
    const session = useSessionStore()

    await session.init()

    expect(session.user).toEqual(USER)
    expect(session.permissions).toContain('article.create')
    expect(session.isSuperAdmin).toBe(false)
    expect(session.loaded).toBe(true)
    const raw = localStorage.getItem('linblog.session')
    expect(raw).not.toBeNull()
    expect(JSON.parse(raw as string).user.username).toBe('lin')
  })

  it('40100/40102：清为访客并删除快照', async () => {
    localStorage.setItem('linblog.session', JSON.stringify({ user: USER, permissions: ['article.create'], isSuperAdmin: false }))
    mockedMe.mockRejectedValueOnce(new ApiError(40100, '未登录'))
    const session = useSessionStore()

    await session.init()

    expect(session.user).toBeNull()
    expect(session.permissions).toEqual([])
    expect(session.isLoggedIn).toBe(false)
    expect(localStorage.getItem('linblog.session')).toBeNull()
  })

  it('网络级瞬时失败：无快照 → 保持访客，loaded 置位', async () => {
    mockedMe.mockRejectedValueOnce(new ApiError(-1, '网络请求失败或服务不可用'))
    const session = useSessionStore()

    await session.init()

    expect(session.user).toBeNull()
    expect(session.loaded).toBe(true)
  })

  it('网络级瞬时失败：有旧快照 → 保留已登录外观（原项目语义）', async () => {
    localStorage.setItem('linblog.session', JSON.stringify({ user: USER, permissions: ['article.create'], isSuperAdmin: true }))
    // 新 pinia：store state 初始化时才会读快照
    setActivePinia(createPinia())
    mockedMe.mockRejectedValueOnce(new ApiError(-1, '网络请求失败或服务不可用'))
    const session = useSessionStore()

    await session.init()

    expect(session.user?.username).toBe('lin')
    expect(session.isSuperAdmin).toBe(true)
    expect(session.loaded).toBe(true)
  })

  it('并发调用共享同一次 /auth/me（单飞）', async () => {
    let resolveMe!: (value: MeDTO) => void
    mockedMe.mockReturnValueOnce(new Promise<MeDTO>((resolve) => (resolveMe = resolve)))
    const session = useSessionStore()

    const first = session.init()
    const second = session.init()
    resolveMe(meResult())
    await Promise.all([first, second])

    expect(mockedMe).toHaveBeenCalledTimes(1)
  })
})

describe('can()/canAny()', () => {
  it('超管短路：任意权限键均返回 true', async () => {
    mockedMe.mockResolvedValueOnce(meResult({ isSuperAdmin: true, permissions: [] }))
    const session = useSessionStore()
    await session.init()

    expect(session.can('system.setting')).toBe(true)
    expect(session.can('user.assignAdmin')).toBe(true)
    expect(session.canAny(['permission.edit', 'file.view'])).toBe(true)
  })

  it('普通用户：命中集合返回 true，未命中返回 false', async () => {
    mockedMe.mockResolvedValueOnce(meResult())
    const session = useSessionStore()
    await session.init()

    expect(session.can('article.create')).toBe(true)
    expect(session.can('user.view')).toBe(false)
    expect(session.canAny(['user.view', 'article.create'])).toBe(true)
    expect(session.canAny(['user.view', 'file.view'])).toBe(false)
  })
})

describe('reload()/logout()', () => {
  it('reload 绕过单飞缓存强制重拉', async () => {
    mockedMe.mockResolvedValueOnce(meResult())
    const session = useSessionStore()
    await session.init()

    const nextUser = { ...USER, id: 2, username: 'newbie' }
    mockedMe.mockResolvedValueOnce(meResult({ user: nextUser }))
    await session.reload()

    expect(mockedMe).toHaveBeenCalledTimes(2)
    expect(session.user?.username).toBe('newbie')
  })

  it('logout 调后端并清空本地会话（后端失败也清）', async () => {
    mockedMe.mockResolvedValueOnce(meResult())
    const session = useSessionStore()
    await session.init()
    mockedLogout.mockClear() // beforeEach 的复位 logout 不计入

    await session.logout()

    expect(mockedLogout).toHaveBeenCalledTimes(1)
    expect(session.user).toBeNull()
    expect(localStorage.getItem('linblog.session')).toBeNull()
  })

  it('logout 后端不可达：仍然清空', async () => {
    mockedMe.mockResolvedValueOnce(meResult())
    const session = useSessionStore()
    await session.init()
    mockedLogout.mockRejectedValueOnce(new ApiError(-1, '网络请求失败或服务不可用'))

    await session.logout()

    expect(session.user).toBeNull()
  })
})
