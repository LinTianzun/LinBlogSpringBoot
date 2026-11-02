import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { listSystemLogs } from '@/api/admin'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import LogView from '@/views/admin/LogView.vue'
import type { SystemLogViewDTO } from '@/types/api'

vi.mock('@/api/admin', () => ({
  listSystemLogs: vi.fn(),
}))

const mockedList = vi.mocked(listSystemLogs)

function logRow(overrides: Partial<SystemLogViewDTO> = {}): SystemLogViewDTO {
  return {
    id: 1,
    userId: 9,
    userName: 'boss',
    module: 'content',
    action: 'trash',
    targetType: 'article',
    targetId: 3,
    detail: '标题《测试文章》',
    ip: '127.0.0.1',
    createdAt: '2026-01-02T10:30:00',
    ...overrides,
  }
}

const mountedWrappers: VueWrapper[] = []

async function mountPage(): Promise<VueWrapper> {
  const wrapper = mount(LogView)
  mountedWrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mockedList.mockReset()
})

afterEach(() => {
  mountedWrappers.forEach((wrapper) => wrapper.unmount())
  mountedWrappers.length = 0
})

describe('LogView 系统日志', () => {
  it('首屏加载：page=1&pageSize=20 请求并渲染日志行', async () => {
    mockedList.mockResolvedValue({
      records: [
        logRow(),
        logRow({ id: 2, module: 'mail', action: 'send_failed' }),
      ],
      total: 41,
    })

    const wrapper = await mountPage()

    expect(mockedList).toHaveBeenCalledWith({ page: 1, pageSize: 20 })
    const html = wrapper.html()
    expect(html).toContain('boss')
    expect(html).toContain('trash')
    expect(html).toContain('article #3')
    expect(html).toContain('标题《测试文章》')
    expect(html).toContain('2026')
    expect(html).toContain('系统')
    expect(html).toContain('mail')
    expect(wrapper.findAll('.el-table__row')).toHaveLength(2)
  })

  it('条件过滤：module 下拉与 action 输入随查询提交，page 重置为 1', async () => {
    mockedList.mockResolvedValue({ records: [logRow()], total: 1 })

    const wrapper = await mountPage()

    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'content')
    await flushPromises()
    // element-plus el-input inheritAttrs:false，data-testid 落在原生 input 自身
    await wrapper.find('[data-testid="log-action"]').setValue('trash')
    await wrapper.find('[data-testid="log-search"]').trigger('click')
    await flushPromises()

    expect(mockedList).toHaveBeenLastCalledWith({ page: 1, pageSize: 20, module: 'content', action: 'trash' })
  })

  it('分页：翻页保持过滤条件', async () => {
    mockedList.mockResolvedValue({ records: [logRow()], total: 41 })

    const wrapper = await mountPage()
    expect(wrapper.findComponent(PaginationBar).exists()).toBe(true)

    wrapper.findComponent(PaginationBar).vm.$emit('update:page', 2)
    await flushPromises()

    expect(mockedList).toHaveBeenLastCalledWith({ page: 2, pageSize: 20 })
  })

  it('重置：清空过滤条件并回到第一页', async () => {
    mockedList.mockResolvedValue({ records: [logRow()], total: 41 })

    const wrapper = await mountPage()
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'user')
    await flushPromises()
    await wrapper.find('[data-testid="log-search"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="log-reset"]').trigger('click')
    await flushPromises()

    expect(mockedList).toHaveBeenLastCalledWith({ page: 1, pageSize: 20 })
  })

  it('加载失败：显示错误提示，表格清空', async () => {
    mockedList.mockRejectedValue(new ApiError(40300, '无权访问'))

    const wrapper = await mountPage()

    const alert = wrapper.find('.el-alert')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('无权访问')
    expect(wrapper.find('.el-table__row').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无日志')
  })
})
