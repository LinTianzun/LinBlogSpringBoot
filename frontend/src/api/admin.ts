import { del, get, patch, post } from './request'
import type {
  AdminContentQuery,
  ContentDetailDTO,
  ContentSummaryDTO,
  CreateContentPayload,
  OverviewSummaryDTO,
  PageResult,
  UpdateContentPayload,
  UpdateContentStatePayload,
} from '@/types/api'

/** §5.8 GET /admin/overview/summary（overview.view） */
export function getOverviewSummary(): Promise<OverviewSummaryDTO> {
  return get<OverviewSummaryDTO>('/admin/overview/summary')
}

/** §5.3 GET /admin/contents：管理列表；无 viewAll 时后端自动加 author_id=self */
export function listAdminContents(query: AdminContentQuery): Promise<PageResult<ContentSummaryDTO>> {
  return get<PageResult<ContentSummaryDTO>>('/admin/contents', query)
}

/** §5.3 GET /admin/contents/{id}：viewAll 或 viewOwn+归属，否则 40400 */
export function getAdminContent(id: number): Promise<ContentDetailDTO> {
  return get<ContentDetailDTO>(`/admin/contents/${id}`)
}

/** §5.3 POST /admin/contents；里程碑 0 假设返回 { id } */
export function createContent(payload: CreateContentPayload): Promise<{ id: number }> {
  return post<{ id: number }>('/admin/contents', payload)
}

/** §5.3 PATCH /admin/contents/{id}：部分更新 */
export function updateContent(id: number, payload: UpdateContentPayload): Promise<void> {
  return patch<void>(`/admin/contents/${id}`, payload)
}

/** §5.3 PATCH /admin/contents/{id}/state：published↔hidden/draft，写 published_at */
export function updateContentState(
  id: number,
  state: UpdateContentStatePayload['state'],
): Promise<void> {
  return patch<void>(`/admin/contents/${id}/state`, { state })
}

/** §5.3 DELETE /admin/contents/{id}：state=deleted，封面无引用则 media 转 trash */
export function deleteContent(id: number): Promise<void> {
  return del<void>(`/admin/contents/${id}`)
}
