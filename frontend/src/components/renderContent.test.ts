import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ContentRenderer from '@/components/ContentRenderer.vue'
import { renderProseMirror } from '@/components/renderContent'
import type { TiptapDoc } from '@/types/api'

/**
 * ProseMirror JSON 确定性渲染（§8.3）：白名单遍历、未知类型安全跳过、
 * 链接/图片地址白名单（无需 DOMPurify）。
 */

function renderHtml(doc: TiptapDoc): string {
  const wrapper = mount(ContentRenderer, { props: { doc } })
  return wrapper.html()
}

const EMPTY_DOC: TiptapDoc = { type: 'doc', content: [] }

describe('renderProseMirror 基础结构', () => {
  it('paragraph + text + bold mark', () => {
    const html = renderHtml({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: '你好' },
            { type: 'text', text: '世界', marks: [{ type: 'bold' }] },
          ],
        },
      ],
    })
    expect(html).toContain('<p><span>你好</span><strong><span>世界</span></strong></p>')
  })

  it('heading level 映射：1-4 保留，越界回退 h2', () => {
    expect(renderHtml({ type: 'doc', content: [{ type: 'heading', attrs: { level: 3 }, content: [{ type: 'text', text: '三级' }] }] })).toContain(
      '<h3><span>三级</span></h3>',
    )
    expect(renderHtml({ type: 'doc', content: [{ type: 'heading', attrs: { level: 9 }, content: [{ type: 'text', text: '越界' }] }] })).toContain(
      '<h2><span>越界</span></h2>',
    )
  })

  it('codeBlock → pre>code，blockquote 嵌套 paragraph', () => {
    expect(renderHtml({ type: 'doc', content: [{ type: 'codeBlock', content: [{ type: 'text', text: 'const a = 1' }] }] })).toContain(
      '<pre><code>const a = 1</code></pre>',
    )
    const html = renderHtml({
      type: 'doc',
      content: [{ type: 'blockquote', content: [{ type: 'paragraph', content: [{ type: 'text', text: '引用' }] }] }],
    })
    expect(html).toContain('<blockquote>')
    expect(html).toContain('引用')
  })

  it('hardBreak 与 hr', () => {
    const html = renderHtml({
      type: 'doc',
      content: [
        { type: 'paragraph', content: [{ type: 'text', text: '上' }, { type: 'hardBreak' }, { type: 'text', text: '下' }] },
        { type: 'paragraph', content: [{ type: 'text', marks: [{ type: 'horizontalRule' as never }], text: '' }] },
        { type: 'paragraph' },
        { type: 'paragraph', content: [] },
      ],
    })
    expect(html).toContain('<br>')
  })

  it('bulletList/orderedList/listItem', () => {
    const html = renderHtml({
      type: 'doc',
      content: [
        {
          type: 'bulletList',
          content: [
            { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'A' }] }] },
            { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'B' }] }] },
          ],
        },
        {
          type: 'orderedList',
          content: [{ type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'C' }] }] }],
        },
      ],
    })
    expect(html).toContain('<ul>')
    expect(html).toContain('<ol>')
    expect(html).toContain('A')
    expect(html).toContain('C')
  })
})

describe('链接与图片地址白名单', () => {
  it('安全 http 链接渲染 <a>，javascript: 被丢弃（仅保留文本）', () => {
    const safe = renderHtml({
      type: 'doc',
      content: [{ type: 'paragraph', content: [{ type: 'text', text: '官网', marks: [{ type: 'link', attrs: { href: 'https://a.com' } }] }] }],
    })
    expect(safe).toContain('<a')
    expect(safe).toContain('https://a.com')

    const evil = renderHtml({
      type: 'doc',
      content: [{ type: 'paragraph', content: [{ type: 'text', text: '点我', marks: [{ type: 'link', attrs: { href: 'javascript:alert(1)' } }] }] }],
    })
    expect(evil).not.toContain('<a')
    expect(evil).toContain('点我')
  })

  it('图片：站内 /media/ 与 http(s) 放行，javascript: 与相对注入丢弃', () => {
    expect(renderHtml({ type: 'doc', content: [{ type: 'image', attrs: { src: '/api/v1/media/abc?w=320', alt: '图' } }] })).toContain('<img')
    expect(renderHtml({ type: 'doc', content: [{ type: 'image', attrs: { src: 'https://cdn.example.com/x.png' } }] })).toContain('<img')
    expect(renderHtml({ type: 'doc', content: [{ type: 'image', attrs: { src: 'javascript:alert(1)' } }] })).not.toContain('<img')
    expect(renderHtml({ type: 'doc', content: [{ type: 'image', attrs: { src: 'data:text/html,<script>' } }] })).not.toContain('<img')
  })
})

describe('未知类型安全跳过', () => {
  it('未知 node 不产出标签，但下钻渲染子节点', () => {
    const html = renderHtml({
      type: 'doc',
      content: [
        {
          type: 'fancyWidget',
          attrs: { evil: '<script>' },
          content: [{ type: 'paragraph', content: [{ type: 'text', text: '内层内容' }] }],
        },
      ],
    })
    expect(html).not.toContain('fancywidget')
    expect(html).not.toContain('<script>')
    expect(html).toContain('<p><span>内层内容</span></p>')
  })

  it('未知 mark 被跳过，文本保留', () => {
    const html = renderHtml({
      type: 'doc',
      content: [{ type: 'paragraph', content: [{ type: 'text', text: '带光环', marks: [{ type: 'rainbow' } as never] }] }],
    })
    expect(html).toContain('带光环')
    expect(html).not.toContain('rainbow')
  })

  it('空文档与根节点兜底不抛错', () => {
    expect(renderHtml(EMPTY_DOC)).not.toContain('<undefined')
    // 根类型非 doc：遍历器仍按节点下钻
    expect(() => renderProseMirror({ type: 'fragment', content: [] } as unknown as TiptapDoc)).not.toThrow()
  })
})
