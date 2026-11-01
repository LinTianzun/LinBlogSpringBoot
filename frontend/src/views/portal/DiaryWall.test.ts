import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { listPortalDiaries } from '@/api/portal'
import { ApiError } from '@/api/request'
import { WEATHER_EMOJI } from '@/utils/weather'
import PaginationBar from '@/components/PaginationBar.vue'
import DiaryWall from '@/views/portal/DiaryWall.vue'
import type { DiaryDTO } from '@/types/api'

vi.mock('@/api/portal', () => ({
  listPortalDiaries: vi.fn(),
}))

const mockedList = vi.mocked(listPortalDiaries)

function diary(overrides: Partial<DiaryDTO> = {}): DiaryDTO {
  return {
    id: 1,
    diaryDate: '2026-01-02',
    weather: '晴天',
    mood: '平静',
    body: '{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"今天补了一条日记"}]}]}',
    state: 'published',
    views: 12,
    praises: 34,
    ...overrides,
  }
}

async function mountWall(): Promise<VueWrapper> {
  const wrapper = mount(DiaryWall)
  mountedWrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

const mountedWrappers: VueWrapper[] = []

beforeEach(() => {
  mockedList.mockReset()
})

afterEach(() => {
  mountedWrappers.forEach((wrapper) => wrapper.unmount())
  mountedWrappers.length = 0
})

describe('DiaryWall 日记墙', () => {
  it('首屏加载：以 page=1&pageSize=10 请求并渲染日记卡片', async () => {
    mockedList.mockResolvedValue({ records: [diary()], total: 1 })

    const wrapper = await mountWall()

    expect(mockedList).toHaveBeenCalledWith({ page: 1, pageSize: 10 })
    const html = wrapper.html()
    expect(html).toContain('2026-01-02')
    expect(html).toContain(WEATHER_EMOJI['晴天'])
    expect(html).toContain('心情：平静')
    expect(html).toContain('今天补了一条日记')
    expect(html).toContain('34')
  })

  it('纯文本 body（非 ProseMirror JSON）兜底渲染为段落文本', async () => {
    mockedList.mockResolvedValue({ records: [diary({ body: '就是一段纯文本' })], total: 1 })

    const wrapper = await mountWall()

    expect(wrapper.html()).toContain('就是一段纯文本')
  })

  it('接口失败：显示错误提示且不渲染空态', async () => {
    mockedList.mockRejectedValue(new ApiError(50000, '日记服务暂不可用'))

    const wrapper = await mountWall()

    expect(wrapper.html()).toContain('日记服务暂不可用')
    expect(wrapper.find('.n-empty').exists()).toBe(false)
    expect(wrapper.find('.n-alert').exists()).toBe(true)
  })

  it('空数据：显示"还没有日记"空态', async () => {
    mockedList.mockResolvedValue({ records: [], total: 0 })

    const wrapper = await mountWall()

    expect(wrapper.html()).toContain('还没有日记')
    expect(wrapper.find('.n-empty').exists()).toBe(true)
  })

  it('翻页：PaginationBar 触发 update:page 后以新页码重新请求', async () => {
    mockedList.mockResolvedValue({ records: [diary()], total: 35 })

    const wrapper = await mountWall()
    expect(wrapper.findComponent(PaginationBar).exists()).toBe(true)

    wrapper.findComponent(PaginationBar).vm.$emit('update:page', 2)
    await flushPromises()

    expect(mockedList).toHaveBeenLastCalledWith({ page: 2, pageSize: 10 })
  })
})
