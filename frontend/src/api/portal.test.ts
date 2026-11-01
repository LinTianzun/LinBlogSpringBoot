import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * portal.ts 接口契约：路径、query、body 形状与 request 层调用方式。
 * mock 掉请求层，只断言封装行为（§4.1 契约测试）。
 */

vi.mock('@/api/request', () => ({
  get: vi.fn(async () => undefined),
  post: vi.fn(async () => undefined),
  put: vi.fn(async () => undefined),
  patch: vi.fn(async () => undefined),
  del: vi.fn(async () => undefined),
}))

import { get as apiGet, post as apiPost } from '@/api/request'
import {
  createComment,
  createGuestbookEntry,
  getGuestbook,
  getPortalContent,
  getPortalHome,
  listComments,
  listPortalContents,
  listPortalDiaries,
  praiseComment,
  praiseInteraction,
  reportView,
} from '@/api/portal'

const mockedGet = vi.mocked(apiGet)
const mockedPost = vi.mocked(apiPost)

beforeEach(() => {
  mockedGet.mockReset()
  mockedPost.mockReset()
})

describe('GET 封装', () => {
  it('getPortalHome → GET /portal/home', async () => {
    await getPortalHome()
    expect(mockedGet).toHaveBeenCalledWith('/portal/home')
    expect(mockedGet).toHaveBeenCalledTimes(1)
  })

  it('listPortalContents → GET /portal/contents 并透传 query', async () => {
    const query = { contentType: 'article' as const, page: 2, pageSize: 10, keyword: 'vue' }
    await listPortalContents(query)
    expect(mockedGet).toHaveBeenCalledWith('/portal/contents', query)
  })

  it('getPortalContent(id) → GET /portal/contents/{id}，无 preview 参数', async () => {
    await getPortalContent(3)
    expect(mockedGet).toHaveBeenCalledWith('/portal/contents/3', undefined)
  })

  it('getPortalContent(id, true) → query 带 preview=1', async () => {
    await getPortalContent(3, true)
    expect(mockedGet).toHaveBeenCalledWith('/portal/contents/3', { preview: 1 })
  })

  it('listPortalDiaries → GET /portal/diaries 并透传分页', async () => {
    await listPortalDiaries({ page: 1, pageSize: 10 })
    expect(mockedGet).toHaveBeenCalledWith('/portal/diaries', { page: 1, pageSize: 10 })
  })

  it('getGuestbook → GET /guestbook', async () => {
    await getGuestbook()
    expect(mockedGet).toHaveBeenCalledWith('/guestbook')
  })

  it('listComments → GET /portal/{targetType}/{id}/comments，page 缺省 1', async () => {
    await listComments('content', 7)
    expect(mockedGet).toHaveBeenCalledWith('/portal/content/7/comments', { page: 1 })

    await listComments('diary', 9, 3)
    expect(mockedGet).toHaveBeenLastCalledWith('/portal/diary/9/comments', { page: 3 })
  })
})

describe('POST 封装', () => {
  it('reportView → POST /views {targetType, targetId}', async () => {
    await reportView('content', 5)
    expect(mockedPost).toHaveBeenCalledWith('/views', { targetType: 'content', targetId: 5 })
  })

  it('createGuestbookEntry 新留言：仅 {body}', async () => {
    await createGuestbookEntry('你好')
    expect(mockedPost).toHaveBeenCalledWith('/guestbook', { body: '你好' })
  })

  it('createGuestbookEntry 回复：携带 replyToId', async () => {
    await createGuestbookEntry('回复你', 12)
    expect(mockedPost).toHaveBeenCalledWith('/guestbook', { body: '回复你', replyToId: 12 })
  })

  it('createComment → POST /comments 透传 payload', async () => {
    const payload = { targetType: 'content' as const, targetId: 1, body: '评论' }
    await createComment(payload)
    expect(mockedPost).toHaveBeenCalledWith('/comments', payload)
  })

  it('praiseComment → POST /comments/{id}/praise 无 body', async () => {
    await praiseComment(8)
    expect(mockedPost).toHaveBeenCalledWith('/comments/8/praise')
  })

  it('praiseInteraction → POST /interactions {targetType, targetId}', async () => {
    await praiseInteraction('diary', 4)
    expect(mockedPost).toHaveBeenCalledWith('/interactions', { targetType: 'diary', targetId: 4 })
  })
})
