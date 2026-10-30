<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, NInputNumber, NSwitch, useMessage } from 'naive-ui'
import { createContent, getAdminContent, updateContent } from '@/api/admin'
import { ApiError } from '@/api/request'
import type { CreateContentPayload, FieldError, TiptapDoc } from '@/types/api'

/**
 * 画廊编辑（§5.5）：/admin/gallery/edit/:id?。
 * 图片内容以 coverMediaId（对应 /media/{storageKey}）承载，正文为可选说明 JSON。
 * 媒体上传接口（M4）未交付，本轮手填 mediaId。
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

const EMPTY_DOC: TiptapDoc = { type: 'doc', content: [] }

function applyFieldErrors(errors: FieldError[]): void {
  const map: Record<string, string> = {}
  for (const item of errors) map[item.field] = item.message
  fieldErrors.value = map
}

function parseBody(): TiptapDoc {
  if (!bodyText.value.trim()) return EMPTY_DOC
  try {
    const doc = JSON.parse(bodyText.value) as TiptapDoc
    if (doc && typeof doc === 'object' && doc.type === 'doc' && Array.isArray(doc.content)) {
      bodyError.value = ''
      return doc
    }
    bodyError.value = 'JSON 结构须为 { type: "doc", content: [...] }'
    return EMPTY_DOC
  } catch {
    bodyError.value = '正文不是合法 JSON'
    return EMPTY_DOC
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
    bodyText.value = detail.body ? JSON.stringify(detail.body, null, 2) : ''
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  fieldErrors.value = {}
  bodyError.value = ''
  if (!title.value.trim()) {
    fieldErrors.value = { title: '标题不能为空' }
    return
  }
  if (coverMediaId.value === null) {
    fieldErrors.value = { coverMediaId: '请填写封面媒体 ID' }
    return
  }

  saving.value = true
  try {
    const payload: CreateContentPayload = {
      contentType: 'image',
      title: title.value.trim(),
      introduce: introduce.value.trim() || undefined,
      coverMediaId: coverMediaId.value,
      isPinned: isPinned.value,
      body: parseBody(),
    }
    if (contentId.value === null) {
      await createContent(payload)
      message.success('图片已创建')
    } else {
      await updateContent(contentId.value, payload)
      message.success('图片已保存')
    }
    await router.push('/admin/gallery')
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
    <n-page-header :title="contentId === null ? '新建图片' : '编辑图片'" @back="router.back()" />
    <n-spin :show="loading">
      <n-card style="margin-top: 16px">
        <n-form label-placement="top">
          <n-form-item label="标题" :validation-status="fieldErrors.title ? 'error' : undefined" :feedback="fieldErrors.title">
            <n-input v-model:value="title" placeholder="图片标题" maxlength="200" show-count />
          </n-form-item>
          <n-form-item label="封面媒体 ID" :validation-status="fieldErrors.coverMediaId ? 'error' : undefined" :feedback="fieldErrors.coverMediaId">
            <n-space vertical size="small" style="width: 100%">
              <n-input-number v-model:value="coverMediaId" :min="1" placeholder="mediaId（上传接口就绪后改为选择器）" style="width: 280px" />
              <n-text depth="3" style="font-size: 12px">媒体上传与预览（M4）未交付，当前仅记录 mediaId。</n-text>
            </n-space>
          </n-form-item>
          <n-form-item label="说明" :validation-status="fieldErrors.introduce ? 'error' : undefined" :feedback="fieldErrors.introduce">
            <n-input
              v-model:value="introduce"
              type="textarea"
              placeholder="图片说明（可空）"
              :autosize="{ minRows: 2, maxRows: 4 }"
              maxlength="500"
            />
          </n-form-item>
          <n-form-item label="置顶">
            <n-switch v-model:value="isPinned" />
          </n-form-item>
          <n-form-item
            label="正文（Tiptap JSON，可空）"
            :validation-status="bodyError || fieldErrors.body ? 'error' : undefined"
            :feedback="bodyError || fieldErrors.body"
          >
            <n-input v-model:value="bodyText" type="textarea" :autosize="{ minRows: 8, maxRows: 20 }" />
          </n-form-item>
          <n-space>
            <n-button type="primary" :loading="saving" @click="save">保存</n-button>
            <n-button @click="router.push('/admin/gallery')">返回列表</n-button>
          </n-space>
        </n-form>
      </n-card>
    </n-spin>
  </div>
</template>
