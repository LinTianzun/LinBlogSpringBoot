import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * admin.ts 接口契约：内容/标签/合集/日记/媒体/总览各端点的
 * 路径、query、body 形状与 HTTP 动词（§5.3-§5.8）。
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
  createContent,
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
  getSubset,
  getSubsetItems,
  listAdminContents,
  listAdminDiaries,
  listLabels,
  listSubsets,
  purgeContent,
  replaceSubsetItems,
  updateContent,
  updateContentState,
  updateDiary,
  updateLabel,
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
})
