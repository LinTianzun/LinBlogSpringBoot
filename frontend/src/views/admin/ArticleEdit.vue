<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadProps, UploadRequestOptions } from 'element-plus'
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
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

/** el-upload 自定义上传：POST /files/upload → 回填 coverMediaId */
async function uploadCover(options: UploadRequestOptions): Promise<unknown> {
  const raw = options.file
  uploading.value = true
  try {
    const result = await uploadMedia(raw)
    coverMediaId.value = result.id
    coverHint.value = `已上传：${result.storageKey}（${Math.round(result.sizeBytes / 1024)} KB）`
    ElMessage.success('封面上传成功')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
  return Promise.resolve()
}

const beforeUpload: UploadProps['beforeUpload'] = (rawFile) => {
  if (!rawFile.type.startsWith('image/')) {
    ElMessage.error('封面仅支持图片文件')
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
      ElMessage.success('文章已创建')
    } else {
      // isPinned 仅 PATCH 接受（backend ContentCreateRequest 无该字段）
      await updateContent(contentId.value, { ...payload, isPinned: isPinned.value })
      ElMessage.success('文章已保存')
    }
    await router.push('/admin/articles')
  } catch (error) {
    if (error instanceof ApiError && error.code === 40001 && error.fieldErrors) {
      applyFieldErrors(error.fieldErrors)
    } else {
      ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
    }
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <el-page-header :content="contentId === null ? '新建文章' : '编辑文章'" @back="router.back()" />

    <el-card v-loading="loading" shadow="never" class="lb-edit-card">
      <el-form label-position="top">
        <el-form-item label="标题" :error="fieldErrors.title">
          <el-input
            v-model="title"
            placeholder="文章标题"
            maxlength="150"
            show-word-limit
            data-testid="article-title"
          />
        </el-form-item>
        <el-form-item label="摘要" :error="fieldErrors.introduce">
          <el-input
            v-model="introduce"
            type="textarea"
            placeholder="显示在列表页的摘要（可空）"
            :autosize="{ minRows: 2, maxRows: 4 }"
            maxlength="512"
          />
        </el-form-item>
        <el-form-item label="标签" :error="fieldErrors.labelIds">
          <el-select
            v-model="labelIds"
            multiple
            clearable
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入新标签"
            style="width: 100%"
            :max-collapse-tags="6"
          >
            <el-option v-for="option in labelOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="封面" :error="fieldErrors.coverMediaId">
          <div class="lb-cover-field">
            <el-upload
              accept="image/*"
              :limit="1"
              :auto-upload="true"
              :http-request="uploadCover"
              :show-file-list="false"
              data-testid="article-cover-upload"
              :before-upload="beforeUpload"
            >
              <el-button size="small" :loading="uploading">上传封面图片</el-button>
            </el-upload>
            <el-input-number v-model="coverMediaId" :min="1" placeholder="或手填媒体 ID" style="width: 220px" size="small" />
            <span v-if="coverHint" class="lb-cover-hint">{{ coverHint }}</span>
          </div>
        </el-form-item>
        <el-form-item v-if="contentId !== null" label="置顶">
          <el-switch v-model="isPinned" />
        </el-form-item>
        <el-form-item label="正文" :error="fieldErrors.body">
          <RichEditor v-model="bodyText" />
        </el-form-item>
        <div class="lb-edit-actions">
          <el-button type="primary" :loading="saving" data-testid="article-save" @click="save">保存</el-button>
          <el-button @click="router.push('/admin/articles')">返回列表</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.lb-edit-card {
  margin-top: 16px;
}

.lb-cover-field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}

.lb-cover-hint {
  font-size: 12px;
  color: var(--lb-muted);
}

.lb-edit-actions {
  display: flex;
  gap: 10px;
}
</style>
