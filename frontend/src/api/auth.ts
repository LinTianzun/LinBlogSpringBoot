import { get, post } from './request'
import type {
  ChangePasswordPayload,
  LoginPayload,
  MeDTO,
  RegisterPayload,
  ResetPasswordPayload,
  ResetPasswordRequestPayload,
} from '@/types/api'

/** POST /auth/login（公开限流；成功 Set-Cookie 两个令牌，禁用号返回 40300） */
export function login(payload: LoginPayload): Promise<void> {
  return post<void>('/auth/login', payload)
}

/** POST /auth/register（公开限流；并发重名返回 40900） */
export function register(payload: RegisterPayload): Promise<void> {
  return post<void>('/auth/register', payload)
}

/**
 * GET /auth/me（§5.1 返回 { user, permissions[], isSuperAdmin }）。
 * authError silent：游客访问门户时 init 失败保持访客，不触发全局跳登录。
 */
export function me(): Promise<MeDTO> {
  return get<MeDTO>('/auth/me', undefined, { authError: 'silent' })
}

/** POST /auth/logout：吊销 refresh family，清 Cookie */
export function logout(): Promise<void> {
  return post<void>('/auth/logout')
}

/** POST /auth/password/reset-request：恒返回 ok，不暴露邮箱存在性 */
export function requestPasswordReset(payload: ResetPasswordRequestPayload): Promise<void> {
  return post<void>('/auth/password/reset-request', payload)
}

/** POST /auth/password/reset：token 一次性，成功后吊销会话全端下线 */
export function resetPassword(payload: ResetPasswordPayload): Promise<void> {
  return post<void>('/auth/password/reset', payload)
}

/** POST /auth/password/change：修改密码后全端下线 */
export function changePassword(payload: ChangePasswordPayload): Promise<void> {
  return post<void>('/auth/password/change', payload)
}
