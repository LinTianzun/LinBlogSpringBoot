import { get, patch, post } from './request'
import type { QueryParams } from './request'
import type {
  AdminUserResetPasswordPayload,
  AdminUserRolePayload,
  AdminUserStatusPayload,
  AdminUserView,
  CreateAdminUserPayload,
  PageQuery,
  PageResult,
  UpdateAdminUserPayload,
} from '@/types/api'

/** GET /admin/users（需 user.view）：keyword 匹配 username/email/nickname，status 过滤，id 倒序 */
export function listAdminUsers(
  params: PageQuery & { status?: number | null },
): Promise<PageResult<AdminUserView>> {
  return get<PageResult<AdminUserView>>('/admin/users', params as QueryParams)
}

/** POST /admin/users（需 user.update）：重名返回 40900 */
export function createAdminUser(payload: CreateAdminUserPayload): Promise<AdminUserView> {
  return post<AdminUserView>('/admin/users', payload)
}

/** PATCH /admin/users/{id}：改昵称/邮箱 */
export function updateAdminUser(id: number, payload: UpdateAdminUserPayload): Promise<AdminUserView> {
  return patch<AdminUserView>(`/admin/users/${id}`, payload)
}

/** PATCH /admin/users/{id}/status（需 user.disable）：超管不可被禁用 → 40900 */
export function updateAdminUserStatus(id: number, payload: AdminUserStatusPayload): Promise<AdminUserView> {
  return patch<AdminUserView>(`/admin/users/${id}/status`, payload)
}

/** PATCH /admin/users/{id}/password（需 user.resetPassword） */
export function resetAdminUserPassword(id: number, payload: AdminUserResetPasswordPayload): Promise<void> {
  return patch<void>(`/admin/users/${id}/password`, payload)
}

/** PATCH /admin/users/{id}/role（需 user.assignAdmin，仅超管）：不可降级 super_admin → 40900 */
export function updateAdminUserRole(id: number, payload: AdminUserRolePayload): Promise<AdminUserView> {
  return patch<AdminUserView>(`/admin/users/${id}/role`, payload)
}
