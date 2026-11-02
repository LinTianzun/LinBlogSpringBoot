import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { getSettings, updateSettings } from '@/api/admin'
import { ApiError } from '@/api/request'
import { ElMessage } from 'element-plus'
import SettingManage from '@/views/admin/SettingManage.vue'
import type { SettingsDTO } from '@/types/api'

vi.mock('@/api/admin', () => ({
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: Object.assign(vi.fn(), { success: vi.fn(), warning: vi.fn(), error: vi.fn() }),
  }
})

const mockedGet = vi.mocked(getSettings)
const mockedUpdate = vi.mocked(updateSettings)
const mockedSuccess = vi.mocked(ElMessage.success)

const mountedWrappers: VueWrapper[] = []

const SETTINGS_KEYS = [
  'site_title',
  'site_description',
  'site_keywords',
  'icp',
  'footer_text',
  'announcement',
] as const

async function mountPage(): Promise<VueWrapper> {
  const wrapper = mount(SettingManage)
  mountedWrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mockedGet.mockReset()
  mockedUpdate.mockReset()
  mockedSuccess.mockClear()
  vi.mocked(ElMessage.error).mockClear()
  mockedUpdate.mockResolvedValue(undefined)
})

afterEach(() => {
  mountedWrappers.forEach((wrapper) => wrapper.unmount())
  mountedWrappers.length = 0
})

describe('SettingManage 系统设置', () => {
  it('加载回填：GET 返回值写入表单（缺失键回退空串）', async () => {
    mockedGet.mockResolvedValue({
      site_title: '林间博客',
      site_keywords: '博客,随笔',
      announcement: '欢迎来到小站',
    } as SettingsDTO)

    const wrapper = await mountPage()

    expect(mockedGet).toHaveBeenCalled()
    // element-plus el-input inheritAttrs:false，data-testid 落在原生 input 自身
    const titleInput = wrapper.find('[data-testid="setting-site_title"]')
    const keywordsInput = wrapper.find('[data-testid="setting-site_keywords"]')
    const icpInput = wrapper.find('[data-testid="setting-icp"]')
    expect((titleInput.element as HTMLInputElement).value).toBe('林间博客')
    expect((keywordsInput.element as HTMLInputElement).value).toBe('博客,随笔')
    expect((icpInput.element as HTMLInputElement).value).toBe('')
    // announcement 是 textarea，value 不会出现在 html() 里
    const announcementInput = wrapper.find('[data-testid="setting-announcement"]')
    expect((announcementInput.element as HTMLTextAreaElement).value).toBe('欢迎来到小站')
  })

  it('保存：提交全部 6 个白名单键（含修改值），成功后提示并重载', async () => {
    mockedGet.mockResolvedValue({
      site_title: '旧标题',
      site_description: '',
      site_keywords: '',
      icp: '',
      footer_text: '',
      announcement: '',
    } as SettingsDTO)

    const wrapper = await mountPage()
    await wrapper.find('[data-testid="setting-site_title"]').setValue('新标题')
    await wrapper.find('[data-testid="setting-save"]').trigger('click')
    await flushPromises()

    expect(mockedUpdate).toHaveBeenCalledTimes(1)
    const payload = mockedUpdate.mock.calls[0][0]
    expect(Object.keys(payload).sort()).toEqual([...SETTINGS_KEYS].sort())
    expect(payload.site_title).toBe('新标题')
    expect(mockedSuccess).toHaveBeenCalledWith('设置已保存')
    expect(mockedGet).toHaveBeenCalledTimes(2)
  })

  it('保存失败：后端限长报错透出到错误提示', async () => {
    mockedGet.mockResolvedValue({
      site_title: '',
      site_description: '',
      site_keywords: '',
      icp: '',
      footer_text: '',
      announcement: '',
    } as SettingsDTO)
    mockedUpdate.mockRejectedValue(new ApiError(40000, '设置项 site_title 超过长度上限 50'))

    const wrapper = await mountPage()
    await wrapper.find('[data-testid="setting-save"]').trigger('click')
    await flushPromises()

    const alert = wrapper.find('.el-alert')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('设置项 site_title 超过长度上限 50')
    expect(vi.mocked(ElMessage.error)).toHaveBeenCalled()
    expect(mockedSuccess).not.toHaveBeenCalled()
  })

  it('加载失败：显示错误提示与重试按钮', async () => {
    mockedGet.mockRejectedValue(new ApiError(40300, '无权访问'))

    const wrapper = await mountPage()

    const alert = wrapper.find('.el-alert')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('无权访问')
  })
})
