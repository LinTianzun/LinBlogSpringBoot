<script setup lang="ts">
import { EditorContent, useEditor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import Link from '@tiptap/extension-link'
import { NButton, NSpace, NTooltip } from 'naive-ui'
import { onBeforeUnmount, watch } from 'vue'

/**
 * Tiptap 富文本编辑器（§3.2）：受控 v-model 为 ProseMirror JSON **字符串**，
 * 与后端 ContentView.body（JSON 字符串）直接对齐；编辑器产出的 schema
 * 是 renderContent.ts 白名单的子集，渲染端确定性还原。
 * 本组件仅被后台编辑页路由级懒加载，落在 editor chunk（§8.4 ≤500KB）。
 */
const body = defineModel<string>({ required: true })

const editor = useEditor({
  content: safeParse(body.value),
  extensions: [
    // StarterKit 3.x 内置 link → 关掉，用独立 Link 扩展做 openOnClick:false 配置
    StarterKit.configure({ heading: { levels: [1, 2, 3, 4] }, link: false }),
    Image.configure({ inline: false, allowBase64: false }),
    Link.configure({ openOnClick: false, autolink: true }),
  ],
  editorProps: { attributes: { class: 'lb-rich-editor-prosemirror' } },
  onUpdate: ({ editor: ed }) => {
    body.value = JSON.stringify(ed.getJSON())
  },
})

function safeParse(raw: string): Record<string, unknown> {
  if (!raw || raw.trim().length === 0) return { type: 'doc', content: [] }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && (parsed as { type?: string }).type === 'doc') {
      return parsed as Record<string, unknown>
    }
  } catch {
    /* 非 JSON → 空文档 */
  }
  return { type: 'doc', content: [] }
}

// 外部（载入草稿）回填时同步进编辑器，避免光标/内容错位
watch(body, (value) => {
  const ed = editor.value
  if (!ed) return
  const incoming = JSON.stringify(safeParse(value))
  if (incoming !== JSON.stringify(ed.getJSON())) {
    ed.commands.setContent(safeParse(value), { emitUpdate: false })
  }
})

onBeforeUnmount(() => editor.value?.destroy())

interface ToolItem {
  label: string
  title: string
  active?: boolean
  run: () => void
}

function tools(): ToolItem[] {
  const ed = editor.value
  const chain = () => ed!.chain().focus()
  return [
    { label: 'B', title: '加粗', active: ed?.isActive('bold'), run: () => chain().toggleBold().run() },
    { label: 'I', title: '斜体', active: ed?.isActive('italic'), run: () => chain().toggleItalic().run() },
    { label: 'S', title: '删除线', active: ed?.isActive('strike'), run: () => chain().toggleStrike().run() },
    { label: '</>', title: '行内代码', active: ed?.isActive('code'), run: () => chain().toggleCode().run() },
    { label: 'H2', title: '二级标题', active: ed?.isActive('heading', { level: 2 }), run: () => chain().toggleHeading({ level: 2 }).run() },
    { label: 'H3', title: '三级标题', active: ed?.isActive('heading', { level: 3 }), run: () => chain().toggleHeading({ level: 3 }).run() },
    { label: '“”', title: '引用', active: ed?.isActive('blockquote'), run: () => chain().toggleBlockquote().run() },
    { label: '•–', title: '无序列表', active: ed?.isActive('bulletList'), run: () => chain().toggleBulletList().run() },
    { label: '1.', title: '有序列表', active: ed?.isActive('orderedList'), run: () => chain().toggleOrderedList().run() },
    { label: '{ }', title: '代码块', active: ed?.isActive('codeBlock'), run: () => chain().toggleCodeBlock().run() },
    { label: '—', title: '分隔线', run: () => chain().setHorizontalRule().run() },
    {
      label: '🔗',
      title: '链接',
      active: ed?.isActive('link'),
      run: () => {
        if (!ed) return
        if (ed.isActive('link')) {
          chain().unsetLink().run()
          return
        }
        const url = window.prompt('链接地址（http(s)/mailto/站内路径）', 'https://')
        if (url) chain().setLink({ href: url }).run()
      },
    },
    {
      label: '🖼',
      title: '插入图片',
      run: () => {
        const src = window.prompt('图片地址（/api/v1/media/... 或 https://...）', '/api/v1/media/')
        if (src) chain().setImage({ src }).run()
      },
    },
    { label: '↶', title: '撤销', run: () => chain().undo().run() },
    { label: '↷', title: '重做', run: () => chain().redo().run() },
  ]
}
</script>

<template>
  <div class="lb-rich-editor">
    <div class="lb-rich-editor-toolbar">
      <NSpace :size="4" :wrap="true">
        <NTooltip v-for="tool in tools()" :key="tool.title">
          <template #trigger>
            <NButton
              size="tiny"
              :secondary="!tool.active"
              :type="tool.active ? 'primary' : 'default'"
              :data-active="tool.active || undefined"
              :data-title="tool.title"
              @click="tool.run()"
            >
              {{ tool.label }}
            </NButton>
          </template>
          {{ tool.title }}
        </NTooltip>
      </NSpace>
    </div>
    <EditorContent :editor="editor" class="lb-rich-editor-body" />
  </div>
</template>

<style scoped>
.lb-rich-editor {
  border: 1px solid var(--n-border-color, #e0e0e6);
  border-radius: 6px;
  overflow: hidden;
}

.lb-rich-editor-toolbar {
  padding: 6px 8px;
  border-bottom: 1px solid var(--n-border-color, #e0e0e6);
  background: var(--n-action-color, #fafafc);
}

.lb-rich-editor-body :deep(.lb-rich-editor-prosemirror) {
  min-height: 320px;
  padding: 12px 16px;
  outline: none;
}

.lb-rich-editor-body :deep(p) {
  margin: 0.5em 0;
}

.lb-rich-editor-body :deep(img) {
  max-width: 100%;
}
</style>
