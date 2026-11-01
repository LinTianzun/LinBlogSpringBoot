import { del, get, patch, post, put } from './request'
import type {
  AdminContentQuery,
  ContentView,
  CreateContentPayload,
  DiaryDTO,
  DiaryPayload,
  LabelDTO,
  LabelPayload,
  OverviewSummaryDTO,
  PageQuery,
  PageResult,
  SubsetDTO,
  SubsetCreatePayload,
  SubsetItemDTO,
  SubsetItemsPayload,
  SubsetUpdatePayload,
  UpdateContentPayload,
  UploadResultDTO,
} from '@/types/api'

/* ---------------- 内容（§5.3 ContentAdminController） ---------------- */

/** GET /admin/contents：scope=viewAll/viewOwn 自动收窄；keyword like title；isPinned+updatedAt 倒序 */
export function listAdminContents(query: AdminContentQuery): Promise<PageResult<ContentView>> {
  return get<PageResult<ContentView>>('/admin/contents', query)
}

/** GET /admin/contents/{id}：viewAll 或 viewOwn+归属，否则 40400；带 body/plainText */
export function getAdminContent(id: number): Promise<ContentView> {
  return get<ContentView>(`/admin/contents/${id}`)
}

/** POST /admin/contents：image 必须带 coverMediaId */
export function createContent(payload: CreateContentPayload): Promise<ContentView> {
  return post<ContentView>('/admin/contents', payload)
}

/** PATCH /admin/contents/{id}：null 字段不更新 */
export function updateContent(id: number, payload: UpdateContentPayload): Promise<ContentView> {
  return patch<ContentView>(`/admin/contents/${id}`, payload)
}

/**
 * PATCH /admin/contents/{id}/state（状态机由后端校验）：
 * draft→published|deleted；published→hidden|deleted；hidden→published|draft|deleted；deleted→draft（还原）
 */
export function updateContentState(id: number, state: string): Promise<ContentView> {
  return patch<ContentView>(`/admin/contents/${id}/state`, { state })
}

/** DELETE /admin/contents/{id}：入回收站（state=deleted），封面无引用则 media 转 trash */
export function deleteContent(id: number): Promise<void> {
  return del<void>(`/admin/contents/${id}`)
}

/** DELETE /admin/contents/{id}/purge：彻底删除（仅 manageAll） */
export function purgeContent(id: number): Promise<void> {
  return del<void>(`/admin/contents/${id}/purge`)
}

/* ---------------- 标签（LabelController：GET /labels 公开，写操作后端只验登录） ---------------- */

/** GET /api/v1/labels：公开全量 */
export function listLabels(): Promise<LabelDTO[]> {
  return get<LabelDTO[]>('/labels')
}

/** POST /admin/labels：name ≤30 字 */
export function createLabel(payload: LabelPayload): Promise<LabelDTO> {
  return post<LabelDTO>('/admin/labels', payload)
}

/** PATCH /admin/labels/{id} */
export function updateLabel(id: number, payload: LabelPayload): Promise<LabelDTO> {
  return patch<LabelDTO>(`/admin/labels/${id}`, payload)
}

/** DELETE /admin/labels/{id} */
export function deleteLabel(id: number): Promise<void> {
  return del<void>(`/admin/labels/${id}`)
}

/* ---------------- 合集（SubsetAdminController，自设计端点） ---------------- */

/** GET /admin/subsets：viewAll 全量 / viewOwn 仅 own */
export function listSubsets(query: PageQuery): Promise<PageResult<SubsetDTO>> {
  return get<PageResult<SubsetDTO>>('/admin/subsets', query)
}

/** GET /admin/subsets/{id} */
export function getSubset(id: number): Promise<SubsetDTO> {
  return get<SubsetDTO>(`/admin/subsets/${id}`)
}

/** GET /admin/subsets/{id}/items：按 sort 升序 */
export function getSubsetItems(id: number): Promise<SubsetItemDTO[]> {
  return get<SubsetItemDTO[]>(`/admin/subsets/${id}/items`)
}

/** POST /admin/subsets（article.create|gallery.create） */
export function createSubset(payload: SubsetCreatePayload): Promise<SubsetDTO> {
  return post<SubsetDTO>('/admin/subsets', payload)
}

/** PATCH /admin/subsets/{id}：null 字段不更新 */
export function updateSubset(id: number, payload: SubsetUpdatePayload): Promise<void> {
  return patch<void>(`/admin/subsets/${id}`, payload)
}

/** PUT /admin/subsets/{id}/items：全量替换条目及顺序 */
export function replaceSubsetItems(id: number, payload: SubsetItemsPayload): Promise<void> {
  return put<void>(`/admin/subsets/${id}/items`, payload)
}

/** DELETE /admin/subsets/{id}：manageAll 或 owner */
export function deleteSubset(id: number): Promise<void> {
  return del<void>(`/admin/subsets/${id}`)
}

/* ---------------- 日记（§5.4 DiaryAdminController） ---------------- */

/** GET /admin/diaries：scope=viewAll/viewOwn；state 过滤 */
export function listAdminDiaries(query: PageQuery & { state?: string }): Promise<PageResult<DiaryDTO>> {
  return get<PageResult<DiaryDTO>>('/admin/diaries', query)
}

/** GET /admin/diaries/{id} */
export function getAdminDiary(id: number): Promise<DiaryDTO> {
  return get<DiaryDTO>(`/admin/diaries/${id}`)
}

/** POST /admin/diaries：weather 8 值白名单服务端校验 */
export function createDiary(payload: DiaryPayload): Promise<DiaryDTO> {
  return post<DiaryDTO>('/admin/diaries', payload)
}

/** PATCH /admin/diaries/{id}：null 字段不更新 */
export function updateDiary(id: number, payload: DiaryPayload): Promise<DiaryDTO> {
  return patch<DiaryDTO>(`/admin/diaries/${id}`, payload)
}

/** DELETE /admin/diaries/{id}：manageAll 或 manageOwn+归属 */
export function deleteDiary(id: number): Promise<void> {
  return del<void>(`/admin/diaries/${id}`)
}

/* ---------------- 媒体（§5.7 MediaController） ---------------- */

/**
 * POST /files/upload：multipart 字段名 file（request.ts 对 FormData 不设 Content-Type，
 * 非 GET 自动附 X-XSRF-TOKEN）；file.upload。
 */
export function uploadMedia(file: File): Promise<UploadResultDTO> {
  const form = new FormData()
  form.append('file', file)
  return post<UploadResultDTO>('/files/upload', form)
}

/* ---------------- 总览（§5.8，未交付，预留契约） ---------------- */

/** GET /admin/overview/summary（overview.view） */
export function getOverviewSummary(): Promise<OverviewSummaryDTO> {
  return get<OverviewSummaryDTO>('/admin/overview/summary')
}
