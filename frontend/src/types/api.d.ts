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

/** 分页返回形状：对齐 backend common/PageResult.java（records + total） */
export interface PageResult<T> {
  records: T[]
  total: number
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

/* ---------------- 内容域（§5.3，对齐 backend ContentView） ---------------- */

export type ContentType = 'article' | 'image'
export type ContentState = 'draft' | 'published' | 'hidden' | 'deleted'

/** labels 表实体直出（backend LabelController /portal/home）。 */
export interface LabelDTO {
  id: number
  name: string
}

/**
 * 状态机（backend ContentService §5.3）：
 * draft→{published,deleted}；published→{hidden,deleted}；
 * hidden→{published,draft,deleted}；deleted→{draft}（还原）。
 * article 进入 published 或 published→hidden 需 article.publish。
 */
export interface ContentView {
  id: number
  contentType: ContentType
  title: string
  introduce?: string
  /** 详情返回（ProseMirror JSON 字符串）；列表不回传 */
  body?: string
  /** admin 详情返回的纯文本摘要 */
  plainText?: string
  coverMediaId?: number | null
  state: ContentState
  publishedAt?: string
  views?: number
  praises?: number
  commentsCount?: number
  isPinned?: boolean
  authorId?: number
  authorName?: string
  labelIds?: number[]
  /** 标签名列表（后端已拼好） */
  labels?: string[]
  createdAt?: string
  updatedAt?: string
}

/** 列表视图与详情同构，body 仅详情返回 — 保留旧名做语义别名 */
export type ContentSummaryDTO = ContentView
export type ContentDetailDTO = ContentView

export type PortalContentQuery = PageQuery & {
  contentType?: ContentType
  labelId?: number
  /** latest|hot（后端暂未消费，保留契约） */
  sort?: 'latest' | 'hot'
}

export type AdminContentQuery = PageQuery & {
  contentType?: ContentType
  state?: ContentState
  labelId?: number
}

export interface CreateContentPayload {
  contentType: ContentType
  title: string
  introduce?: string
  /** ProseMirror JSON 字符串（Tiptap getJSON 序列化） */
  body?: string
  coverMediaId?: number | null
  labelIds?: number[]
  state?: ContentState
}

/** PATCH：null 字段不更新（backend ContentUpdateRequest） */
export interface UpdateContentPayload {
  title?: string
  introduce?: string
  body?: string
  coverMediaId?: number | null
  labelIds?: number[]
  isPinned?: boolean
}

/* ---------------- 标签（§5.3 LabelController） ---------------- */

export interface LabelPayload {
  name: string
}

/* ---------------- 合集（SubsetAdminController，自设计端点） ---------------- */

export interface SubsetDTO {
  id: number
  name: string
  description?: string
  coverMediaId?: number | null
  ownerId: number
  createdAt: string
}

export interface SubsetItemDTO {
  subsetId: number
  contentId: number
  sort: number
}

export interface SubsetCreatePayload {
  name: string
  description?: string
  coverMediaId?: number | null
  contentIds?: number[]
}

export interface SubsetUpdatePayload {
  name?: string
  description?: string
  coverMediaId?: number | null
}

/** PUT /admin/subsets/{id}/items：全量替换条目及顺序 */
export interface SubsetItemsPayload {
  contentIds: number[]
}

/* ---------------- 门户首屏（backend PortalController /portal/home） ---------------- */

export interface PortalHomeDTO {
  latest: ContentView[]
  pinned: ContentView[]
  labels: LabelDTO[]
}

/* ---------------- 日记（§5.4，对齐 backend DiaryView） ---------------- */

/** 天气 8 值白名单（§3.2，服务端校验） */
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
  authorId?: number
  authorName?: string
  /** YYYY-MM-DD */
  diaryDate: string
  weather?: Weather
  mood?: string
  /** 纯文本或 Tiptap JSON 字符串（前端防御性解析渲染） */
  body: string
  state: ContentState
  views?: number
  praises?: number
  createdAt?: string
  updatedAt?: string
}

export interface DiaryPayload {
  diaryDate: string
  weather?: Weather
  mood?: string
  body?: string
  state?: ContentState
}

/* ---------------- 媒体（§5.7 MediaController） ---------------- */

export interface UploadResultDTO {
  id: number
  storageKey: string
  /** /api/v1/media/{storageKey} */
  url: string
  kind: string
  mimeType: string
  sizeBytes: number
  duplicated: boolean
}

/* ---------------- 浏览上报（§5.6/§7.6 ViewController） ---------------- */

export type ViewTargetType = 'content' | 'diary'

export interface ViewReportResultDTO {
  counted: boolean
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

/* ---------------- 权限目录（§5.2；对齐 backend PermissionController/PermissionKeys） ---------------- */

/** GET /permissions/catalog → data：{groups, protectedKeys, totalKeys} */
export interface PermissionCatalogKeyDTO {
  key: PermissionKey
  label: string
  isProtected: boolean
}

export interface PermissionCatalogGroupDTO {
  name: string
  items: PermissionCatalogKeyDTO[]
}

export interface PermissionCatalogDTO {
  groups: PermissionCatalogGroupDTO[]
  protectedKeys: PermissionKey[]
  totalKeys: number
}

/** GET /permissions/me → data：{role, permissions[], isSuperAdmin} */
export interface PermissionMeDTO {
  role: RoleCode
  permissions: PermissionKey[]
  isSuperAdmin: boolean
}

/** PUT /permissions/matrix：role → 该角色授予的权限键列表（超管固定全量，不可编辑） */
export type PermissionMatrix = Partial<Record<RoleCode, PermissionKey[]>>

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

/* ---------------- 后台用户管理（§5.6；对齐 backend AdminUserController/AdminUserService） ---------------- */

/** GET/POST/PATCH /admin/users* → data：AdminUserView */
export interface AdminUserView {
  id: number
  username: string
  email: string
  nickname: string
  role: RoleCode
  status: 0 | 1
  /** LocalDateTime → ISO 字符串 */
  createdAt: string
}

/** POST /admin/users：username 3-20 位字母数字下划线；email 邮箱；password 8-72 */
export interface CreateAdminUserPayload {
  username: string
  email: string
  password: string
  nickname?: string
}

/** PATCH /admin/users/{id}：改昵称/邮箱 */
export interface UpdateAdminUserPayload {
  nickname?: string
  email?: string
}

/** PATCH /admin/users/{id}/status：0 停用 / 1 启用（超管不可被禁用 → 40900） */
export interface AdminUserStatusPayload {
  status: 0 | 1
}

/** PATCH /admin/users/{id}/password */
export interface AdminUserResetPasswordPayload {
  password: string
}

/** PATCH /admin/users/{id}/role：仅超管可改；不可降级 super_admin → 40900 */
export interface AdminUserRolePayload {
  role: RoleCode
}
