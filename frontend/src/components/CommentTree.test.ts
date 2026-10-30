import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CommentTree from '@/components/CommentTree.vue'
import type { CommentListDTO, UserDTO } from '@/types/api'

/**
 * CommentTree 两段式评论（§3.3）：两级渲染、回复前缀、点赞 toggle、未登录无操作。
 */

vi.mock('@/api/portal', () => ({
  listComments: vi.fn(),
  createComment: vi.fn(),
  praiseComment: vi.fn(),
}))

import { createComment as apiCreateComment, listComments as apiListComments, praiseComment as apiPraiseComment } from '@/api/portal'

const mockedlistComments = vi.mocked(apiListComments)
const mockedPraise = vi.mocked(apiPraiseComment)
const mockedCreate = vi.mocked(apiCreateComment)

const NOW = new Date('2026-06-01T12:00:00').toISOString()

const COMMENT_DATA: CommentListDTO = {
  total: 3,
  page: 1,
  pageSize: 10,
  rootComments: [
    {
      id: 11,
      authorId: 1,
      authorNickname: '甲',
      authorAvatarMediaId: null,
      body: '第一条评论',
      praiseCount: 3,
      praised: false,
      createdAt: NOW,
    },
    {
      id: 12,
      authorId: 2,
      authorNickname: '乙',
      authorAvatarMediaId: null,
      body: '第二条评论',
      praiseCount: 0,
      praised: false,
      createdAt: NOW,
    },
  ],
  replyComments: [
    {
      id: 21,
      parentId: 11,
      replyToCommentId: null,
      replyToUserId: null,
      replyToNickname: null,
      authorId: 3,
      authorNickname: '丙',
      authorAvatarMediaId: null,
      body: '回复甲的楼中楼',
      praiseCount: 1,
      praised: false,
      createdAt: NOW,
    },
    {
      id: 22,
      parentId: 11,
      replyToCommentId: 21,
      replyToUserId: 3,
      replyToNickname: '丙',
      authorId: 4,
      authorNickname: '丁',
      authorAvatarMediaId: null,
      body: '回复丙的楼中楼',
      praiseCount: 0,
      praised: false,
      createdAt: NOW,
    },
  ],
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  vi.clearAllMocks()
  Element.prototype.scrollIntoView = vi.fn()
  mockedlistComments.mockResolvedValue(COMMENT_DATA)
  mockedPraise.mockResolvedValue({ active: true, count: 4 })
  mockedCreate.mockResolvedValue(undefined)
})

const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  for (const wrapper of mountedWrappers) wrapper.unmount()
  mountedWrappers.length = 0
})

function mountTree(attachTo = false) {
  const wrapper = mount(CommentTree, {
    props: { targetType: 'content' as const, targetId: 1 },
    ...(attachTo ? { attachTo: document.body } : {}),
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('两级渲染', () => {
  it('渲染一级评论与楼中楼（按 parentId 归组）', async () => {
    const wrapper = mountTree()
    await flushPromises()

    expect(wrapper.text()).toContain('3 条评论')
    expect(wrapper.text()).toContain('第一条评论')
    expect(wrapper.text()).toContain('第二条评论')
    expect(wrapper.text()).toContain('回复甲的楼中楼')

    const roots = wrapper.findAll('.lb-comment-root')
    expect(roots).toHaveLength(2)
    // 楼中楼挂在正确的一级下：第一个 root 包含两条回复
    const firstRootReplies = roots[0].findAll('.lb-comment-reply')
    expect(firstRootReplies).toHaveLength(2)
    expect(roots[1].findAll('.lb-comment-reply')).toHaveLength(0)
  })

  it('显示「回复 @昵称」前缀', async () => {
    const wrapper = mountTree()
    await flushPromises()

    expect(wrapper.text()).toContain('回复 @丙')
  })
})

describe('点赞', () => {
  it('未登录：点赞无操作', async () => {
    const wrapper = mountTree()
    await flushPromises()

    const praiseButton = wrapper.findAll('button').find((b) => b.text().includes('👍'))
    expect(praiseButton).toBeDefined()
    await praiseButton?.trigger('click')
    await flushPromises()

    expect(mockedPraise).not.toHaveBeenCalled()
  })

  it('已登录：toggle 调接口并本地更新计数', async () => {
    const wrapper = mountTree()
    await flushPromises()
    const { useSessionStore } = await import('@/stores/session')
    const session = useSessionStore()
    session.$patch({ user: { id: 9, username: 'u' } as unknown as typeof session.user, isSuperAdmin: false, permissions: [] })

    const praiseButton = wrapper.findAll('button').find((b) => b.text().includes('👍'))
    await praiseButton?.trigger('click')
    await flushPromises()

    expect(mockedPraise).toHaveBeenCalledWith(11)
    expect(wrapper.text()).toContain('👍 4')
  })
})

describe('回复定位与提交', () => {
  it('已登录：点回复打开行内框（带目标昵称），提交携带 parent_id', async () => {
    const wrapper = mountTree(true)
    await flushPromises()
    const { useSessionStore } = await import('@/stores/session')
    useSessionStore().$patch({ user: { id: 9, username: 'u' } as unknown as UserDTO, isSuperAdmin: false, permissions: [] })

    const replyButton = wrapper.findAll('button').find((b) => b.text() === '回复')
    await replyButton?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('回复 @甲')
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()

    await wrapper.find('textarea').setValue('我的回复')
    const sendButton = wrapper.findAll('button').find((b) => b.text() === '发送')
    await sendButton?.trigger('click')
    await flushPromises()

    expect(mockedCreate).toHaveBeenCalledTimes(1)
    expect(mockedCreate.mock.calls[0]?.[0]).toMatchObject({ targetType: 'content', targetId: 1, parent_id: 11, body: '我的回复' })
    // 提交成功后重拉评论
    expect(mockedlistComments).toHaveBeenCalledTimes(2)
  })
})
