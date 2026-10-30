import { get, put } from './request'
import type { PermissionCatalogDTO, PermissionMatrix, PermissionMeDTO } from '@/types/api'

/** GET /permissions/me（登录即可）：{role, permissions[], isSuperAdmin} */
export function getMyPermissions(): Promise<PermissionMeDTO> {
  return get<PermissionMeDTO>('/permissions/me')
}

/** GET /permissions/catalog（需 permission.view）：分组目录 + 受保护键 + 总键数 */
export function getPermissionCatalog(): Promise<PermissionCatalogDTO> {
  return get<PermissionCatalogDTO>('/permissions/catalog')
}

/** PUT /permissions/matrix（仅超管，permission.edit）：返回 role → 键数 */
export function savePermissionMatrix(matrix: PermissionMatrix): Promise<Record<string, number>> {
  return put<Record<string, number>>('/permissions/matrix', { matrix })
}
