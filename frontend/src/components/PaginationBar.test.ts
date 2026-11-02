import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { ElPagination } from 'element-plus'
import PaginationBar from '@/components/PaginationBar.vue'

/**
 * PaginationBar 统一封装：单页不渲染、多页渲染 el-pagination、
 * 翻页透传 update:page、disabled 透传（§5 通用组件约定）。
 */

function mountBar(props: { page: number; total: number; pageSize?: number; disabled?: boolean }) {
  return mount(PaginationBar, { props: { pageSize: 10, ...props } })
}

describe('渲染条件', () => {
  it('total 不足一页：不渲染分页器', () => {
    const wrapper = mountBar({ page: 1, total: 10 })
    expect(wrapper.find('.lb-pagination').exists()).toBe(false)
  })

  it('total=0：按单页处理，不渲染', () => {
    const wrapper = mountBar({ page: 1, total: 0 })
    expect(wrapper.find('.lb-pagination').exists()).toBe(false)
  })

  it('多页：渲染 .lb-pagination 与 el-pagination，pageCount 向上取整', () => {
    const wrapper = mountBar({ page: 2, total: 35 })
    expect(wrapper.find('.lb-pagination').exists()).toBe(true)
    const pagination = wrapper.findComponent(ElPagination)
    expect(pagination.exists()).toBe(true)
    expect(pagination.props('pageCount')).toBe(4)
  })

  it('pageSize 非法（0）兜底为 1，按 total 逐页计', () => {
    const wrapper = mountBar({ page: 1, total: 3, pageSize: 0 })
    expect(wrapper.findComponent(ElPagination).props('pageCount')).toBe(3)
  })
})

describe('props 透传', () => {
  it('当前页与 disabled 透传给 el-pagination', () => {
    const wrapper = mountBar({ page: 3, total: 50, disabled: true })
    const pagination = wrapper.findComponent(ElPagination)
    expect(pagination.props('currentPage')).toBe(3)
    expect(pagination.props('disabled')).toBe(true)
  })

  it('disabled 缺省为 false', () => {
    const wrapper = mountBar({ page: 1, total: 50 })
    expect(wrapper.findComponent(ElPagination).props('disabled')).toBe(false)
  })
})

describe('翻页事件', () => {
  it('el-pagination 触发 update:current-page：原样透传给父级', async () => {
    const wrapper = mountBar({ page: 1, total: 50 })
    wrapper.findComponent(ElPagination).vm.$emit('update:current-page', 2)
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('update:page')).toEqual([[2]])
  })
})
