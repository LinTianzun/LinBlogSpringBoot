import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, get, post, request, setAuthErrorHandler } from '@/api/request'

/**
 * request 封装（§4.1/§7.2）：壳解包、XSRF 头、40001 fieldErrors、429 Retry-After、
 * 40101 单飞刷新 + 并发重放、40102 全局处理器、刷新网络失败不清会话。
 */

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function shell(code: number, message = '', data?: unknown) {
  return { code, message, data, requestId: 'req-test' }
}

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  setAuthErrorHandler(null)
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  vi.restoreAllMocks()
})

describe('壳解包与基础行为', () => {
  it('code=0 解包 data，GET 不带 XSRF 头，credentials include', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(shell(0, 'ok', { hello: 'world' })))

    const data = await get<{ hello: string }>('/demo', { a: 1, skip: null, drop: '' })

    expect(data).toEqual({ hello: 'world' })
    const call = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(call[0]).toBe('/api/v1/demo?a=1')
    expect(call[1].credentials).toBe('include')
    expect((call[1].headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined()
  })

  it('业务错误码抛 ApiError（携带 code/message/requestId），与 HTTP 状态无关', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(shell(40300, '没有权限'), { status: 403 }))

    const error = await request('/demo').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(40300)
    expect((error as ApiError).message).toBe('没有权限')
    expect((error as ApiError).requestId).toBe('req-test')
  })

  it('网络层失败抛 code=-1 的 ApiError', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('network down'))
    const error = await request('/demo').catch((e: unknown) => e)
    expect((error as ApiError).code).toBe(-1)
  })

  it('非 JSON 响应（壳格式异常）抛 50000/HTTP 状态码', async () => {
    fetchMock.mockResolvedValueOnce(new Response('<html>', { status: 502 }))
    const error = await request('/demo').catch((e: unknown) => e)
    expect((error as ApiError).code).toBe(502)
  })
})

describe('XSRF 双提交', () => {
  it('非 GET 请求自动附 X-XSRF-TOKEN（读 cookie）', async () => {
    document.cookie = 'XSRF-TOKEN=tok-123; path=/'
    fetchMock.mockResolvedValueOnce(jsonResponse(shell(0, 'ok', null)))

    await post('/demo', { x: 1 })

    const call = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((call[1].headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('tok-123')
    expect((call[1].headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })
})

describe('40001 / 42900', () => {
  it('40001 抛出含 fieldErrors 的 ApiError', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(shell(40001, '字段校验失败', { fieldErrors: [{ field: 'account', message: '账号或密码错误' }] })),
    )

    const error = await request('/auth/login').catch((e: unknown) => e)
    expect((error as ApiError).code).toBe(40001)
    expect((error as ApiError).fieldErrors).toEqual([{ field: 'account', message: '账号或密码错误' }])
  })

  it('42900 解析 Retry-After 响应头', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(shell(42900, '请求过于频繁'), { status: 429, headers: { 'Retry-After': '12' } }))

    const error = await request('/auth/login').catch((e: unknown) => e)
    expect((error as ApiError).code).toBe(42900)
    expect((error as ApiError).retryAfter).toBe(12)
  })
})

describe('40101 单飞刷新与重放（§7.2）', () => {
  it('并发请求共享同一次 POST /auth/refresh，随后各自重放一次', async () => {
    fetchMock.mockImplementation(async () => {
      if (fetchMock.mock.calls.length <= 2) return jsonResponse(shell(40101, '令牌过期'))
      if (fetchMock.mock.calls.length === 3) return jsonResponse(shell(0, 'ok', null)) // refresh
      return jsonResponse(shell(0, 'ok', 'replayed'))
    })

    const [a, b] = await Promise.all([get<string>('/demo'), get<string>('/demo')])

    expect(a).toBe('replayed')
    expect(b).toBe('replayed')
    expect(fetchMock).toHaveBeenCalledTimes(5)
    const refreshCall = fetchMock.mock.calls[2] as [string, RequestInit]
    expect(refreshCall[0]).toBe('/api/v1/auth/refresh')
    expect(refreshCall[1].method).toBe('POST')
  })

  it('重放后仍 40101 → 走认证失败处理器（不无限循环）', async () => {
    const handler = vi.fn()
    setAuthErrorHandler(handler)
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/auth/refresh')) return jsonResponse(shell(0, 'ok', null))
      return jsonResponse(shell(40101, '令牌过期'))
    })

    const error = await request('/demo').catch((e: unknown) => e)

    expect((error as ApiError).code).toBe(40101)
    expect(handler).toHaveBeenCalledWith(40101)
  })

  it('刷新请求网络失败：抛网络错误但不清会话（保留旧快照语义）', async () => {
    const handler = vi.fn()
    setAuthErrorHandler(handler)
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/auth/refresh')) throw new TypeError('refresh network down')
      return jsonResponse(shell(40101, '令牌过期'))
    })

    const error = await request('/demo').catch((e: unknown) => e)

    expect((error as ApiError).code).toBe(-1)
    expect(handler).not.toHaveBeenCalled()
  })
})

describe('40100 / 40102', () => {
  it('40102 通知全局处理器并抛 ApiError', async () => {
    const handler = vi.fn()
    setAuthErrorHandler(handler)
    fetchMock.mockResolvedValueOnce(jsonResponse(shell(40102, '会话已失效')))

    const error = await request('/admin/secret').catch((e: unknown) => e)

    expect((error as ApiError).code).toBe(40102)
    expect(handler).toHaveBeenCalledWith(40102)
  })

  it('authError=silent 不触发全局处理器（/auth/me 供游客访问门户）', async () => {
    const handler = vi.fn()
    setAuthErrorHandler(handler)
    fetchMock.mockResolvedValueOnce(jsonResponse(shell(40100, '未登录')))

    const error = await request('/auth/me', { authError: 'silent' }).catch((e: unknown) => e)

    expect((error as ApiError).code).toBe(40100)
    expect(handler).not.toHaveBeenCalled()
  })
})
