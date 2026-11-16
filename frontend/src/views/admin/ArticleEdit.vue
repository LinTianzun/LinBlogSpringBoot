<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadProps, UploadRequestOptions } from 'element-plus'
import { createContent, getAdminContent, listLabels, updateContent, uploadMedia } from '@/api/admin'
import { ApiError } from '@/api/request'
import RichEditor from '@/components/RichEditor.vue'
import CoverImage from '@/components/CoverImage.vue'
import { plainTextOf } from '@/components/renderContent'
import { aiSuggestArticleMeta, aiRewriteText } from '@/api/ai'
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
const coverKey = ref('')
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
      coverKey.value = detail.coverStorageKey ?? ''
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

/** el-upload 自定义上传：POST /files/upload → 回填 coverMediaId + 预览 storageKey */
async function uploadCover(options: UploadRequestOptions): Promise<unknown> {
  const raw = options.file
  uploading.value = true
  try {
    const result = await uploadMedia(raw)
    coverMediaId.value = result.id
    coverKey.value = result.storageKey
    coverHint.value = `已上传：${result.storageKey}（${Math.round(result.sizeBytes / 1024)} KB）`
    ElMessage.success('封面上传成功')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
  return Promise.resolve()
}

/** 移除封面：只清引用（媒体文件本体走文件管理回收） */
function removeCover(): void {
  coverMediaId.value = null
  coverKey.value = ''
  coverHint.value = ''
}

const beforeUpload: UploadProps['beforeUpload'] = (rawFile) => {
  if (!rawFile.type.startsWith('image/')) {
    ElMessage.error('封面仅支持图片文件')
    return false
  }
  return true
}

/* ---- AI 写作建议（只建议不落库；作者确认后才写入表单字段） ---- */
const aiSuggesting = ref(false)

async function aiSuggest(): Promise<void> {
  const content = plainTextOf(bodyText.value, 3000)
  if (!title.value.trim() || !content) {
    ElMessage.warning('请先填写标题和正文，再获取 AI 建议')
    return
  }
  aiSuggesting.value = true
  try {
    const suggestion = await aiSuggestArticleMeta({ title: title.value.trim(), content })
    if (suggestion.summary && !introduce.value.trim()) {
      introduce.value = suggestion.summary
    }
    if (suggestion.labels.length > 0) {
      // 合并建议标签：已有标签映射到 id，新名走 allow-create 直入
      const merged: Array<number> = [...labelIds.value]
      for (const name of suggestion.labels) {
        const option = labelOptions.value.find((o) => o.label === name)
        if (option) {
          if (!merged.includes(option.value)) merged.push(option.value)
        } else if (!merged.includes(name as unknown as number)) {
          merged.push(name as unknown as number)
        }
      }
      labelIds.value = merged
    }
    ElMessage.success(`AI 建议已生成（${suggestion.modelName}），请确认后保存`)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : 'AI 建议获取失败')
  } finally {
    aiSuggesting.value = false
  }
}

/* ---- AI 写作辅助（润色/精简/扩写/续写；候选确认后才替换正文） ---- */
const WRITING_ACTIONS = [
  { key: 'polish', label: '润色' },
  { key: 'shorten', label: '精简' },
  { key: 'expand', label: '扩写' },
  { key: 'continue', label: '续写' },
] as const

const writingRunning = ref(false)
const writingCandidate = ref<{ text: string; modelName: string; action: string } | null>(null)
const writingActionLabel = computed(() =>
  WRITING_ACTIONS.find((a) => a.key === writingCandidate.value?.action)?.label ?? '')

async function aiWrite(action: (typeof WRITING_ACTIONS)[number]['key']): Promise<void> {
  const plain = plainTextOf(bodyText.value, 6000)
  if (!plain) {
    ElMessage.warning('请先填写正文，再使用 AI 写作辅助')
    return
  }
  writingRunning.value = true
  try {
    const result = await aiRewriteText({ action, title: title.value.trim(), text: plain })
    writingCandidate.value = { ...result, action }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : 'AI 写作失败')
  } finally {
    writingRunning.value = false
  }
}

/** 采用候选：整段替换为纯段落 ProseMirror doc（格式化内容会被平铺，弹窗内已提示） */
function adoptWritingCandidate(): void {
  if (!writingCandidate.value) return
  const paras = writingCandidate.value.text
    .split(/\n+/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => ({ type: 'paragraph', content: [{ type: 'text', text: line }] }))
  if (paras.length === 0) return
  bodyText.value = JSON.stringify({ type: 'doc', content: paras })
  writingCandidate.value = null
  ElMessage.success('已写入编辑器，可继续调整后保存')
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
    <div class="lb-edit-head">
      <el-page-header :content="contentId === null ? '新建文章' : '编辑文章'" @back="router.back()" />
      <div class="lb-edit-actions">
        <el-button @click="router.push('/admin/articles')">返回列表</el-button>
        <el-button type="primary" :loading="saving" data-testid="article-save" @click="save">保存</el-button>
      </div>
    </div>

    <el-card v-loading="loading" shadow="never" class="lb-edit-card">
      <el-form label-position="top">
        <div class="lb-edit-section lb-edit-section--meta">
          <div class="lb-edit-section-head">
            <span class="lb-edit-section-title">基本信息</span>
            <span class="lb-edit-section-ops">
              <el-dropdown :disabled="writingRunning" @command="(cmd: string) => aiWrite(cmd as 'polish')">
                <el-button link type="primary" :loading="writingRunning" data-testid="article-ai-write">
                  ✦ AI 写作
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="a in WRITING_ACTIONS" :key="a.key" :command="a.key">
                      {{ a.label }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button
                link
                type="primary"
                :loading="aiSuggesting"
                data-testid="article-ai-suggest"
                @click="aiSuggest"
              >
                ✦ AI 建议摘要与标签
              </el-button>
            </span>
          </div>
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
              <CoverImage
                v-if="coverMediaId !== null && coverKey"
                :storage-key="coverKey"
                ratio="16:6"
                class="lb-cover-preview"
                alt="封面预览"
              />
              <div class="lb-cover-actions">
                <el-upload
                  accept="image/*"
                  :limit="1"
                  :auto-upload="true"
                  :http-request="uploadCover"
                  :show-file-list="false"
                  data-testid="article-cover-upload"
                  :before-upload="beforeUpload"
                >
                  <el-button :loading="uploading">{{ coverMediaId !== null ? '更换封面' : '上传封面图片' }}</el-button>
                </el-upload>
                <el-button v-if="coverMediaId !== null" link type="danger" @click="removeCover">移除封面</el-button>
                <el-input-number
                  v-model="coverMediaId"
                  :min="1"
                  placeholder="媒体 ID"
                  style="width: 150px"
                  title="高级：直接填写媒体 ID（正常情况用上传）"
                />
              </div>
              <span v-if="coverHint" class="lb-cover-hint">{{ coverHint }}</span>
            </div>
          </el-form-item>
          <el-form-item v-if="contentId !== null" label="置顶">
            <el-switch v-model="isPinned" />
          </el-form-item>
        </div>

        <div class="lb-edit-section">
          <span class="lb-edit-section-title">正文内容</span>
          <el-form-item label="正文" :error="fieldErrors.body">
            <RichEditor v-model="bodyText" />
          </el-form-item>
        </div>

        <div class="lb-edit-actions lb-edit-actions--bottom">
          <el-button type="primary" :loading="saving" data-testid="article-save-bottom" @click="save">保存</el-button>
          <el-button @click="router.push('/admin/articles')">返回列表</el-button>
        </div>
      </el-form>
    </el-card>

    <!-- AI 写作候选（只建议；采用后整段替换正文为纯段落） -->
    <el-dialog
      :model-value="writingCandidate !== null"
      title="AI 写作候选"
      width="640px"
      @update:model-value="(show: boolean) => { if (!show) writingCandidate = null }"
    >
      <template v-if="writingCandidate">
        <p class="lb-writing-warn">
          动作：{{ writingActionLabel }} ·
          由 {{ writingCandidate.modelName }} 生成。采用后将<b>整段替换当前正文</b>为纯段落（图文格式会被平铺），请先确认。
        </p>
        <pre class="lb-writing-candidate">{{ writingCandidate.text }}</pre>
      </template>
      <template #footer>
        <div class="lb-dialog-actions">
          <el-button size="small" @click="writingCandidate = null">放弃</el-button>
          <el-button size="small" type="primary" :disabled="!writingCandidate" @click="adoptWritingCandidate">
            采用并替换正文
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 页头由 el-page-header 自带；此处保留分节卡片与封面上传字段样式。 */
.lb-edit-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-gap);
  flex-wrap: wrap;
  margin-bottom: var(--app-gap);
}

.lb-edit-card {
  margin-top: 0;
}

.lb-edit-section {
  padding-bottom: var(--app-gap);
  margin-bottom: var(--app-gap);
  border-bottom: 1px solid var(--ed-rule-soft);
}

/* 基本信息表单收窄到舒适阅读宽度（正文编辑器保持全宽） */
.lb-edit-section--meta :deep(.el-form-item) {
  max-width: 720px;
}

.lb-edit-section:last-of-type {
  border-bottom: none;
}

.lb-edit-section-title {
  display: inline-block;
  font-size: var(--ed-step-h3);
  font-weight: 700;
  color: var(--ed-ink);
  margin-bottom: 16px;
}

.lb-cover-field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}

.lb-cover-preview {
  width: min(360px, 100%);
  border-radius: var(--ed-radius-md);
  overflow: hidden;
  box-shadow: var(--ed-shadow-sm);
}

.lb-cover-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.lb-cover-hint {
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

.lb-edit-actions {
  display: flex;
  gap: 10px;
}

.lb-edit-actions--bottom {
  padding-top: 4px;
}
</style>
<style scoped>
/* 分节标题行：标题 + 行内操作（AI 建议按钮） */
.lb-edit-section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.lb-edit-section-head .lb-edit-section-title {
  margin-bottom: 0;
}
</style>

/* AI 写作候选弹窗 */
.lb-edit-section-ops {
  display: inline-flex;
  align-items: baseline;
  gap: 14px;
}

.lb-writing-warn {
  margin: 0 0 12px;
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

.lb-writing-candidate {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  padding: 12px 14px;
  background: var(--ed-paper);
  border-radius: var(--ed-radius-sm);
  white-space: pre-wrap;
  font-size: var(--ed-step-small);
  line-height: 1.8;
}
