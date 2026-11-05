/**
 * 领域投影层（hand-curated projection）。
 *
 * 契约事实源 = src/types/schema.d.ts（`pnpm gen:api` 从 openapi.json 生成，
 * CI contract job 对 schema.d.ts 做漂移检查）。本文件保持 40+ 消费文件的
 * 领域名（ContentView/DiaryDTO/CommentViewDTO…）与必选形状（生成物因
 * springdoc 不感知 @NotNull 而全 optional，在此投影层收紧），形状以
 * schema.d.ts 为准；《Vue3-SpringBoot重实现开发文档》§4/§5 为需求来源。
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
  /** 强制首改标记（P3）：1 = 种子超管尚未完成首次改密，路由守卫强制跳转改密页 */
  mustChangePassword: 0 | 1
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
  /**
   * 封面对应的媒体 storageKey（backend ContentView 新增；brief/列表与详情均回传）。
   * 图片地址规则：`/api/v1/media/${coverStorageKey}?width=320|640|1280`（MediaController 参数名是 width）。
   * 为空时可渲染主色渐变占位块。
   */
  coverStorageKey?: string
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
  /** 日记封面 media storageKey（可选；后端可选回传），图片地址 `/api/v1/media/${coverStorageKey}?width=W` */
  coverStorageKey?: string
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

/* ---------------- 评论与互动（§3.3 / §5.5，对齐 backend CommentView） ---------------- */

/** 评论挂载目标白名单（§4.2，服务端映射到表；article/image→content） */
export type CommentTargetType = CommentViewTargetType

/** 评论挂载目标白名单（§4.2，服务端映射到表） */
export type CommentViewTargetType = 'article' | 'image' | 'diary' | 'guestbook'

/** 统一评论视图：门户两段式与留言墙树共用（backend CommentView） */
export interface CommentViewDTO {
  id: number
  targetType: CommentViewTargetType
  targetId: number
  authorId: number
  authorName: string
  parentId: number | null
  replyToCommentId: number | null
  replyToUserId: number | null
  replyToUserName: string | null
  body: string
  praiseCount: number
  /** 仅登录请求方可能为 true */
  liked: boolean
  /** backend CommentView record 未输出 createdAt，可能缺省 */
  createdAt?: string
  /** 仅留言墙树形返回时填充；两段式接口不返回 */
  subReplies?: CommentViewDTO[]
}

/** §5.5 两段返回（backend CommentThread）：一级 + 楼中楼平铺 */
export interface CommentListDTO {
  rootComments: CommentViewDTO[]
  replyComments: CommentViewDTO[]
}

export interface CreateCommentPayload {
  targetType: CommentTargetType
  targetId: number
  /** 一级评论的父评论 id；后端 CommentCreateRequest 接受 parent_id/parentId 双写，前端统一 camelCase */
  parentId?: number
  replyToCommentId?: number | null
  body: string
}

export interface PraiseToggleResultDTO {
  active: boolean
  count: number
}

export type InteractionTargetType = 'content' | 'diary'

/* ---------------- 评论举报工作台（§5.5 CommentReportAdminController） ---------------- */

export type ReportStatus = 'pending' | 'resolved' | 'dismissed'
export type ReportAction = 'resolved' | 'dismissed'

/** 举报工作台行（backend ReportView 为 NON_NULL → 缺省字段全部可选） */
export interface ReportViewDTO {
  id: number
  commentId: number
  commentBody?: string
  commentAuthorId?: number
  commentAuthorName?: string
  commentStatus?: string
  reporterId?: number
  reporterName?: string
  reason: string
  detail?: string
  status: ReportStatus
  handledByName?: string
  handleNote?: string
  createdAt: string
  handledAt?: string
}

/** PATCH /admin/comment-reports/{id}：{action: resolved|dismissed, note≤200} */
export interface ReportHandlePayload {
  action: ReportAction
  note?: string
}

/** POST /admin/comment-reports/batch：{ids≤200, action} */
export interface ReportBatchPayload {
  ids: number[]
  action: ReportAction
}

export interface ReportBatchResultDTO {
  updated: number
  skipped: number
}

export type ReportQuery = PageQuery & {
  /** 缺省 pending */
  status?: ReportStatus
}

/** 举报原因白名单（backend ReportService.REASONS） */
export type ReportReason = 'spam' | 'abuse' | 'porn' | 'illegal' | 'misinformation' | 'other'

/** POST /comments/{id}/report：{reason, detail≤200} */
export interface ReportCreatePayload {
  reason: ReportReason
  detail?: string
}

/* ---------------- 留言墙（§3.4 / §5.6，对齐 backend GuestbookView） ---------------- */

export interface GuestbookEntryDTO {
  id: number
  userId: number
  userName: string
  body: string
  createdAt: string
  /** 两级回复（来自 comments 表，backend GuestbookView.replies） */
  replies: CommentViewDTO[]
}

/** POST /guestbook 结果：kind=entry（一级留言）| reply（回复，落 comments 表） */
export interface GuestbookCreateResultDTO {
  kind: 'entry' | 'reply'
  entry?: GuestbookEntryDTO
  comment?: CommentViewDTO
}

/* ---------------- 私信（§5.6；对齐 backend MessageService ConversationView/DirectMessageView） ---------------- */

/** 会话对方信息（backend MessageService.Peer） */
export interface MessagePeerDTO {
  id: number
  username: string
  nickname: string
}

export interface ConversationDTO {
  id: number
  peer: MessagePeerDTO
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

/** GET /messages/conversations/{id}?before= 游标倒查结果（records 正序） */
export interface MessagePageDTO {
  records: MessageDTO[]
  hasMore: boolean
}

/** POST /messages/conversations：建会话并发出首条消息 */
export interface CreateConversationPayload {
  recipientId: number
  body: string
}

/** POST /messages/conversations/{id} */
export interface SendMessagePayload {
  body: string
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

/* ---------------- 后台概览（§5.8；对齐 backend SummaryResult/VisitStatsResult） ---------------- */

/** GET /admin/overview/summary（overview.view） */
export interface OverviewSummaryDTO {
  contents: number
  comments: number
  unreadMessages: number
  files: number
  viewsToday: number
  viewsWeek: number
}

/** GET /admin/overview/visit-stats?range=week|moon（缺数日补零） */
export type VisitStatsRange = 'week' | 'moon'

export interface VisitStatsPointDTO {
  date: string
  count: number
}

export interface VisitStatsResultDTO {
  range: VisitStatsRange
  points: VisitStatsPointDTO[]
}

/* ---------------- 系统日志（§5.8；对齐 backend SystemLogAdminController/SystemLogView） ---------------- */

export interface SystemLogQuery {
  page: number
  pageSize: number
  module?: string
  action?: string
}

export interface SystemLogViewDTO {
  id: number
  userId?: number
  userName?: string
  module: string
  action: string
  targetType?: string
  targetId?: number
  detail?: string
  ip?: string
  createdAt: string
}

/* ---------------- 系统设置（§5.8；对齐 backend SettingController：Map<String,String>） ---------------- */

export type SettingsDTO = Record<string, string>

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

/* ---------------- 文件管理（§5.7；对齐 backend FileAdminController/FileAdminView/StorageStatus） ---------------- */

/** GET /admin/files 列表项（state: active 主区 / trashed 备份区） */
export interface FileAdminViewDTO {
  id: number
  uploaderId?: number
  uploaderName?: string
  storageKey: string
  originalName: string
  mimeType: string
  sizeBytes: number
  sha256?: string
  state: 'active' | 'trashed'
  trashedAt?: string
  createdAt: string
}

/** GET /admin/files/storage-status：主区=active，备份区=trash；配额 2GiB，备份区 ≥80% 告警 */
export interface StorageStatusDTO {
  primaryBytes: number
  primaryCount: number
  backupBytes: number
  backupCount: number
  quotaBytes: number
  backupPercent: number
  alert: boolean
}
