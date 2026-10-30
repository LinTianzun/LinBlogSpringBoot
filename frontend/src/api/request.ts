import type { FieldError } from '@/types/api'

/**
 * fetch 封装（§4.1 / §7.2）：
 * - baseURL `/api/v1`，credentials include（Cookie 三件套 linlog_at / linlog_rt / XSRF-TOKEN 自动携带）；
 * - 解包 { code, message, data, requestId } 壳；
 * - 非 GET 自动附 X-XSRF-TOKEN（读 document.cookie，双提交）；
 * - 40101 → 单飞 POST /auth/refresh 后重放原请求（并发请求共享同一次刷新）；
 * - 40100 / 40102 → 通知全局处理器清会话跳 /login-register（main.ts 注册）；
 * - 40001 → ApiError 携带 fieldErrors；42900 → 携带 Retry-After；
 * - 刷新的网络级瞬时失败不清会话（保留旧会话快照，继承原项目语义）。
 */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export type QueryValue = string | number | boolean | null | undefined
export type QueryParams = Record<string, QueryValue>

export interface RequestOptions {
  method?: HttpMethod
  query?: QueryParams
  body?: unknown
  signal?: AbortSignal
  /**
   * 40100/40102 认证失败时的行为：
   * - redirect（默认）：通知全局处理器（清会话 + 跳登录页）
   * - silent：只抛 ApiError，不做全局导航（供 session.init 的 /auth/me 使用，
   *   避免游客访问门户被强制跳登录）
   */
  authError?: 'redirect' | 'silent'
}

const BASE_URL = '/api/v1'
const REFRESH_PATH = '/auth/refresh'
const XSRF_COOKIE = 'XSRF-TOKEN'
const XSRF_HEADER = 'X-XSRF-TOKEN'
/** -1 表示网络层/网关层失败，非后端业务码 */
export const NETWORK_ERROR_CODE = -1

type AuthErrorHandler = (code: number) => void
let authErrorHandler: AuthErrorHandler | null = null

export function setAuthErrorHandler(handler: AuthErrorHandler | null): void {
  authErrorHandler = handler
}

export class ApiError extends Error {
  readonly code: number
  readonly requestId?: string
  readonly fieldErrors?: FieldError[]
  readonly retryAfter?: number

  constructor(
    code: number,
    message: string,
    options: {
      requestId?: string
      fieldErrors?: FieldError[]
      retryAfter?: number
      cause?: unknown
    } = {},
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'ApiError'
    this.code = code
    this.requestId = options.requestId
    this.fieldErrors = options.fieldErrors
    this.retryAfter = options.retryAfter
  }
}

interface ResponseShell {
  code: number
  message: string
  data: unknown
  requestId?: string
}

export function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null
  const prefix = `${name}=`
  for (const piece of document.cookie.split(';')) {
    const item = piece.trim()
    if (item.startsWith(prefix)) {
      const raw = item.slice(prefix.length)
      try {
        return decodeURIComponent(raw)
      } catch {
        return raw
      }
    }
  }
  return null
}

function buildUrl(path: string, query?: QueryParams): string {
  let url = `${BASE_URL}${path}`
  if (query) {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === '') continue
      params.append(key, String(value))
    }
    const qs = params.toString()
    if (qs) url += `?${qs}`
  }
  return url
}

function buildFetchInit(path: string, options: RequestOptions): RequestInit {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = {}
  let body: BodyInit | undefined
  if (options.body !== undefined) {
    if (options.body instanceof FormData) {
      // 让浏览器自动生成 multipart boundary
      body = options.body
    } else {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(options.body)
    }
  }
  // §4.2：所有非 GET 请求须带 X-XSRF-TOKEN（双提交 Cookie）
  if (method !== 'GET') {
    const token = readCookie(XSRF_COOKIE)
    if (token) headers[XSRF_HEADER] = token
  }
  return { method, headers, body, credentials: 'include', signal: options.signal }
}

async function rawRequest(path: string, options: RequestOptions): Promise<Response> {
  try {
    return await fetch(buildUrl(path, options.query), buildFetchInit(path, options))
  } catch (error) {
    throw new ApiError(NETWORK_ERROR_CODE, '网络请求失败或服务不可用', { cause: error })
  }
}

function parseShell(raw: unknown, status: number): ResponseShell {
  if (raw && typeof raw === 'object' && 'code' in raw && typeof (raw as { code: unknown }).code === 'number') {
    const shell = raw as Partial<ResponseShell>
    return {
      code: shell.code,
      message: typeof shell.message === 'string' ? shell.message : '',
      data: shell.data,
      requestId: typeof shell.requestId === 'string' ? shell.requestId : undefined,
    }
  }
  throw new ApiError(status >= 400 ? status : 50000, `服务响应格式异常（HTTP ${status}）`)
}

/** 单飞刷新（§7.2）：并发 40101 共享同一次 POST /auth/refresh，完成后各自重放 */
let refreshPromise: Promise<void> | null = null

function runRefreshOnce(initiating: RequestOptions): Promise<void> {
  if (refreshPromise) return refreshPromise
  const core = (async () => {
    let shell: ResponseShell
    try {
      const response = await rawRequest(REFRESH_PATH, { method: 'POST' })
      shell = parseShell(await response.json(), response.status)
    } catch (error) {
      if (error instanceof ApiError) throw error
      throw new ApiError(NETWORK_ERROR_CODE, '刷新请求失败或响应解析失败', { cause: error })
    }
    if (shell.code === 0) return
    if ((shell.code === 40100 || shell.code === 40102) && initiating.authError !== 'silent') {
      // refresh 无效/重放：清会话并跳登录
      authErrorHandler?.(shell.code)
    }
    throw new ApiError(shell.code, shell.message || '刷新会话失败', { requestId: shell.requestId })
  })()
  refreshPromise = core.finally(() => {
    refreshPromise = null
  })
  return refreshPromise
}

async function handleAuthFailure(shell: ResponseShell, options: RequestOptions): Promise<never> {
  if (options.authError !== 'silent') {
    authErrorHandler?.(shell.code)
  }
  throw new ApiError(shell.code, shell.message || '未认证', { requestId: shell.requestId })
}

export async function request<T>(path: string, options: RequestOptions = {}, replayed = false): Promise<T> {
  const response = await rawRequest(path, options)
  let shell: ResponseShell
  try {
    shell = parseShell(await response.json(), response.status)
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(response.status || 50000, `服务响应解析失败（HTTP ${response.status}）`, { cause: error })
  }

  if (shell.code === 0) return shell.data as T

  if (shell.code === 40101) {
    if (!replayed) {
      await runRefreshOnce(options)
      // 刷新成功，重放原请求（只重放一次）
      return request<T>(path, options, true)
    }
    return handleAuthFailure(shell, options)
  }

  if (shell.code === 40100 || shell.code === 40102) {
    return handleAuthFailure(shell, options)
  }

  if (shell.code === 40001) {
    const data = shell.data as { fieldErrors?: FieldError[] } | null
    throw new ApiError(shell.code, shell.message || '字段校验失败', {
      requestId: shell.requestId,
      fieldErrors: Array.isArray(data?.fieldErrors) ? data.fieldErrors : [],
    })
  }

  if (shell.code === 42900) {
    const retryHeader = response.headers.get('Retry-After')
    const retryAfter = retryHeader === null ? undefined : Number(retryHeader)
    throw new ApiError(shell.code, shell.message || '请求过于频繁', {
      requestId: shell.requestId,
      retryAfter: retryAfter !== undefined && Number.isFinite(retryAfter) ? retryAfter : undefined,
    })
  }

  throw new ApiError(shell.code, shell.message || '请求失败', { requestId: shell.requestId })
}

export function get<T>(
  path: string,
  query?: QueryParams,
  options: Omit<RequestOptions, 'method' | 'query' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'GET', query })
}

export function post<T>(
  path: string,
  body?: unknown,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'POST', body })
}

export function put<T>(
  path: string,
  body?: unknown,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'PUT', body })
}

export function patch<T>(
  path: string,
  body?: unknown,
  options: Omit<RequestOptions, 'method' | 'body'> = {},
): Promise<T> {
  return request<T>(path, { ...options, method: 'PATCH', body })
}

export function del<T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}): Promise<T> {
  return request<T>(path, { ...options, method: 'DELETE' })
}
