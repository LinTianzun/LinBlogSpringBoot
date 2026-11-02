import { del, get, patch, post, put } from './request'
import type {
  AdminContentQuery,
  ContentView,
  ConversationDTO,
  CreateConversationPayload,
  CreateContentPayload,
  DiaryDTO,
  DiaryPayload,
  FileAdminViewDTO,
  LabelDTO,
  LabelPayload,
  MessageDTO,
  MessagePageDTO,
  OverviewSummaryDTO,
  PageQuery,
  PageResult,
  ReportBatchPayload,
  ReportBatchResultDTO,
  ReportHandlePayload,
  ReportQuery,
  ReportViewDTO,
  SendMessagePayload,
  SettingsDTO,
  StorageStatusDTO,
  SubsetDTO,
  SubsetCreatePayload,
  SubsetItemDTO,
  SubsetItemsPayload,
  SubsetUpdatePayload,
  SystemLogQuery,
  SystemLogViewDTO,
  UpdateContentPayload,
  UploadResultDTO,
  VisitStatsRange,
  VisitStatsResultDTO,
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

/* ---------------- 媒体（§5.7 MediaController / FileAdminController） ---------------- */

/**
 * POST /files/upload：multipart 字段名 file（request.ts 对 FormData 不设 Content-Type，
 * 非 GET 自动附 X-XSRF-TOKEN）；file.upload。
 */
export function uploadMedia(file: File): Promise<UploadResultDTO> {
  const form = new FormData()
  form.append('file', file)
  return post<UploadResultDTO>('/files/upload', form)
}

/** GET /admin/files：?state=active|trashed&page=&pageSize= */
export function listAdminFiles(query: PageQuery & { state?: string }): Promise<PageResult<FileAdminViewDTO>> {
  return get<PageResult<FileAdminViewDTO>>('/admin/files', query)
}

/** GET /admin/files/storage-status */
export function getStorageStatus(): Promise<StorageStatusDTO> {
  return get<StorageStatusDTO>('/admin/files/storage-status')
}

/** PATCH /admin/files/{id}/trash：软删进备份区 */
export function trashFile(id: number): Promise<void> {
  return patch<void>(`/admin/files/${id}/trash`)
}

/** PATCH /admin/files/{id}/restore */
export function restoreFile(id: number): Promise<void> {
  return patch<void>(`/admin/files/${id}/restore`)
}

/** DELETE /admin/files/{id}：彻底删除（仅备份区可 purge） */
export function purgeFile(id: number): Promise<void> {
  return del<void>(`/admin/files/${id}`)
}

/* ---------------- 总览（§5.8 OverviewAdminController，overview.view） ---------------- */

/** GET /admin/overview/summary → {contents, comments, unreadMessages, files, viewsToday, viewsWeek} */
export function getOverviewSummary(): Promise<OverviewSummaryDTO> {
  return get<OverviewSummaryDTO>('/admin/overview/summary')
}

/** GET /admin/overview/visit-stats?range=week|moon */
export function getVisitStats(range: VisitStatsRange): Promise<VisitStatsResultDTO> {
  return get<VisitStatsResultDTO>('/admin/overview/visit-stats', { range })
}

/* ---------------- 系统日志（§5.8 SystemLogAdminController，systemLog.view） ---------------- */

/** GET /admin/system-logs?module&action&page */
export function listSystemLogs(query: SystemLogQuery): Promise<PageResult<SystemLogViewDTO>> {
  return get<PageResult<SystemLogViewDTO>>('/admin/system-logs', { ...query })
}

/* ---------------- 系统设置（§5.8 SettingController，system.setting） ---------------- */

/** GET /admin/settings → Map<key,value> */
export function getSettings(): Promise<SettingsDTO> {
  return get<SettingsDTO>('/admin/settings')
}

/** PUT /admin/settings：{settings} 全量提交 */
export function updateSettings(settings: SettingsDTO): Promise<void> {
  return put<void>('/admin/settings', { settings })
}

/* ---------------- 私信（§5.6 MessageController，M4） ---------------- */

/** GET /messages/conversations（message.private） */
export function listConversations(query: PageQuery): Promise<PageResult<ConversationDTO>> {
  return get<PageResult<ConversationDTO>>('/messages/conversations', query)
}

/** POST /messages/conversations：{recipientId, body≤2000} 建会话并发出首条消息 */
export function createConversation(payload: CreateConversationPayload): Promise<MessageDTO> {
  return post<MessageDTO>('/messages/conversations', payload)
}

/** GET /messages/conversations/{id}?before= 游标倒查 */
export function listMessages(conversationId: number, before?: number): Promise<MessagePageDTO> {
  return get<MessagePageDTO>(`/messages/conversations/${conversationId}`, before === undefined ? undefined : { before })
}

/** POST /messages/conversations/{id}：{body} */
export function sendMessage(conversationId: number, payload: SendMessagePayload): Promise<MessageDTO> {
  return post<MessageDTO>(`/messages/conversations/${conversationId}`, payload)
}

/** PATCH /messages/conversations/{id}/read：清未读 */
export function markConversationRead(conversationId: number): Promise<void> {
  return patch<void>(`/messages/conversations/${conversationId}/read`)
}

/** GET /messages/unread-count → 未读总数 */
export function getUnreadCount(): Promise<number> {
  return get<number>('/messages/unread-count')
}

/* ---------------- 评论举报工作台（§5.5 CommentReportAdminController） ---------------- */

/** GET /admin/comment-reports：?status=&page=&pageSize=（status 缺省 pending） */
export function listCommentReports(query: ReportQuery): Promise<PageResult<ReportViewDTO>> {
  return get<PageResult<ReportViewDTO>>('/admin/comment-reports', query)
}

/** PATCH /admin/comment-reports/{id}：{action: resolved|dismissed, note≤200} */
export function handleCommentReport(id: number, payload: ReportHandlePayload): Promise<void> {
  return patch<void>(`/admin/comment-reports/${id}`, payload)
}

/** POST /admin/comment-reports/batch：{ids≤200, action} → {updated, skipped} */
export function batchHandleCommentReports(payload: ReportBatchPayload): Promise<ReportBatchResultDTO> {
  return post<ReportBatchResultDTO>('/admin/comment-reports/batch', payload)
}
