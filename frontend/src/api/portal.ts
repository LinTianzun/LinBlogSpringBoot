import { get, post } from './request'
import type {
  CommentListDTO,
  CommentTargetType,
  ContentDetailDTO,
  ContentSummaryDTO,
  CreateCommentPayload,
  DiaryDTO,
  GuestbookEntryDTO,
  InteractionTargetType,
  PageQuery,
  PageResult,
  PortalContentQuery,
  PortalHomeDTO,
  PraiseToggleResultDTO,
} from '@/types/api'

/** §6.2 PortalHome：首屏数据合并为单请求（pinned/latest/labels/galleryPreview/diaryPreview） */
export function getPortalHome(): Promise<PortalHomeDTO> {
  return get<PortalHomeDTO>('/portal/home')
}

/** §5.3 GET /portal/contents：门户口径恒 published，不随登录变化 */
export function listPortalContents(query: PortalContentQuery): Promise<PageResult<ContentSummaryDTO>> {
  return get<PageResult<ContentSummaryDTO>>('/portal/contents', query)
}

/** §5.3 GET /portal/contents/{id}：仅 published；?preview=1 作者/管理者可看草稿；后端顺带记浏览 */
export function getPortalContent(id: number, preview = false): Promise<ContentDetailDTO> {
  return get<ContentDetailDTO>(`/portal/contents/${id}`, preview ? { preview: 1 } : undefined)
}

/** §5.4 GET /portal/diaries：公开，published */
export function listPortalDiaries(query: PageQuery): Promise<PageResult<DiaryDTO>> {
  return get<PageResult<DiaryDTO>>('/portal/diaries', query)
}

/** §5.6 GET /guestbook：全部 visible 一级留言 + 两级回复 */
export function getGuestbook(): Promise<GuestbookEntryDTO[]> {
  return get<GuestbookEntryDTO[]>('/guestbook')
}

/** §5.6 POST /guestbook：{body, replyToId?}——新留言 replyToId 缺省；回复传目标留言/回复 id */
export function createGuestbookEntry(body: string, replyToId?: number): Promise<void> {
  return post<void>('/guestbook', replyToId === undefined ? { body } : { body, replyToId })
}

/** §5.5 GET /portal/{targetType}/{id}/comments：一级 + 楼中楼两段 */
export function listComments(
  targetType: CommentTargetType,
  targetId: number,
  page = 1,
): Promise<CommentListDTO> {
  return get<CommentListDTO>(`/portal/${targetType}/${targetId}/comments`, { page })
}

/** §5.5 POST /comments：{targetType, targetId, parent_id?, replyToCommentId?, body} */
export function createComment(payload: CreateCommentPayload): Promise<void> {
  return post<void>('/comments', payload)
}

/** §5.5 POST /comments/{id}/praise：toggle → {active, count} */
export function praiseComment(id: number): Promise<PraiseToggleResultDTO> {
  return post<PraiseToggleResultDTO>(`/comments/${id}/praise`)
}

/** §5.5 POST /interactions：内容/日记点赞 toggle */
export function praiseInteraction(
  targetType: InteractionTargetType,
  targetId: number,
): Promise<PraiseToggleResultDTO> {
  return post<PraiseToggleResultDTO>('/interactions', { targetType, targetId })
}
