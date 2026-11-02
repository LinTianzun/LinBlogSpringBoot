import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * admin.ts 接口契约：内容/标签/合集/日记/媒体/总览/举报/文件/日志/设置/私信
 * 各端点的路径、query、body 形状与 HTTP 动词（§5.3-§5.8，M3/M4）。
 */

vi.mock('@/api/request', () => ({
  get: vi.fn(async () => undefined),
  post: vi.fn(async () => undefined),
  put: vi.fn(async () => undefined),
  patch: vi.fn(async () => undefined),
  del: vi.fn(async () => undefined),
}))

import { del as apiDel, get as apiGet, patch as apiPatch, post as apiPost, put as apiPut } from '@/api/request'
import {
  batchHandleCommentReports,
  createContent,
  createConversation,
  createDiary,
  createLabel,
  createSubset,
  deleteContent,
  deleteDiary,
  deleteLabel,
  deleteSubset,
  getAdminContent,
  getAdminDiary,
  getOverviewSummary,
  getSettings,
  getStorageStatus,
  getSubset,
  getSubsetItems,
  getUnreadCount,
  getVisitStats,
  handleCommentReport,
  listAdminContents,
  listAdminDiaries,
  listAdminFiles,
  listCommentReports,
  listConversations,
  listLabels,
  listMessages,
  listSubsets,
  listSystemLogs,
  markConversationRead,
  purgeContent,
  purgeFile,
  replaceSubsetItems,
  restoreFile,
  sendMessage,
  trashFile,
  updateContent,
  updateContentState,
  updateDiary,
  updateLabel,
  updateSettings,
  updateSubset,
  uploadMedia,
} from '@/api/admin'

const mockedGet = vi.mocked(apiGet)
const mockedPost = vi.mocked(apiPost)
const mockedPut = vi.mocked(apiPut)
const mockedPatch = vi.mocked(apiPatch)
const mockedDel = vi.mocked(apiDel)

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
  mockedPut.mockReset()
  mockedPatch.mockReset()
  mockedDel.mockReset()
})

describe('内容域（/admin/contents）', () => {
  it('listAdminContents → GET /admin/contents 透传 query', async () => {
    const query = { contentType: 'article' as const, page: 1, pageSize: 10, keyword: 'vue' }
    await listAdminContents(query)
    expect(mockedGet).toHaveBeenCalledWith('/admin/contents', query)
  })

  it('getAdminContent → GET /admin/contents/{id}', async () => {
    await getAdminContent(7)
    expect(mockedGet).toHaveBeenCalledWith('/admin/contents/7')
  })

  it('createContent → POST /admin/contents 透传 payload（body 为 ProseMirror JSON 字符串）', async () => {
    const payload = {
      contentType: 'article' as const,
      title: '标题',
      body: '{"type":"doc","content":[]}',
    }
    await createContent(payload)
    expect(mockedPost).toHaveBeenCalledWith('/admin/contents', payload)
  })

  it('updateContent → PATCH /admin/contents/{id}', async () => {
    const payload = { title: '新标题' }
    await updateContent(7, payload)
    expect(mockedPatch).toHaveBeenCalledWith('/admin/contents/7', payload)
  })

  it('updateContentState → PATCH /admin/contents/{id}/state {state}', async () => {
    await updateContentState(7, 'published')
    expect(mockedPatch).toHaveBeenCalledWith('/admin/contents/7/state', { state: 'published' })
  })

  it('deleteContent → DELETE /admin/contents/{id}；purgeContent → DELETE .../purge', async () => {
    await deleteContent(7)
    expect(mockedDel).toHaveBeenCalledWith('/admin/contents/7')

    await purgeContent(7)
    expect(mockedDel).toHaveBeenLastCalledWith('/admin/contents/7/purge')
  })
})

describe('标签域', () => {
  it('listLabels → GET /labels（公开）', async () => {
    await listLabels()
    expect(mockedGet).toHaveBeenCalledWith('/labels')
  })

  it('create/update/deleteLabel 对应 /admin/labels 契约', async () => {
    await createLabel({ name: '前端' })
    expect(mockedPost).toHaveBeenCalledWith('/admin/labels', { name: '前端' })

    await updateLabel(3, { name: '后端' })
    expect(mockedPatch).toHaveBeenCalledWith('/admin/labels/3', { name: '后端' })

    await deleteLabel(3)
    expect(mockedDel).toHaveBeenCalledWith('/admin/labels/3')
  })
})

describe('合集域（/admin/subsets）', () => {
  it('list/get/subsets 读取端点', async () => {
    await listSubsets({ page: 1, pageSize: 10 })
    expect(mockedGet).toHaveBeenCalledWith('/admin/subsets', { page: 1, pageSize: 10 })

    await getSubset(5)
    expect(mockedGet).toHaveBeenCalledWith('/admin/subsets/5')

    await getSubsetItems(5)
    expect(mockedGet).toHaveBeenCalledWith('/admin/subsets/5/items')
  })

  it('create/update/replaceItems/delete 写入端点', async () => {
    await createSubset({ name: '合集' })
    expect(mockedPost).toHaveBeenCalledWith('/admin/subsets', { name: '合集' })

    await updateSubset(5, { name: '新名' })
    expect(mockedPatch).toHaveBeenCalledWith('/admin/subsets/5', { name: '新名' })

    await replaceSubsetItems(5, { contentIds: [1, 2] })
    expect(mockedPut).toHaveBeenCalledWith('/admin/subsets/5/items', { contentIds: [1, 2] })

    await deleteSubset(5)
    expect(mockedDel).toHaveBeenCalledWith('/admin/subsets/5')
  })
})

describe('日记域（/admin/diaries）', () => {
  it('listAdminDiaries → GET /admin/diaries 透传 query', async () => {
    await listAdminDiaries({ page: 2, pageSize: 10, state: 'published' })
    expect(mockedGet).toHaveBeenCalledWith('/admin/diaries', { page: 2, pageSize: 10, state: 'published' })
  })

  it('get/create/update/delete 对应 /admin/diaries 契约', async () => {
    await getAdminDiary(4)
    expect(mockedGet).toHaveBeenCalledWith('/admin/diaries/4')

    const payload = { diaryDate: '2026-01-01', weather: '晴天' as const, mood: '开心', body: '正文' }
    await createDiary(payload)
    expect(mockedPost).toHaveBeenCalledWith('/admin/diaries', payload)

    await updateDiary(4, payload)
    expect(mockedPatch).toHaveBeenCalledWith('/admin/diaries/4', payload)

    await deleteDiary(4)
    expect(mockedDel).toHaveBeenCalledWith('/admin/diaries/4')
  })
})

describe('媒体与总览', () => {
  it('uploadMedia → POST /files/upload，body 为同一 FormData 实例', async () => {
    const file = new File(['x'], 'a.png', { type: 'image/png' })
    await uploadMedia(file)
    expect(mockedPost).toHaveBeenCalledTimes(1)
    const [path, body] = mockedPost.mock.calls[0]
    expect(path).toBe('/files/upload')
    expect(body).toBeInstanceOf(FormData)
    expect(body as FormData).toBe(mockedPost.mock.calls[0][1])
    expect((body as FormData).get('file')).toBe(file)
  })

  it('getOverviewSummary → GET /admin/overview/summary', async () => {
    await getOverviewSummary()
    expect(mockedGet).toHaveBeenCalledWith('/admin/overview/summary')
  })

  it('getVisitStats → GET /admin/overview/visit-stats?range=', async () => {
    await getVisitStats('week')
    expect(mockedGet).toHaveBeenCalledWith('/admin/overview/visit-stats', { range: 'week' })
  })
})

describe('举报工作台（/admin/comment-reports，M3）', () => {
  it('listCommentReports → GET /admin/comment-reports 透传 status 分页', async () => {
    const query = { page: 1, pageSize: 10, status: 'pending' as const }
    await listCommentReports(query)
    expect(mockedGet).toHaveBeenCalledWith('/admin/comment-reports', query)
  })

  it('handleCommentReport → PATCH /admin/comment-reports/{id} {action, note?}', async () => {
    await handleCommentReport(11, { action: 'resolved', note: '已处理' })
    expect(mockedPatch).toHaveBeenCalledWith('/admin/comment-reports/11', { action: 'resolved', note: '已处理' })
  })

  it('batchHandleCommentReports → POST /admin/comment-reports/batch {ids, action}', async () => {
    await batchHandleCommentReports({ ids: [1, 2, 3], action: 'dismissed' })
    expect(mockedPost).toHaveBeenCalledWith('/admin/comment-reports/batch', { ids: [1, 2, 3], action: 'dismissed' })
  })
})

describe('文件管理（/admin/files，M4）', () => {
  it('listAdminFiles → GET /admin/files 透传 state 分页', async () => {
    await listAdminFiles({ page: 1, pageSize: 12, state: 'trashed' })
    expect(mockedGet).toHaveBeenCalledWith('/admin/files', { page: 1, pageSize: 12, state: 'trashed' })
  })

  it('getStorageStatus → GET /admin/files/storage-status', async () => {
    await getStorageStatus()
    expect(mockedGet).toHaveBeenCalledWith('/admin/files/storage-status')
  })

  it('trash/restore → PATCH /{id}/trash|restore；purge → DELETE /{id}', async () => {
    await trashFile(9)
    expect(mockedPatch).toHaveBeenCalledWith('/admin/files/9/trash')

    await restoreFile(9)
    expect(mockedPatch).toHaveBeenLastCalledWith('/admin/files/9/restore')

    await purgeFile(9)
    expect(mockedDel).toHaveBeenCalledWith('/admin/files/9')
  })
})

describe('系统日志与设置（M4）', () => {
  it('listSystemLogs → GET /admin/system-logs 透传 module/action', async () => {
    await listSystemLogs({ page: 1, pageSize: 20, module: 'content', action: 'publish' })
    expect(mockedGet).toHaveBeenCalledWith('/admin/system-logs', { page: 1, pageSize: 20, module: 'content', action: 'publish' })
  })

  it('getSettings → GET /admin/settings；updateSettings → PUT /admin/settings {settings}', async () => {
    await getSettings()
    expect(mockedGet).toHaveBeenCalledWith('/admin/settings')

    await updateSettings({ siteName: 'LinBlog' })
    expect(mockedPut).toHaveBeenCalledWith('/admin/settings', { settings: { siteName: 'LinBlog' } })
  })
})

describe('私信（/messages，M4）', () => {
  it('listConversations → GET /messages/conversations 透传分页', async () => {
    await listConversations({ page: 1, pageSize: 20 })
    expect(mockedGet).toHaveBeenCalledWith('/messages/conversations', { page: 1, pageSize: 20 })
  })

  it('createConversation → POST /messages/conversations {recipientId, body}', async () => {
    await createConversation({ recipientId: 5, body: '你好' })
    expect(mockedPost).toHaveBeenCalledWith('/messages/conversations', { recipientId: 5, body: '你好' })
  })

  it('listMessages → GET /messages/conversations/{id}，before 有无分别处理', async () => {
    await listMessages(8)
    expect(mockedGet).toHaveBeenCalledWith('/messages/conversations/8', undefined)

    await listMessages(8, 100)
    expect(mockedGet).toHaveBeenLastCalledWith('/messages/conversations/8', { before: 100 })
  })

  it('sendMessage → POST /messages/conversations/{id} {body}', async () => {
    await sendMessage(8, { body: '在吗' })
    expect(mockedPost).toHaveBeenCalledWith('/messages/conversations/8', { body: '在吗' })
  })

  it('markConversationRead → PATCH /messages/conversations/{id}/read', async () => {
    await markConversationRead(8)
    expect(mockedPatch).toHaveBeenCalledWith('/messages/conversations/8/read')
  })

  it('getUnreadCount → GET /messages/unread-count', async () => {
    await getUnreadCount()
    expect(mockedGet).toHaveBeenCalledWith('/messages/unread-count')
  })
})
