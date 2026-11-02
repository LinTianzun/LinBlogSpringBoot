import { del, get, post } from './request'
import type {
  CommentListDTO,
  CommentTargetType,
  CommentViewDTO,
  ContentView,
  CreateCommentPayload,
  DiaryDTO,
  GuestbookCreateResultDTO,
  GuestbookEntryDTO,
  InteractionTargetType,
  PageQuery,
  PageResult,
  PortalContentQuery,
  PortalHomeDTO,
  PraiseToggleResultDTO,
  ReportCreatePayload,
  ViewReportResultDTO,
  ViewTargetType,
} from '@/types/api'

/** GET /portal/home：{latest, pinned, labels}（backend PortalController.PortalHome） */
export function getPortalHome(): Promise<PortalHomeDTO> {
  return get<PortalHomeDTO>('/portal/home')
}

/** GET /portal/contents：门户口径恒 published；sort=latest|hot（后端暂未消费） */
export function listPortalContents(query: PortalContentQuery): Promise<PageResult<ContentView>> {
  return get<PageResult<ContentView>>('/portal/contents', query)
}

/**
 * GET /portal/contents/{id}：仅 published；?preview=1 作者/管理者可看非 published。
 * body 为 ProseMirror JSON 字符串；后端顺带按 IP+UA 记浏览（30 分钟去重）。
 */
export function getPortalContent(id: number, preview = false): Promise<ContentView> {
  return get<ContentView>(`/portal/contents/${id}`, preview ? { preview: 1 } : undefined)
}

/** GET /portal/diaries：公开，published */
export function listPortalDiaries(query: PageQuery): Promise<PageResult<DiaryDTO>> {
  return get<PageResult<DiaryDTO>>('/portal/diaries', query)
}

/** POST /views：浏览上报 {targetType, targetId} → {counted}（§5.6/§7.6，30 分钟去重） */
export function reportView(targetType: ViewTargetType, targetId: number): Promise<ViewReportResultDTO> {
  return post<ViewReportResultDTO>('/views', { targetType, targetId })
}

/** §5.6 GET /guestbook：全部 visible 一级留言 + 两级回复（backend GuestbookView） */
export function getGuestbook(): Promise<GuestbookEntryDTO[]> {
  return get<GuestbookEntryDTO[]>('/guestbook')
}

/** §5.6 POST /guestbook：{body, replyToId?}——新留言 replyToId 缺省；回复传目标留言/回复 id */
export function createGuestbookEntry(
  body: string,
  replyToId?: number,
): Promise<GuestbookCreateResultDTO> {
  return post<GuestbookCreateResultDTO>(
    '/guestbook',
    replyToId === undefined ? { body } : { body, replyToId },
  )
}

/** §5.6 DELETE /guestbook/{id}：作者或 message.wallModerate（逻辑删，一级连带回复） */
export function deleteGuestbookEntry(id: number): Promise<void> {
  return del<void>(`/guestbook/${id}`)
}

/** §5.5 GET /portal/{targetType}/{id}/comments：一级 + 楼中楼两段（backend CommentThread） */
export function listComments(
  targetType: CommentTargetType,
  targetId: number,
  page = 1,
): Promise<CommentListDTO> {
  return get<CommentListDTO>(`/portal/${targetType}/${targetId}/comments`, { page })
}

/** §5.5 POST /comments：{targetType, targetId, parentId?, replyToCommentId?, body} → 评论视图 */
export function createComment(payload: CreateCommentPayload): Promise<CommentViewDTO> {
  return post<CommentViewDTO>('/comments', payload)
}

/** §5.5 DELETE /comments/{id}：作者或 comment.moderate（一级删连带回复） */
export function deleteComment(id: number): Promise<void> {
  return del<void>(`/comments/${id}`)
}

/** §5.5 POST /comments/{id}/praise：toggle → {active, count}（游客 40100） */
export function praiseComment(id: number): Promise<PraiseToggleResultDTO> {
  return post<PraiseToggleResultDTO>(`/comments/${id}/praise`)
}

/** §5.5 POST /comments/{id}/report：{reason∈白名单, detail≤200}（登录） */
export function reportComment(id: number, payload: ReportCreatePayload): Promise<void> {
  return post<void>(`/comments/${id}/report`, payload)
}

/** §5.5 POST /interactions：内容/日记点赞 toggle */
export function praiseInteraction(
  targetType: InteractionTargetType,
  targetId: number,
): Promise<PraiseToggleResultDTO> {
  return post<PraiseToggleResultDTO>('/interactions', { targetType, targetId })
}
