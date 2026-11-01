<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, NInputNumber, NSwitch, NUpload, useMessage, type UploadCustomRequestOptions, type UploadFileInfo } from 'naive-ui'
import { createContent, getAdminContent, updateContent, uploadMedia } from '@/api/admin'
import { ApiError } from '@/api/request'
import type { CreateContentPayload, FieldError } from '@/types/api'

/**
 * 画廊编辑（§5.3）：/admin/gallery/edit/:id?。
 * 图片内容以 coverMediaId 承载（后端强校验：image 必须 coverMediaId），
 * 上传走 POST /files/upload；正文为可选说明（本页用纯文本，不入 ProseMirror JSON）。
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
const bodyText = ref('')
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
  if (contentId.value === null) return
  loading.value = true
  try {
    const detail = await getAdminContent(contentId.value)
    title.value = detail.title
    introduce.value = detail.introduce ?? ''
    coverMediaId.value = detail.coverMediaId ?? null
    isPinned.value = detail.isPinned ?? false
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function uploadCover({ file }: UploadCustomRequestOptions): Promise<void> {
  const raw = file.file
  if (!raw) return
  uploading.value = true
  try {
    const result = await uploadMedia(raw)
    coverMediaId.value = result.id
    coverHint.value = `已上传：${result.storageKey}（${Math.round(result.sizeBytes / 1024)} KB）`
    message.success('图片上传成功')
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
}

function beforeUpload({ file }: { file: UploadFileInfo }): boolean {
  if (file.file && !file.file.type.startsWith('image/')) {
    message.error('仅支持图片文件')
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
  if (coverMediaId.value === null) {
    fieldErrors.value = { coverMediaId: '请先上传图片或填写媒体 ID' }
    return
  }

  saving.value = true
  try {
    const payload: CreateContentPayload = {
      contentType: 'image',
      title: title.value.trim(),
      introduce: introduce.value.trim() || undefined,
      coverMediaId: coverMediaId.value,
      body: bodyText.value.trim() || undefined,
    }
    if (contentId.value === null) {
      await createContent(payload)
      message.success('图片已创建')
    } else {
      // isPinned 仅 PATCH 接受（backend ContentCreateRequest 无该字段）
      await updateContent(contentId.value, { ...payload, isPinned: isPinned.value })
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
            <n-input v-model:value="title" placeholder="图片标题" maxlength="150" show-count data-testid="gallery-title" />
          </n-form-item>
          <n-form-item label="图片" :validation-status="fieldErrors.coverMediaId ? 'error' : undefined" :feedback="fieldErrors.coverMediaId">
            <n-space vertical size="small" style="width: 100%">
              <n-upload
                accept="image/*"
                :max="1"
                :custom-request="uploadCover"
                :show-file-list="false"
                data-testid="gallery-upload"
                @before-upload="beforeUpload"
              >
                <n-button size="small" type="primary" :loading="uploading">上传图片</n-button>
              </n-upload>
              <n-input-number v-model:value="coverMediaId" :min="1" placeholder="或手填媒体 ID" style="width: 220px" size="small" />
              <n-text v-if="coverHint" depth="3" style="font-size: 12px">{{ coverHint }}</n-text>
            </n-space>
          </n-form-item>
          <n-form-item label="说明" :validation-status="fieldErrors.introduce ? 'error' : undefined" :feedback="fieldErrors.introduce">
            <n-input
              v-model:value="introduce"
              type="textarea"
              placeholder="图片说明（可空）"
              :autosize="{ minRows: 2, maxRows: 4 }"
              maxlength="512"
            />
          </n-form-item>
          <n-form-item v-if="contentId !== null" label="置顶">
            <n-switch v-model:value="isPinned" />
          </n-form-item>
          <n-form-item label="详情描述（可空，显示在详情页）">
            <n-input v-model:value="bodyText" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" />
          </n-form-item>
          <n-space>
            <n-button type="primary" :loading="saving" data-testid="gallery-save" @click="save">保存</n-button>
            <n-button @click="router.push('/admin/gallery')">返回列表</n-button>
          </n-space>
        </n-form>
      </n-card>
    </n-spin>
  </div>
</template>
