/**
 * 里程碑 0 手写 API 契约（hand-written contract）。
 *
 * 后端 springdoc 产出 openapi.json 后，本文件将被
 * `pnpm gen:api`（openapi-typescript ../openapi.json -o src/types/api.d.ts）
 * 全量替换（开发文档 §1.2 类型单源）。替换前的所有手写形状均以
 * 《Vue3-SpringBoot重实现开发文档》§4（响应壳/错误码/Cookie）与 §5（API 清单）为准；
 * 少数文档未明确形状的字段以注释标注「里程碑 0 假设」，契约生成落地后以契约为准。
 */

/** 统一响应壳（§4.1） */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  requestId: string
}

/** 业务错误码（§4.1 表；0 为成功不在此列） */
export type ApiErrorCode =
  | 40000 // 通用参数错误
  | 40001 // 字段校验失败（data.fieldErrors）
  | 40100 // 未认证
  | 40101 // access 过期（前端单飞刷新后重放）
  | 40102 // refresh 无效/重放（清会话跳登录）
  | 40300 // 无权限（仅管理端）
  | 40400 // 不存在或无权访问（读取类统一 404 防探测）
  | 40900 // 状态冲突
  | 42900 // 频率限制（Retry-After）
  | 50000 // 内部错误

export interface FieldError {
  field: string
  message: string
}

/** §4.2 通用请求参数 */
export type PageQuery = {
  page?: number
  pageSize?: number
  keyword?: string
  sort?: 'latest' | 'hot'
}

/** 里程碑 0 假设：分页返回形状（MyBatis-Plus records 语义），以 openapi 契约为准 */
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
}

/* ---------------- ProseMirror / Tiptap（§3.2 body、§7.4 校验器） ---------------- */

export interface ProseMirrorMark {
  type: string
  attrs?: Record<string, unknown>
}

export interface ProseMirrorNode {
  type: string
  attrs?: Record<string, unknown>
  content?: ProseMirrorNode[]
  marks?: ProseMirrorMark[]
  text?: string
}

export type TiptapDoc = ProseMirrorNode

/* ---------------- 权限（§5.2 共 33 键；带 * 为受保护键） ---------------- */

export type PermissionKey =
  | 'overview.view'
  | 'user.view'
  | 'user.update'
  | 'user.disable'
  | 'user.resetPassword'
  | 'user.assignAdmin' // 受保护
  | 'permission.view'
  | 'permission.edit' // 受保护
  | 'article.viewAll'
  | 'article.viewOwn'
  | 'article.create'
  | 'article.manageOwn'
  | 'article.manageAll'
  | 'article.publish'
  | 'gallery.viewAll'
  | 'gallery.viewOwn'
  | 'gallery.create'
  | 'gallery.manageOwn'
  | 'gallery.manageAll'
  | 'diary.viewAll'
  | 'diary.create'
  | 'diary.manageOwn'
  | 'diary.manageAll'
  | 'comment.viewAll'
  | 'comment.moderate'
  | 'message.private'
  | 'message.wallModerate'
  | 'file.view'
  | 'file.upload'
  | 'file.delete'
  | 'systemLog.view'
  | 'system.setting'
  | 'label.manage'

/* ---------------- 认证（§5.1） ---------------- */

export type RoleCode = 'super_admin' | 'admin' | 'editor' | 'viewer'

export interface UserDTO {
  id: number
  username: string
  email: string
  nickname: string
  avatarMediaId: number | null
  roleCode: RoleCode
  isSuperAdmin: boolean
  status: 0 | 1
  createdAt: string
}

export interface MeDTO {
  user: UserDTO
  permissions: PermissionKey[]
  isSuperAdmin: boolean
}

export interface LoginPayload {
  account: string
  password: string
}

export interface RegisterPayload {
  username: string
  email: string
  password: string
  nickname?: string
}

export interface ResetPasswordRequestPayload {
  email: string
}

export interface ResetPasswordPayload {
  token: string
  newPassword: string
}

export interface ChangePasswordPayload {
  oldPassword: string
  newPassword: string
}

/* ---------------- 内容域（§5.3） ---------------- */

export type ContentType = 'article' | 'image'
export type ContentState = 'draft' | 'published' | 'hidden' | 'deleted'

export interface LabelDTO {
  id: number
  name: string
}

export interface ContentAuthorDTO {
  id: number
  nickname: string
  avatarMediaId: number | null
}

export interface ContentSummaryDTO {
  id: number
  contentType: ContentType
  title: string
  introduce: string
  coverMediaId: number | null
  /** 里程碑 0 假设：封面媒体 storage key，用于拼 /media/{key}?w= 的 srcset */
  coverStorageKey?: string | null
  state: ContentState
  publishedAt: string | null
  views: number
  praises: number
  commentsCount: number
  isPinned: boolean
  author: ContentAuthorDTO | null
  labels: LabelDTO[]
  createdAt: string
  updatedAt: string
}

export interface ContentDetailDTO extends ContentSummaryDTO {
  body: TiptapDoc | null
}

export type PortalContentQuery = PageQuery & {
  contentType: ContentType
  labelId?: number
}

export type AdminContentQuery = PageQuery & {
  contentType: ContentType
  state?: ContentState | 'all'
  labelId?: number
}

export interface CreateContentPayload {
  contentType: ContentType
  title: string
  introduce?: string
  body?: TiptapDoc | null
  coverMediaId?: number | null
  labelIds?: number[]
  isPinned?: boolean
}

export type UpdateContentPayload = Partial<CreateContentPayload>

/** §5.3 PATCH /admin/contents/{id}/state：published↔hidden/draft */
export type UpdateContentStatePayload = {
  state: Exclude<ContentState, 'deleted'>
}

/* ---------------- 门户首屏单请求（§6.2 PortalHome） ---------------- */

export interface PortalHomeDTO {
  pinned: ContentSummaryDTO[]
  latest: ContentSummaryDTO[]
  labels: LabelDTO[]
  galleryPreview: ContentSummaryDTO[]
  diaryPreview: DiaryDTO[]
}

/* ---------------- 日记（§3.2 / §5.4） ---------------- */

/** 天气 8 值白名单（§3.2） */
export type Weather =
  | '晴天'
  | '多云'
  | '大雪'
  | '大雨'
  | '阵风'
  | '雷雨'
  | '雾霾'
  | '夜晚'

export interface DiaryDTO {
  id: number
  authorId: number
  diaryDate: string
  weather: Weather
  mood: string | null
  body: TiptapDoc | null
  state: ContentState
  views: number
  praises: number
  createdAt: string
  updatedAt: string
}

/* ---------------- 评论与互动（§3.3 / §5.5） ---------------- */

export type CommentTargetType = 'content' | 'diary' | 'guestbook'

export interface ReplyCommentDTO {
  id: number
  parentId: number
  replyToCommentId: number | null
  replyToUserId: number | null
  replyToNickname: string | null
  authorId: number
  authorNickname: string
  authorAvatarMediaId: number | null
  body: string
  praiseCount: number
  /** 里程碑 0 假设：后端可返回当前用户是否已点赞 */
  praised?: boolean
  createdAt: string
}

export interface RootCommentDTO {
  id: number
  authorId: number
  authorNickname: string
  authorAvatarMediaId: number | null
  body: string
  praiseCount: number
  praised?: boolean
  createdAt: string
  /** 嵌套形态；若后端走两段式则由 replyComments 按 parentId 归组 */
  replies?: ReplyCommentDTO[]
}

/** §5.5 两段返回（对应原 rootComments / replyComments） */
export interface CommentListDTO {
  rootComments: RootCommentDTO[]
  replyComments: ReplyCommentDTO[]
  total: number
  page: number
  pageSize: number
}

export interface CreateCommentPayload {
  targetType: CommentTargetType
  targetId: number
  /** 文档 §5.5 原文为 parent_id（snake_case），契约生成前保持原文 */
  parent_id?: number
  replyToCommentId?: number | null
  body: string
}

export interface PraiseToggleResultDTO {
  active: boolean
  count: number
}

export type InteractionTargetType = 'content' | 'diary'

/* ---------------- 留言墙（§3.4 / §5.6） ---------------- */

export interface GuestbookAuthorDTO {
  id: number
  nickname: string
  avatarMediaId: number | null
}

export interface GuestbookReplyDTO {
  id: number
  parentId: number | null
  replyToCommentId: number | null
  replyToUserId: number | null
  replyToNickname: string | null
  author: GuestbookAuthorDTO
  body: string
  praiseCount: number
  createdAt: string
}

export interface GuestbookEntryDTO {
  id: number
  user: GuestbookAuthorDTO
  body: string
  status: 'visible' | 'deleted'
  createdAt: string
  replies: GuestbookReplyDTO[]
}

/* ---------------- 私信（§5.6；本轮仅定义基础形状） ---------------- */

export interface ConversationDTO {
  id: number
  peer: GuestbookAuthorDTO
  lastMessageAt: string | null
  lastMessagePreview: string | null
  unread: number
}

export interface MessageDTO {
  id: number
  conversationId: number
  senderId: number
  body: string
  readAt: string | null
  createdAt: string
}

/* ---------------- 权限目录（§5.2，后台权限页用） ---------------- */

export interface PermissionCatalogKeyDTO {
  key: PermissionKey
  isProtected: boolean
}

export interface PermissionCatalogGroupDTO {
  name: string
  keys: PermissionCatalogKeyDTO[]
}

/* ---------------- 后台概览（§5.8） ---------------- */

export interface OverviewSummaryDTO {
  contentCount: number
  commentCount: number
  unreadMessageCount: number
  fileCount: number
  viewsToday: number
  viewsWeek: number
}

export type VisitStatsRange = 'week' | 'moon'

export interface VisitStatsPointDTO {
  date: string
  views: number
}
