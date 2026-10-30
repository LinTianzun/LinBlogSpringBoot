import { h, type VNode } from 'vue'
import type { ProseMirrorMark, ProseMirrorNode } from '@/types/api'

/**
 * ProseMirror JSON → VNode 确定性渲染器（§3.2）。
 * 白名单遍历：未知 node/mark/attribute 一律安全跳过（可继续渲染子节点）；
 * 全程 VNode 树、不拼 HTML 字符串 → Vue 自动转义，无需 DOMPurify。
 * link href 只放行 http(s)/mailto/站内相对路径，阻断 javascript: 等伪协议。
 */

const HEADING_LEVELS = new Set([1, 2, 3, 4])

function safeHref(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length === 0) return null
  const trimmed = raw.trim()
  if (/^(https?:\/\/|mailto:|\/)/i.test(trimmed)) return trimmed
  return null
}

function safeImageSrc(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length === 0) return null
  const trimmed = raw.trim()
  // 站内媒体（含 ?w= 变体）或 http(s) 远程图
  if (/^\/(api\/v1\/)?media\//.test(trimmed) || /^https?:\/\//i.test(trimmed)) return trimmed
  return null
}

function applyMarks(node: VNode, marks: ProseMirrorMark[] | undefined): VNode {
  if (!marks || marks.length === 0) return node
  let current = node
  for (const mark of marks) {
    switch (mark.type) {
      case 'bold':
      case 'strong':
        current = h('strong', null, [current])
        break
      case 'italic':
      case 'em':
        current = h('em', null, [current])
        break
      case 'code':
        current = h('code', null, [current])
        break
      case 'link': {
        const href = safeHref(mark.attrs?.href)
        // href 非法则降级为纯文本样式包裹，不渲染 <a>
        current = href
          ? h('a', { href, target: '_blank', rel: 'noopener noreferrer nofollow' }, [current])
          : h('span', { class: 'lb-link-invalid' }, [current])
        break
      }
      case 'underline':
        current = h('u', null, [current])
        break
      case 'strike':
        current = h('s', null, [current])
        break
      default:
        // 未知 mark：跳过该层标记，保留内容
        break
    }
  }
  return current
}

function renderNode(node: ProseMirrorNode): VNode | null {
  switch (node.type) {
    case 'paragraph':
      return h('p', null, renderChildren(node))
    case 'heading': {
      const level = Number(node.attrs?.level ?? 2)
      const tag = HEADING_LEVELS.has(level) ? `h${level}` : 'h2'
      return h(tag, null, renderChildren(node))
    }
    case 'text': {
      if (typeof node.text !== 'string' || node.text.length === 0) return null
      return applyMarks(h('span', null, node.text), node.marks)
    }
    case 'blockquote':
      return h('blockquote', null, renderChildren(node))
    case 'codeBlock': {
      const text = (node.content ?? [])
        .map((child) => (typeof child.text === 'string' ? child.text : ''))
        .join('\n')
      return h('pre', null, [h('code', text)])
    }
    case 'hardBreak':
      return h('br')
    case 'horizontalRule':
      return h('hr')
    case 'image': {
      const src = safeImageSrc(node.attrs?.src)
      if (!src) return null
      return h('img', {
        src,
        alt: typeof node.attrs?.alt === 'string' ? node.attrs.alt : '',
        title: typeof node.attrs?.title === 'string' ? node.attrs.title : undefined,
        loading: 'lazy',
      })
    }
    case 'bulletList':
      return h('ul', null, renderChildren(node))
    case 'orderedList':
      return h('ol', null, renderChildren(node))
    case 'listItem':
      return h('li', null, renderChildren(node))
    default: {
      // 未知 node：不产出标签，但继续下钻子节点，避免内容丢失
      const children = renderChildren(node)
      if (children.length === 0) return null
      return children.length === 1 ? children[0]! : h('span', null, children)
    }
  }
}

function renderChildren(node: ProseMirrorNode): VNode[] {
  return (node.content ?? [])
    .map(renderNode)
    .filter((child): child is VNode => child !== null)
}

/** 渲染整篇文档；空/非法文档返回空数组（由组件决定空态） */
export function renderProseMirror(doc: ProseMirrorNode | null | undefined): VNode[] {
  if (!doc || doc.type !== 'doc') return []
  return renderChildren(doc)
}
