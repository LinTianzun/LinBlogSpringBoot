<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, NInputNumber, NSwitch, useMessage } from 'naive-ui'
import { createContent, getAdminContent, updateContent } from '@/api/admin'
import { ApiError } from '@/api/request'
import type { CreateContentPayload, FieldError, TiptapDoc } from '@/types/api'

/**
 * 文章编辑（§5.5）：/admin/articles/edit/:id?。
 * 有 :id → 加载后编辑；无 → 新建。正文为 Tiptap（ProseMirror JSON），
 * 本轮以 JSON 文本域编辑，富文本编辑器后续里程碑接入。
 */

const route = useRoute()
const router = useRouter()
const message = useMessage()

const contentId = computed<number | null>(() => {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(id) && id > 0 ? id : null
})

const title = ref('')
const introduce = ref('')
const coverMediaId = ref<number | null>(null)
const isPinned = ref(false)
const bodyText = ref(JSON.stringify({ type: 'doc', content: [] }, null, 2))
const saving = ref(false)
const loading = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const bodyError = ref('')

function applyFieldErrors(errors: FieldError[]): void {
  const map: Record<string, string> = {}
  for (const item of errors) map[item.field] = item.message
  fieldErrors.value = map
}

function parseBody(): TiptapDoc | null {
  try {
    const doc = JSON.parse(bodyText.value) as TiptapDoc
    if (doc && typeof doc === 'object' && doc.type === 'doc' && Array.isArray(doc.content)) return doc
    bodyError.value = 'JSON 结构须为 { type: "doc", content: [...] }'
    return null
  } catch {
    bodyError.value = '正文不是合法 JSON'
    return null
  }
}

async function load(): Promise<void> {
  if (contentId.value === null) return
  loading.value = true
  try {
    const detail = await getAdminContent(contentId.value)
    title.value = detail.title
    introduce.value = detail.introduce ?? ''
    coverMediaId.value = detail.coverMediaId ?? null
    isPinned.value = detail.isPinned ?? false
    bodyText.value = JSON.stringify(detail.body, null, 2)
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  fieldErrors.value = {}
  bodyError.value = ''
  const doc = parseBody()
  if (!doc) return
  if (!title.value.trim()) {
    fieldErrors.value = { title: '标题不能为空' }
    return
  }

  saving.value = true
  try {
    const payload: CreateContentPayload = {
      contentType: 'article',
      title: title.value.trim(),
      introduce: introduce.value.trim() || undefined,
      coverMediaId: coverMediaId.value ?? undefined,
      isPinned: isPinned.value,
      body: doc,
    }
    if (contentId.value === null) {
      await createContent(payload)
      message.success('文章已创建')
    } else {
      await updateContent(contentId.value, payload)
      message.success('文章已保存')
    }
    await router.push('/admin/articles')
  } catch (error) {
    if (error instanceof ApiError && error.code === 40001 && error.fieldErrors) {
      applyFieldErrors(error.fieldErrors)
    } else {
      message.error(error instanceof ApiError ? error.message : '保存失败')
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <n-page-header :title="contentId === null ? '新建文章' : '编辑文章'" @back="router.back()" />
    <n-spin :show="loading">
      <n-card style="margin-top: 16px">
        <n-form label-placement="top">
          <n-form-item label="标题" :validation-status="fieldErrors.title ? 'error' : undefined" :feedback="fieldErrors.title">
            <n-input v-model:value="title" placeholder="文章标题" maxlength="200" show-count />
          </n-form-item>
          <n-form-item label="摘要" :validation-status="fieldErrors.introduce ? 'error' : undefined" :feedback="fieldErrors.introduce">
            <n-input
              v-model:value="introduce"
              type="textarea"
              placeholder="显示在列表页的摘要（可空）"
              :autosize="{ minRows: 2, maxRows: 4 }"
              maxlength="500"
            />
          </n-form-item>
          <n-form-item label="封面媒体 ID（可空）" :validation-status="fieldErrors.coverMediaId ? 'error' : undefined" :feedback="fieldErrors.coverMediaId">
            <n-input-number v-model:value="coverMediaId" :min="1" placeholder="mediaId" style="width: 220px" />
          </n-form-item>
          <n-form-item label="置顶">
            <n-switch v-model:value="isPinned" />
          </n-form-item>
          <n-form-item
            label="正文（Tiptap JSON）"
            :validation-status="bodyError || fieldErrors.body ? 'error' : undefined"
            :feedback="bodyError || fieldErrors.body"
          >
            <n-input
              v-model:value="bodyText"
              type="textarea"
              :autosize="{ minRows: 12, maxRows: 28 }"
              placeholder='{"type":"doc","content":[...]}'
            />
          </n-form-item>
          <n-space>
            <n-button type="primary" :loading="saving" @click="save">保存</n-button>
            <n-button @click="router.push('/admin/articles')">返回列表</n-button>
          </n-space>
        </n-form>
      </n-card>
    </n-spin>
  </div>
</template>
