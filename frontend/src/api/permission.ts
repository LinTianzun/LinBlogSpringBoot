import { get } from './request'
import type { PermissionCatalogGroupDTO, PermissionKey } from '@/types/api'

/** GET /permissions/me（§5.2 登录后首拉；会话快照刷新时可对比） */
export function getMyPermissions(): Promise<PermissionKey[]> {
  return get<PermissionKey[]>('/permissions/me')
}

/** GET /permissions/catalog（permission.view）：权限分组目录 + 受保护标记 */
export function getPermissionCatalog(): Promise<PermissionCatalogGroupDTO[]> {
  return get<PermissionCatalogGroupDTO[]>('/permissions/catalog')
}
