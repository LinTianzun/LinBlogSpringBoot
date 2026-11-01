import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AdminPlaceholder from '@/components/AdminPlaceholder.vue'
import type { PermissionKey } from '@/types/api'

/**
 * AdminPlaceholder：后台里程碑占位壳 — 标题、描述（默认文案兜底）、
 * 所需权限标签逐项渲染。
 */

function mountPlaceholder(props: {
  title: string
  description?: string
  permission: PermissionKey[]
}) {
  return mount(AdminPlaceholder, { props })
}

describe('AdminPlaceholder', () => {
  it('渲染标题与默认空态文案', () => {
    const wrapper = mountPlaceholder({ title: '系统日志', permission: ['systemLog.view'] })
    expect(wrapper.text()).toContain('系统日志')
    expect(wrapper.text()).toContain('功能建设中：本轮仅交付骨架，后续里程碑接入接口。')
  })

  it('自定义 description 覆盖默认文案', () => {
    const wrapper = mountPlaceholder({
      title: '评论管理',
      description: '互动域接口（M3）尚未交付，待后端就绪后接入评论列表与删除。',
      permission: ['comment.viewAll'],
    })
    expect(wrapper.text()).toContain('互动域接口（M3）尚未交付，待后端就绪后接入评论列表与删除。')
    expect(wrapper.text()).not.toContain('功能建设中：本轮仅交付骨架')
  })

  it('逐项渲染所需权限标签', () => {
    const wrapper = mountPlaceholder({
      title: '文章管理',
      permission: ['article.viewAll', 'article.viewOwn'],
    })
    expect(wrapper.text()).toContain('所需权限（任一即可）')
    expect(wrapper.text()).toContain('article.viewAll')
    expect(wrapper.text()).toContain('article.viewOwn')
  })

  it('空权限列表：权限区保留但不渲染任何标签', () => {
    const wrapper = mountPlaceholder({ title: '设置', permission: [] })
    expect(wrapper.text()).toContain('所需权限（任一即可）')
    expect(wrapper.findAll('.n-tag').length).toBe(0)
  })
})
