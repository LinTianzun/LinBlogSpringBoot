<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, NInputNumber, NSwitch, NUpload, useMessage, type UploadCustomRequestOptions, type UploadFileInfo } from 'naive-ui'
import { createContent, getAdminContent, listLabels, updateContent, uploadMedia } from '@/api/admin'
import { ApiError } from '@/api/request'
import RichEditor from '@/components/RichEditor.vue'
import type { CreateContentPayload, FieldError } from '@/types/api'

/**
 * 文章编辑（§5.3 ContentAdminController）：/admin/articles/edit/:id?。
 * 有 :id → 加载后编辑；无 → 新建（默认草稿，发布走管理页状态机）。
 * 正文 RichEditor（Tiptap）产 ProseMirror JSON 字符串；封面走 POST /files/upload。
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
const coverHint = ref('')
const isPinned = ref(false)
const labelIds = ref<number[]>([])
const labelOptions = ref<Array<{ label: string; value: number }>>([])
const bodyText = ref(JSON.stringify({ type: 'doc', content: [] }))
const saving = ref(false)
const loading = ref(false)
const uploading = ref(false)
const fieldErrors = ref<Record<string, string>>({})

function applyFieldErrors(errors: FieldError[]): void {
  const map: Record<string, string> = {}
  for (const item of errors) map[item.field] = item.message
  fieldErrors.value = map
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const listTask = listLabels()
    if (contentId.value !== null) {
      const detail = await getAdminContent(contentId.value)
      title.value = detail.title
      introduce.value = detail.introduce ?? ''
      coverMediaId.value = detail.coverMediaId ?? null
      isPinned.value = detail.isPinned ?? false
      labelIds.value = detail.labelIds ?? []
      bodyText.value = detail.body ?? JSON.stringify({ type: 'doc', content: [] })
    }
    const labels = await listTask
    labelOptions.value = labels.map((item) => ({ label: item.name, value: item.id }))
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

/** n-upload 自定义上传：POST /files/upload → 回填 coverMediaId */
async function uploadCover({ file }: UploadCustomRequestOptions): Promise<void> {
  const raw = file.file
  if (!raw) return
  uploading.value = true
  try {
    const result = await uploadMedia(raw)
    coverMediaId.value = result.id
    coverHint.value = `已上传：${result.storageKey}（${Math.round(result.sizeBytes / 1024)} KB）`
    message.success('封面上传成功')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
}

function beforeUpload({ file }: { file: UploadFileInfo }): boolean {
  if (file.file && !file.file.type.startsWith('image/')) {
    message.error('封面仅支持图片文件')
    return false
  }
  return true
}

async function save(): Promise<void> {
  fieldErrors.value = {}
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
      labelIds: labelIds.value.length > 0 ? labelIds.value : undefined,
      body: bodyText.value,
    }
    if (contentId.value === null) {
      await createContent(payload)
      message.success('文章已创建')
    } else {
      // isPinned 仅 PATCH 接受（backend ContentCreateRequest 无该字段）
      await updateContent(contentId.value, { ...payload, isPinned: isPinned.value })
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
            <n-input v-model:value="title" placeholder="文章标题" maxlength="150" show-count data-testid="article-title" />
          </n-form-item>
          <n-form-item label="摘要" :validation-status="fieldErrors.introduce ? 'error' : undefined" :feedback="fieldErrors.introduce">
            <n-input
              v-model:value="introduce"
              type="textarea"
              placeholder="显示在列表页的摘要（可空）"
              :autosize="{ minRows: 2, maxRows: 4 }"
              maxlength="512"
            />
          </n-form-item>
          <n-form-item label="标签" :validation-status="fieldErrors.labelIds ? 'error' : undefined" :feedback="fieldErrors.labelIds">
            <n-select
              v-model:value="labelIds"
              multiple
              clearable
              filterable
              tag
              placeholder="选择或输入新标签"
              :options="labelOptions"
              :max-tag-count="6"
            />
          </n-form-item>
          <n-form-item label="封面" :validation-status="fieldErrors.coverMediaId ? 'error' : undefined" :feedback="fieldErrors.coverMediaId">
            <n-space vertical size="small" style="width: 100%">
              <n-upload
                accept="image/*"
                :max="1"
                :default-upload="true"
                :custom-request="uploadCover"
                :show-file-list="false"
                data-testid="article-cover-upload"
                @before-upload="beforeUpload"
              >
                <n-button size="small" :loading="uploading">上传封面图片</n-button>
              </n-upload>
              <n-input-number v-model:value="coverMediaId" :min="1" placeholder="或手填媒体 ID" style="width: 220px" size="small" />
              <n-text v-if="coverHint" depth="3" style="font-size: 12px">{{ coverHint }}</n-text>
            </n-space>
          </n-form-item>
          <n-form-item v-if="contentId !== null" label="置顶">
            <n-switch v-model:value="isPinned" />
          </n-form-item>
          <n-form-item label="正文" :validation-status="fieldErrors.body ? 'error' : undefined" :feedback="fieldErrors.body">
            <RichEditor v-model="bodyText" />
          </n-form-item>
          <n-space>
            <n-button type="primary" :loading="saving" data-testid="article-save" @click="save">保存</n-button>
            <n-button @click="router.push('/admin/articles')">返回列表</n-button>
          </n-space>
        </n-form>
      </n-card>
    </n-spin>
  </div>
</template>
