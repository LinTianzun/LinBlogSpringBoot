import { del, get, patch, post, put } from './request'
import type { AiModelDTO, AiPromptTemplateDTO, AiSettingDTO, AiUsageSummaryDTO } from '@/types/api'

/* ==================== AI 功能端点（登录态 + 配额） ==================== */

/** POST /ai/suggest：文章写作建议（只建议不落库，作者确认后自行填充） */
export function aiSuggestArticleMeta(payload: { title: string; content: string }): Promise<{
  summary: string
  labels: string[]
  modelName: string
  promptName: string
}> {
  return post('/ai/suggest', payload)
}

/** POST /ai/report/review：举报处置建议（处置状态仍由运营自己改） */
export function aiReviewReport(payload: {
  reasonLabel: string
  detail: string
  commentUserName: string
  commentContent: string
  sourceTitle: string
  complaint: number | string
}): Promise<{ verdict: string; reason: string; action: string; note: string; modelName: string }> {
  return post('/ai/report/review', payload)
}

/** POST /ai/report/operations：运营周报（真实统计 → 纯文本） */
export function aiOperationsReport(): Promise<string> {
  return post<string>('/ai/report/operations')
}

/** POST /ai/test-run：提示词试跑（AI 管理页，system.setting） */
export function aiTestRun(payload: { prompt: string; variables?: Record<string, string> }): Promise<{
  output: string
  modelName: string
  durationMs: number
}> {
  return post('/ai/test-run', payload)
}

/* ==================== AI 管理（system.setting） ==================== */

/** GET /admin/ai/models：模型列表（密钥掩码） */
export function listAiModels(): Promise<AiModelDTO[]> {
  return get<AiModelDTO[]>('/admin/ai/models')
}

export function createAiModel(payload: {
  name: string
  baseUrl: string
  apiKey: string
  chatModel: string
  imageModel?: string
  imageSize?: string
  timeoutMs?: number
  enabled?: boolean
  remark?: string
}): Promise<AiModelDTO> {
  return post<AiModelDTO>('/admin/ai/models', payload)
}

export function updateAiModel(id: number, payload: {
  name: string
  baseUrl: string
  apiKey?: string
  chatModel: string
  imageModel?: string
  imageSize?: string
  timeoutMs?: number
  enabled?: boolean
  remark?: string
}): Promise<AiModelDTO> {
  return patch<AiModelDTO>(`/admin/ai/models/${id}`, payload)
}

export function deleteAiModel(id: number): Promise<void> {
  return del<void>(`/admin/ai/models/${id}`)
}

/** PUT /admin/ai/models/{id}/active：设为默认模型 */
export function activateAiModel(id: number): Promise<void> {
  return put<void>(`/admin/ai/models/${id}/active`)
}

/** GET/PUT /admin/ai/settings：策略 */
export function getAiSettings(): Promise<AiSettingDTO> {
  return get<AiSettingDTO>('/admin/ai/settings')
}

export function updateAiSettings(payload: Partial<AiSettingDTO>): Promise<AiSettingDTO> {
  return put<AiSettingDTO>('/admin/ai/settings', payload)
}

/** 提示词模板 CRUD + 启停 + 功能绑定 */
export function listAiTemplates(): Promise<AiPromptTemplateDTO[]> {
  return get<AiPromptTemplateDTO[]>('/admin/ai/prompt-templates')
}

export function createAiTemplate(payload: {
  templateKey: string
  featureKey?: string
  name: string
  description?: string
  content: string
  variables?: string
}): Promise<AiPromptTemplateDTO> {
  return post<AiPromptTemplateDTO>('/admin/ai/prompt-templates', payload)
}

export function updateAiTemplate(id: number, payload: {
  templateKey: string
  featureKey?: string
  name: string
  description?: string
  content: string
  variables?: string
}): Promise<AiPromptTemplateDTO> {
  return patch<AiPromptTemplateDTO>(`/admin/ai/prompt-templates/${id}`, payload)
}

export function deleteAiTemplate(id: number): Promise<void> {
  return del<void>(`/admin/ai/prompt-templates/${id}`)
}

export function toggleAiTemplate(id: number, enabled: boolean): Promise<AiPromptTemplateDTO> {
  return patch<AiPromptTemplateDTO>(`/admin/ai/prompt-templates/${id}/enabled`, { enabled })
}

export function bindAiTemplate(feature: string, templateKey: string): Promise<void> {
  return put<void>(`/admin/ai/bindings/${feature}`, { templateKey })
}

/** GET /admin/ai/usage：最近 30 天用量摘要 */
export function getAiUsage(): Promise<AiUsageSummaryDTO> {
  return get<AiUsageSummaryDTO>('/admin/ai/usage')
}

/** POST /ai/writing：写作辅助（润色/精简/扩写/续写），返回纯文本候选 */
export function aiRewriteText(payload: {
  action: 'polish' | 'shorten' | 'expand' | 'continue'
  title?: string
  text: string
}): Promise<{ text: string; modelName: string }> {
  return post<{ text: string; modelName: string }>('/ai/writing', payload)
}

/** POST /ai/gallery/curation：图库策展建议（候选标题/简介/标签） */
export function aiGalleryCuration(payload: {
  title?: string
  introduce?: string
  theme?: string
  photoCount?: number
}): Promise<{ titles: string[]; summary: string; labels: string[]; modelName: string }> {
  return post<{ titles: string[]; summary: string; labels: string[]; modelName: string }>(
    '/ai/gallery/curation',
    payload,
  )
}

/** POST /ai/message/reply：私信拟回复（2 条候选话术；会话双方仅可对自己会话调用） */
export function aiMessageReply(payload: {
  conversationId: number
  tone?: string
}): Promise<{ replies: string[] }> {
  return post<{ replies: string[] }>('/ai/message/reply', payload)
}

/** POST /ai/cover：生图候选（token + dataUri 预览；采用需走 confirm） */
export function aiGenerateCover(payload: {
  title: string
  introduce?: string
  content?: string
}): Promise<{ token: string; dataUri: string; prompt: string; modelName: string }> {
  return post<{ token: string; dataUri: string; prompt: string; modelName: string }>('/ai/cover', payload)
}

/** POST /ai/cover/confirm：一次性消费 token → 落 media → 返回封面引用 */
export function aiConfirmCover(token: string): Promise<{ mediaId: number; storageKey: string; url: string }> {
  return post<{ mediaId: number; storageKey: string; url: string }>('/ai/cover/confirm', { token })
}
