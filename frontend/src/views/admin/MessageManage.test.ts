import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import {
  createConversation,
  getUnreadCount,
  listConversations,
  listMessages,
  markConversationRead,
  sendMessage,
} from '@/api/admin'
import { ApiError } from '@/api/request'
import MessageManage from '@/views/admin/MessageManage.vue'
import type { ConversationDTO, MessageDTO } from '@/types/api'

vi.mock('@/api/admin', () => ({
  listConversations: vi.fn(),
  listMessages: vi.fn(),
  markConversationRead: vi.fn(),
  sendMessage: vi.fn(),
  createConversation: vi.fn(),
  getUnreadCount: vi.fn(),
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: Object.assign(vi.fn(), { success: vi.fn(), warning: vi.fn(), error: vi.fn() }),
  }
})

const mockedListConversations = vi.mocked(listConversations)
const mockedListMessages = vi.mocked(listMessages)
const mockedMarkRead = vi.mocked(markConversationRead)
const mockedSend = vi.mocked(sendMessage)
const mockedCreate = vi.mocked(createConversation)
const mockedUnread = vi.mocked(getUnreadCount)

function conversation(overrides: Partial<ConversationDTO> = {}): ConversationDTO {
  return {
    id: 1,
    peer: { id: 2, username: 'alice', nickname: '爱丽丝' },
    lastMessageAt: '2026-01-02T10:00:00',
    lastMessagePreview: '你好，站长',
    unread: 2,
    ...overrides,
  }
}

function message(overrides: Partial<MessageDTO> = {}): MessageDTO {
  return {
    id: 10,
    conversationId: 1,
    senderId: 2,
    body: '在吗？',
    readAt: null,
    createdAt: '2026-01-02T10:00:00',
    ...overrides,
  }
}

const mountedWrappers: VueWrapper[] = []

async function mountPage(): Promise<VueWrapper> {
  const wrapper = mount(MessageManage)
  mountedWrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(async () => {
  setActivePinia(createPinia())
  const { useSessionStore } = await import('@/stores/session')
  const session = useSessionStore()
  session.$patch({ user: { id: 9, username: 'boss' } as unknown as typeof session.user, isSuperAdmin: true, permissions: [] })

  mockedListConversations.mockReset()
  mockedListMessages.mockReset()
  mockedMarkRead.mockReset()
  mockedSend.mockReset()
  mockedCreate.mockReset()
  mockedUnread.mockReset()

  mockedMarkRead.mockResolvedValue(undefined)
  mockedUnread.mockResolvedValue(0)
})

afterEach(() => {
  mountedWrappers.forEach((wrapper) => wrapper.unmount())
  mountedWrappers.length = 0
})

describe('MessageManage 私信管理', () => {
  it('首屏加载：请求会话列表（pageSize=50）与未读数并渲染会话', async () => {
    mockedListConversations.mockResolvedValue({ records: [conversation()], total: 1 })
    mockedUnread.mockResolvedValue(2)

    const wrapper = await mountPage()

    expect(mockedListConversations).toHaveBeenCalledWith({ page: 1, pageSize: 50 })
    expect(mockedUnread).toHaveBeenCalled()
    const html = wrapper.html()
    expect(html).toContain('爱丽丝')
    expect(html).toContain('你好，站长')
    expect(html).toContain('未读 2 条')
  })

  it('打开会话：首屏自动展开首个会话（拉取消息正序渲染、标记已读并清零未读），点击可切换', async () => {
    mockedListConversations.mockResolvedValue({
      records: [
        conversation(),
        conversation({ id: 5, peer: { id: 3, username: 'bob', nickname: '鲍勃' }, unread: 0 }),
      ],
      total: 2,
    })
    mockedUnread.mockResolvedValueOnce(2).mockResolvedValue(0)
    mockedListMessages.mockResolvedValue({
      records: [
        message({ id: 10, senderId: 2, body: '在吗？' }),
        message({ id: 11, senderId: 9, body: '在的，什么事？' }),
      ],
      hasMore: false,
    })

    const wrapper = await mountPage()

    // 加载会话列表后自动展开最近一条
    expect(mockedListMessages).toHaveBeenCalledWith(1)
    expect(mockedMarkRead).toHaveBeenCalledWith(1)
    await flushPromises()
    expect(wrapper.html()).not.toContain('未读 2 条')

    const selfBubbles = wrapper.findAll('.lb-msg-self .lb-msg-bubble')
    const otherBubbles = wrapper.findAll('.lb-msg-other .lb-msg-bubble')
    expect(selfBubbles).toHaveLength(1)
    expect(selfBubbles[0].text()).toBe('在的，什么事？')
    expect(otherBubbles[0].text()).toBe('在吗？')

    // 点击另一个会话切换
    await wrapper.find('[data-testid="message-conversation"]').trigger('click')
    await flushPromises()

    expect(mockedListMessages).toHaveBeenLastCalledWith(5)
    expect(wrapper.find('[data-testid="message-conversation-active"]').exists()).toBe(true)
  })

  it('游标加载更早消息：以当前首条消息 id 作为 before 前插', async () => {
    mockedListConversations.mockResolvedValue({ records: [conversation()], total: 1 })
    mockedListMessages
      .mockResolvedValueOnce({
        records: [message({ id: 30, body: '最早一条' }), message({ id: 31, body: '后面一条' })],
        hasMore: true,
      })
      .mockResolvedValueOnce({
        records: [message({ id: 28, body: '更早消息' })],
        hasMore: false,
      })

    const wrapper = await mountPage()

    await wrapper.find('[data-testid="message-earlier"]').trigger('click')
    await flushPromises()

    expect(mockedListMessages).toHaveBeenLastCalledWith(1, 30)
    const bubbles = wrapper.findAll('.lb-msg-bubble')
    expect(bubbles).toHaveLength(3)
    expect(bubbles[0].text()).toBe('更早消息')
    expect(bubbles[2].text()).toBe('后面一条')
  })

  it('发送消息：POST {body} 后追加气泡并清空输入框', async () => {
    mockedListConversations.mockResolvedValue({ records: [conversation()], total: 1 })
    mockedListMessages.mockResolvedValue({ records: [message({ id: 10, body: '在吗？' })], hasMore: false })
    mockedSend.mockResolvedValue(message({ id: 12, senderId: 9, body: '在的' }))

    const wrapper = await mountPage()

    await wrapper.find('[data-testid="message-draft"]').setValue('在的')
    await wrapper.find('[data-testid="message-send"]').trigger('click')
    await flushPromises()

    expect(mockedSend).toHaveBeenCalledWith(1, { body: '在的' })
    expect(wrapper.html()).toContain('在的')
    expect((wrapper.find('[data-testid="message-draft"]').element as HTMLTextAreaElement).value).toBe('')
  })

  it('会话列表加载失败：显示错误提示与重试', async () => {
    mockedListConversations.mockRejectedValue(new ApiError(50000, '私信服务暂不可用'))

    const wrapper = await mountPage()

    const alert = wrapper.find('.el-alert')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('私信服务暂不可用')
  })

  it('发起会话：提交 {recipientId, body} 后刷新列表并选中新会话', async () => {
    mockedListConversations
      .mockResolvedValueOnce({ records: [], total: 0 })
      .mockResolvedValue({ records: [conversation({ id: 7, unread: 0 })], total: 1 })
    mockedCreate.mockResolvedValue(message({ id: 1, conversationId: 7, body: '您好' }))
    mockedListMessages.mockResolvedValue({ records: [], hasMore: false })

    const wrapper = await mountPage()

    await wrapper.find('[data-testid="message-compose"]').trigger('click')
    // dialog 已 :teleported="false" 留在组件树内；el-input-number 的 data-testid 落在根 div，
    // 原生 input 是其后代；el-input/textarea 则 data-testid 直接落在原生控件上
    const recipientInput = wrapper.find('[data-testid="compose-recipient"] input')
    await recipientInput.setValue('2')
    await recipientInput.trigger('change')
    await wrapper.find('[data-testid="compose-body"]').setValue('您好')
    await wrapper.find('[data-testid="compose-submit"]').trigger('click')
    await flushPromises()

    expect(mockedCreate).toHaveBeenCalledWith({ recipientId: 2, body: '您好' })
    expect(mockedListMessages).toHaveBeenCalledWith(7)
    expect(wrapper.find('[data-testid="message-conversation-active"]').exists()).toBe(true)
  })
})
