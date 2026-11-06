<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { UploadProps, UploadRequestOptions } from 'element-plus'
import {
  attachContentMedia,
  createContent,
  detachContentMedia,
  getAdminContent,
  listContentMedia,
  setContentMediaCover,
  updateContent,
  uploadMedia,
} from '@/api/admin'
import { ApiError } from '@/api/request'
import CoverImage from '@/components/CoverImage.vue'
import type { ContentMediaItemDTO, CreateContentPayload, FieldError, UploadResultDTO } from '@/types/api'

/**
 * 画廊编辑（§5.3）：/admin/gallery/edit/:id?。
 * 图集：一件作品可挂多张图（media.content_id 承载归属），封面 coverMediaId 为门户展示图；
 * 上传走 POST /files/upload（多选），编辑态即时挂载，新建态先暂存、保存后统一挂载。
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
const isPinned = ref(false)
const bodyText = ref('')
const saving = ref(false)
const loading = ref(false)
const uploading = ref(false)
const fieldErrors = ref<Record<string, string>>({})

/** 已挂载的图集（编辑态，来自后端） */
const album = ref<ContentMediaItemDTO[]>([])
/** 新建态暂存的已上传媒体（保存成功后统一挂载） */
const staged = ref<UploadResultDTO[]>([])

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
    album.value = await listContentMedia(contentId.value)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

/** el-upload 多选上传：编辑态即时挂载；新建态暂存，保存后统一挂载 */
async function uploadImages(options: UploadRequestOptions): Promise<unknown> {
  const raw = options.file
  uploading.value = true
  try {
    const result = await uploadMedia(raw)
    if (contentId.value !== null) {
      const item = await attachContentMedia(contentId.value, result.id)
      album.value.push(item)
      if (coverMediaId.value === null) {
        coverMediaId.value = result.id
        await setContentMediaCover(contentId.value, result.id)
        await refreshAlbum()
      }
    } else {
      staged.value.push(result)
      if (coverMediaId.value === null) coverMediaId.value = result.id
    }
    ElMessage.success('图片上传成功')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
  return Promise.resolve()
}

const beforeUpload: UploadProps['beforeUpload'] = (rawFile) => {
  if (!rawFile.type.startsWith('image/')) {
    ElMessage.error('仅支持图片文件')
    return false
  }
  return true
}

async function refreshAlbum(): Promise<void> {
  if (contentId.value === null) return
  album.value = await listContentMedia(contentId.value)
}

/** 编辑态：设为展示封面 */
async function pickCoverEdit(mediaId: number): Promise<void> {
  try {
    await setContentMediaCover(contentId.value!, mediaId)
    coverMediaId.value = mediaId
    await refreshAlbum()
    ElMessage.success('已设为展示封面')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '设置失败')
  }
}

/** 编辑态：从图集移除（封面需先换） */
async function removeFromAlbum(mediaId: number): Promise<void> {
  try {
    await detachContentMedia(contentId.value!, mediaId)
    album.value = album.value.filter((m) => m.mediaId !== mediaId)
    ElMessage.success('已移除')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '移除失败')
  }
}

/** 新建态：暂存图里挑封面 */
function pickCoverStaged(mediaId: number): void {
  coverMediaId.value = mediaId
}

/** 新建态：从暂存移除 */
function removeStaged(mediaId: number): void {
  staged.value = staged.value.filter((m) => m.id !== mediaId)
  if (coverMediaId.value === mediaId) {
    coverMediaId.value = staged.value[0]?.id ?? null
  }
}

async function save(): Promise<void> {
  fieldErrors.value = {}
  if (!title.value.trim()) {
    fieldErrors.value = { title: '标题不能为空' }
    return
  }
  if (coverMediaId.value === null) {
    fieldErrors.value = { coverMediaId: '请先上传图片' }
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
      const created = await createContent(payload)
      // 暂存图统一挂载到新作品（封面已在 payload 里，无需重复挂载标记）
      for (const m of staged.value) {
        if (m.id !== coverMediaId.value) {
          await attachContentMedia(created.id, m.id).catch(() => {})
        }
      }
      ElMessage.success('作品已创建')
    } else {
      // isPinned 仅 PATCH 接受（backend ContentCreateRequest 无该字段）
      await updateContent(contentId.value, { ...payload, isPinned: isPinned.value })
      ElMessage.success('作品已保存')
    }
    await router.push('/admin/gallery')
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
      <el-page-header :content="contentId === null ? '新建作品' : '编辑作品'" @back="router.back()" />
      <div class="lb-edit-actions">
        <el-button @click="router.push('/admin/gallery')">返回列表</el-button>
        <el-button type="primary" :loading="saving" data-testid="gallery-save" @click="save">保存</el-button>
      </div>
    </div>

    <el-card v-loading="loading" shadow="never" class="lb-edit-card">
      <el-form label-position="top">
        <div class="lb-edit-section">
          <span class="lb-edit-section-title">作品信息</span>
          <el-form-item label="标题" :error="fieldErrors.title">
            <el-input v-model="title" placeholder="作品标题" maxlength="150" show-word-limit data-testid="gallery-title" />
          </el-form-item>

          <el-form-item label="图集" :error="fieldErrors.coverMediaId">
            <div class="lb-album-field">
              <p class="lb-album-hint">
                可上传多张图片；带「封面」角标的是门户列表/详情默认展示图，鼠标悬停卡片可更换。
                {{ contentId === null ? '新建时先暂存，保存后统一挂载。' : '' }}
              </p>

              <!-- 图集网格：编辑态已挂载 + 新建态暂存 -->
              <div class="lb-album-grid">
                <div
                  v-for="m in album"
                  :key="`a-${m.mediaId}`"
                  class="lb-album-item"
                  :class="{ 'is-cover': m.cover }"
                >
                  <CoverImage :storage-key="m.storageKey" ratio="4:3" :alt="`图集图片 ${m.mediaId}`" />
                  <span v-if="m.cover" class="lb-album-badge">封面</span>
                  <div class="lb-album-ops">
                    <el-button
                      v-if="!m.cover"
                      size="small"
                      type="primary"
                      data-testid="gallery-pick-cover"
                      @click="pickCoverEdit(m.mediaId)"
                    >
                      设为封面
                    </el-button>
                    <el-button
                      v-if="!m.cover"
                      size="small"
                      type="danger"
                      plain
                      @click="removeFromAlbum(m.mediaId)"
                    >
                      移除
                    </el-button>
                  </div>
                </div>

                <div
                  v-for="m in staged"
                  :key="`s-${m.id}`"
                  class="lb-album-item"
                  :class="{ 'is-cover': coverMediaId === m.id }"
                >
                  <CoverImage :storage-key="m.storageKey" ratio="4:3" :alt="`暂存图片 ${m.id}`" />
                  <span v-if="coverMediaId === m.id" class="lb-album-badge">封面</span>
                  <div class="lb-album-ops">
                    <el-button
                      v-if="coverMediaId !== m.id"
                      size="small"
                      type="primary"
                      @click="pickCoverStaged(m.id)"
                    >
                      设为封面
                    </el-button>
                    <el-button size="small" type="danger" plain @click="removeStaged(m.id)">移除</el-button>
                  </div>
                </div>

                <el-upload
                  class="lb-album-upload"
                  accept="image/*"
                  multiple
                  :auto-upload="true"
                  :http-request="uploadImages"
                  :show-file-list="false"
                  data-testid="gallery-upload"
                  :before-upload="beforeUpload"
                >
                  <div class="lb-album-upload-box" v-loading="uploading">
                    <span class="lb-album-plus">＋</span>
                    <span class="lb-album-upload-label">上传图片</span>
                  </div>
                </el-upload>
              </div>
            </div>
          </el-form-item>

          <el-form-item v-if="contentId !== null" label="置顶">
            <el-switch v-model="isPinned" />
          </el-form-item>
        </div>

        <div class="lb-edit-section">
          <span class="lb-edit-section-title">描述</span>
          <el-form-item label="说明" :error="fieldErrors.introduce">
            <el-input
              v-model="introduce"
              type="textarea"
              placeholder="图片说明（可空）"
              :autosize="{ minRows: 2, maxRows: 4 }"
              maxlength="512"
            />
          </el-form-item>
          <el-form-item label="详情描述（可空，显示在详情页）">
            <el-input v-model="bodyText" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" />
          </el-form-item>
        </div>

        <div class="lb-edit-actions lb-edit-actions--bottom">
          <el-button type="primary" :loading="saving" data-testid="gallery-save-bottom" @click="save">保存</el-button>
          <el-button @click="router.push('/admin/gallery')">返回列表</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
/* 页头由 el-page-header 自带；此处保留分节卡片与图集上传字段样式。 */
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

/* ---------- 图集 ---------- */
.lb-album-field {
  width: 100%;
}

.lb-album-hint {
  margin: 0 0 12px;
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
}

.lb-album-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 14px;
  width: 100%;
}

.lb-album-item {
  position: relative;
  border-radius: var(--ed-radius-md);
  overflow: hidden;
  border: 2px solid var(--ed-rule-soft);
  transition: border-color var(--lb-dur-fast, 160ms) ease;
}

.lb-album-item.is-cover {
  border-color: var(--ed-accent);
}

.lb-album-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 2;
  padding: 2px 10px;
  border-radius: var(--ed-radius-pill);
  background: var(--ed-accent);
  color: #fff;
  font-size: var(--ed-step-caption);
  font-weight: 600;
}

.lb-album-ops {
  position: absolute;
  inset: auto 0 0 0;
  z-index: 2;
  display: flex;
  gap: 6px;
  padding: 8px;
  background: linear-gradient(transparent, rgba(20, 20, 20, 0.62));
  opacity: 0;
  transition: opacity var(--lb-dur-fast, 160ms) ease;
}

.lb-album-item:hover .lb-album-ops {
  opacity: 1;
}

.lb-album-upload {
  width: 100%;
}

.lb-album-upload-box {
  min-height: 150px;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1.5px dashed var(--ed-rule-soft);
  border-radius: var(--ed-radius-md);
  color: var(--ed-ink-soft);
  cursor: pointer;
  transition: border-color var(--lb-dur-fast, 160ms) ease, color var(--lb-dur-fast, 160ms) ease;
}

.lb-album-upload-box:hover {
  border-color: var(--ed-accent);
  color: var(--ed-accent);
}

.lb-album-plus {
  font-size: 28px;
  line-height: 1;
}

.lb-album-upload-label {
  font-size: var(--ed-step-caption);
}

.lb-edit-actions {
  display: flex;
  gap: 10px;
}

.lb-edit-actions--bottom {
  padding-top: 4px;
}
</style>
