import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ContentRenderer from '@/components/ContentRenderer.vue'
import type { TiptapDoc } from '@/types/api'

/**
 * ContentRenderer 组件级补充（renderContent.ts 的纯函数细节已由
 * renderContent.test.ts 覆盖）：这里验证 props 装配 — 空文档兜底、
 * mark 全集、链接协议白名单补充与图片属性透传。
 */

function html(doc: TiptapDoc | null | undefined): string {
  return mount(ContentRenderer, { props: { doc } }).html()
}

describe('空文档兜底', () => {
  it('null：不渲染 .lb-content', () => {
    expect(html(null)).not.toContain('lb-content')
  })

  it('undefined：不渲染 .lb-content', () => {
    expect(html(undefined)).not.toContain('lb-content')
  })

  it('content 为空数组：不渲染 .lb-content', () => {
    expect(html({ type: 'doc', content: [] })).not.toContain('lb-content')
  })
})

describe('mark 全集', () => {
  it('underline/strike/italic/code 各渲染对应标签', () => {
    const doc: TiptapDoc = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: '下', marks: [{ type: 'underline' }] },
            { type: 'text', text: '删', marks: [{ type: 'strike' }] },
            { type: 'text', text: '斜', marks: [{ type: 'italic' }] },
            { type: 'text', text: '码', marks: [{ type: 'code' }] },
          ],
        },
      ],
    }
    const rendered = html(doc)
    // 渲染器对 text 节点统一 <span> 包裹（round-2 既定策略），mark 标签包在 span 外层
    expect(rendered).toContain('<u><span>下</span></u>')
    expect(rendered).toContain('<s><span>删</span></s>')
    expect(rendered).toContain('<em><span>斜</span></em>')
    expect(rendered).toContain('<code><span>码</span></code>')
  })
})

describe('链接协议白名单补充', () => {
  it('mailto: 与站内相对路径放行为 <a>', () => {
    const doc: TiptapDoc = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: '邮', marks: [{ type: 'link', attrs: { href: 'mailto:a@b.com' } }] },
            { type: 'text', text: '站', marks: [{ type: 'link', attrs: { href: '/articles?page=2' } }] },
          ],
        },
      ],
    }
    const rendered = html(doc)
    expect(rendered).toContain('<a href="mailto:a@b.com"')
    expect(rendered).toContain('<a href="/articles?page=2"')
    expect(rendered).not.toContain('lb-link-invalid')
  })

  it('非法相对地址（无协议且不以 / 开头）降级为 span.lb-link-invalid', () => {
    const doc: TiptapDoc = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [{ type: 'text', text: '坏链', marks: [{ type: 'link', attrs: { href: 'javascript:alert(1)' } }] }],
        },
      ],
    }
    const rendered = html(doc)
    expect(rendered).toContain('lb-link-invalid')
    expect(rendered).not.toContain('<a ')
  })
})

describe('图片属性', () => {
  it('alt/title 透传且 loading=lazy', () => {
    const doc: TiptapDoc = {
      type: 'doc',
      content: [
        {
          type: 'image',
          attrs: { src: '/api/v1/media/abc', alt: '一张图', title: '标题' },
        },
      ],
    }
    const rendered = html(doc)
    expect(rendered).toContain('alt="一张图"')
    expect(rendered).toContain('title="标题"')
    expect(rendered).toContain('loading="lazy"')
  })
})
