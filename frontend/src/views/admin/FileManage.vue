<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions, UploadProps } from 'element-plus'
import {
  Delete,
  Document,
  Picture as PictureIcon,
  RefreshLeft,
  Upload,
} from '@element-plus/icons-vue'
import { getStorageStatus, listAdminFiles, purgeFile, restoreFile, trashFile, uploadMedia } from '@/api/admin'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import PaginationBar from '@/components/PaginationBar.vue'
import { mediaUrl } from '@/utils/media'
import { formatBytes, formatDateTime } from '@/utils/format'
import type { FileAdminViewDTO, StorageStatusDTO } from '@/types/api'

/**
 * 文件管理（§5.7 FileAdminController，file.view）— task-23 改造：
 * 去掉 9 列大表格，改为「缩略图卡片网格」。每张卡：预览缩略图（图片为实图，PDF/其它为图标块）
 * + 文件名 + storageKey 小字 + 大小 + 上传者 + 状态药丸 + 时间 + 操作（移入备份区/还原/彻底删除）。
 * 保留：主区/备份区切换、存储用量条、上传、搜索、分页、按钮级权限（file.upload / file.delete）、
 * data-testid（file-upload / file-upload-button）、loading/空/错态。全部沿用 .lb-ed 亮卡片语言。
 */

const STATE_OPTIONS: Array<{ label: string; value: string }> = [
  { label: '主区（正常）', value: 'active' },
  { label: '备份区（回收站）', value: 'trashed' },
]

const session = useSessionStore()

const records = ref<FileAdminViewDTO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const state = ref('active')
const keyword = ref('')
const loading = ref(false)
const errorMessage = ref('')

const storageStatus = ref<StorageStatusDTO | null>(null)
const storageError = ref('')
const uploading = ref(false)

const canUpload = computed(() => session.can('file.upload'))
const canDelete = computed(() => session.can('file.delete'))

const isImage = (row: FileAdminViewDTO): boolean => row.mimeType.startsWith('image/')

/** 存储用量：主区/备份区各自占配额比例 */
const primaryPercent = computed(() => {
  const quota = storageStatus.value?.quotaBytes ?? 0
  if (!quota) return 0
  return Math.min(100, ((storageStatus.value?.primaryBytes ?? 0) / quota) * 100)
})
const backupPercent = computed(() => {
  const quota = storageStatus.value?.quotaBytes ?? 0
  if (!quota) return 0
  return Math.min(100, ((storageStatus.value?.backupBytes ?? 0) / quota) * 100)
})
const storageTotalUsed = computed(() => (storageStatus.value?.primaryBytes ?? 0) + (storageStatus.value?.backupBytes ?? 0))
const backupOver = computed(() => (storageStatus.value?.backupPercent ?? 0) >= 80)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminFiles({
      page: page.value,
      pageSize,
      state: state.value,
      keyword: keyword.value || undefined,
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadStorage(): Promise<void> {
  storageError.value = ''
  try {
    storageStatus.value = await getStorageStatus()
  } catch (error) {
    storageError.value = error instanceof ApiError ? error.message : '存储用量加载失败'
    storageStatus.value = null
  }
}

function pickState(value: string): void {
  page.value = 1
  state.value = value
  void load()
}

function search(): void {
  page.value = 1
  void load()
}

/** el-upload http-request：走统一 fetch 封装上传，成功后刷新列表 */
async function uploadFile(options: UploadRequestOptions): Promise<unknown> {
  uploading.value = true
  try {
    const result = await uploadMedia(options.file)
    ElMessage.success(result.duplicated ? `「${options.file.name}」已存在（秒传）` : '上传成功')
    void load()
    void loadStorage()
    return result
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '上传失败')
    throw error
  } finally {
    uploading.value = false
  }
}

const beforeUpload: UploadProps['beforeUpload'] = (rawFile) => {
  const ok = rawFile.type.startsWith('image/') || rawFile.type === 'application/pdf'
  if (!ok) ElMessage.error('仅支持图片与 PDF 文件')
  return ok
}

async function confirmTrash(row: FileAdminViewDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定将「${row.originalName}」移入备份区吗？`, '移入备份区', {
      type: 'warning',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await trashFile(row.id)
    ElMessage.success('已移入备份区')
    await Promise.all([load(), loadStorage()])
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

async function doRestore(row: FileAdminViewDTO): Promise<void> {
  try {
    await restoreFile(row.id)
    ElMessage.success('已还原到主区')
    await Promise.all([load(), loadStorage()])
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

async function confirmPurge(row: FileAdminViewDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定彻底删除「${row.originalName}」吗？该操作不可恢复。`, '彻底删除', {
      type: 'error',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await purgeFile(row.id)
    ElMessage.success('已彻底删除')
    await Promise.all([load(), loadStorage()])
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(() => {
  void load()
  void loadStorage()
})
</script>

<template>
  <div>
    <div class="lb-page-header">
      <h2>文件管理</h2>
      <span class="lb-muted">媒体上传与存储维护（file.view）</span>
    </div>

    <!-- 存储用量条 -->
    <section v-if="storageStatus" class="lb-ov-card lb-file-storage">
      <div class="lb-file-storage-head">
        <div class="lb-file-storage-total">
          <span class="lb-file-storage-used lb-num">{{ formatBytes(storageTotalUsed) }}</span>
          <span class="lb-file-storage-quota lb-num">配额 {{ formatBytes(storageStatus.quotaBytes) }}</span>
        </div>
        <span class="lb-file-storage-tag" :class="{ 'is-over': backupOver }">
          备份区 {{ storageStatus.backupPercent.toFixed(1) }}%
        </span>
      </div>
      <div class="lb-file-storage-bar" :class="{ 'is-alert': backupOver }">
        <span class="lb-file-storage-fill lb-file-storage-fill--primary" :style="{ width: `${primaryPercent}%` }" />
        <span class="lb-file-storage-fill lb-file-storage-fill--backup" :style="{ width: `${backupPercent}%` }" />
      </div>
      <div class="lb-file-storage-legend">
        <span class="lb-file-storage-key"><i class="lb-dot-primary" />主区 {{ formatBytes(storageStatus.primaryBytes) }} · {{ storageStatus.primaryCount }} 个</span>
        <span class="lb-file-storage-key"><i class="lb-dot-backup" />备份区 {{ formatBytes(storageStatus.backupBytes) }} · {{ storageStatus.backupCount }} 个</span>
      </div>
      <div v-if="backupOver" class="lb-file-storage-alert">备份区占用已超过 80%，请清理回收站（彻底删除）。</div>
    </section>
    <el-alert v-else-if="storageError" type="warning" class="lb-file-storage-err" :closable="false">
      {{ storageError }}
    </el-alert>

    <!-- 主卡：工具栏 + 卡片网格 -->
    <el-card shadow="never">
      <div class="lb-manage-filters">
        <el-input
          v-model="keyword"
          placeholder="搜索文件名"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Document /></el-icon></template>
        </el-input>
        <el-select :model-value="state" style="width: 180px" @update:model-value="pickState">
          <el-option v-for="option in STATE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button :disabled="loading" @click="search">搜索</el-button>
        <div class="lb-file-upload-slot">
          <el-upload
            v-if="canUpload"
            :http-request="uploadFile"
            :before-upload="beforeUpload"
            :show-file-list="false"
            :disabled="uploading"
            accept="image/*,application/pdf"
            data-testid="file-upload"
          >
            <el-button type="primary" :loading="uploading" data-testid="file-upload-button">
              <el-icon style="margin-right: 4px"><Upload /></el-icon>上传文件
            </el-button>
          </el-upload>
        </div>
      </div>

      <el-alert v-if="errorMessage" type="warning" class="lb-load-alert" :closable="false">
        {{ errorMessage }}
        <el-button link size="small" @click="load">重试</el-button>
      </el-alert>

      <!-- 加载骨架 -->
      <div v-if="loading" class="lb-file-grid">
        <div v-for="n in 8" :key="`skel-${n}`" class="lb-file-card lb-ed-skel" style="height: 232px" />
      </div>

      <!-- 空态 -->
      <div v-else-if="records.length === 0 && !errorMessage" class="lb-ed-empty">
        {{ state === 'trashed' ? '备份区暂无文件。' : '暂无文件，点击右上角「上传文件」添加。' }}
      </div>

      <!-- 文件卡片网格 -->
      <div v-else class="lb-file-grid">
        <article v-for="row in records" :key="row.id" class="lb-file-card">
          <!-- 缩略图 -->
          <div class="lb-file-thumb" :class="{ 'is-image': isImage(row) }">
            <img
              v-if="isImage(row)"
              :src="mediaUrl(row.storageKey, 320)"
              :alt="row.originalName"
              loading="lazy"
            />
            <span v-else class="lb-file-thumb-icon" aria-hidden="true">
              <el-icon :size="30"><PictureIcon /></el-icon>
              <span class="lb-file-thumb-ext">{{ (row.mimeType.split('/')[1] || 'file').toUpperCase() }}</span>
            </span>
            <span class="lb-file-pill" :class="row.state === 'active' ? 'is-active' : 'is-trashed'">
              {{ row.state === 'active' ? '主区' : '备份区' }}
            </span>
          </div>

          <!-- 信息 -->
          <div class="lb-file-body">
            <span class="lb-file-name" :title="row.originalName">{{ row.originalName }}</span>
            <code class="lb-file-key" :title="row.storageKey">{{ row.storageKey }}</code>
            <div class="lb-file-meta lb-num">
              <span>{{ formatBytes(row.sizeBytes) }}</span>
              <span class="lb-file-dot" aria-hidden="true">·</span>
              <span class="lb-file-uploader">{{ row.uploaderName ?? '—' }}</span>
            </div>
            <span class="lb-file-time lb-num">{{ formatDateTime(row.createdAt) }}</span>

            <!-- 操作 -->
            <div class="lb-file-actions">
              <template v-if="row.state === 'active'">
                <button
                  v-if="canDelete"
                  type="button"
                  class="lb-file-action"
                  @click="confirmTrash(row)"
                >
                  <el-icon :size="13"><Delete /></el-icon>移入备份区
                </button>
              </template>
              <template v-else>
                <button
                  v-if="canDelete"
                  type="button"
                  class="lb-file-action is-primary"
                  @click="doRestore(row)"
                >
                  <el-icon :size="13"><RefreshLeft /></el-icon>还原
                </button>
                <button
                  v-if="canDelete"
                  type="button"
                  class="lb-file-action is-danger"
                  @click="confirmPurge(row)"
                >
                  <el-icon :size="13"><Delete /></el-icon>彻底删除
                </button>
              </template>
            </div>
          </div>
        </article>
      </div>

      <div class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
/* 亮卡片面（与 OverView 一致） */
.lb-file-storage,
.lb-ov-card {
  background: var(--ed-card);
  border: 1px solid color-mix(in srgb, var(--ed-ink) 5%, transparent);
  border-radius: var(--ed-radius-lg);
  box-shadow: var(--ed-shadow-sm);
}

/* ---------- 存储用量 ---------- */
.lb-file-storage {
  padding: clamp(16px, 2vw, 20px);
  margin-bottom: var(--app-gap);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lb-file-storage-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.lb-file-storage-total {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.lb-file-storage-used {
  font-family: var(--ed-font-text);
  font-size: 24px;
  font-weight: 700;
  color: var(--ed-ink);
  letter-spacing: -0.01em;
}

.lb-file-storage-quota {
  font-size: 12px;
  color: var(--ed-ink-soft);
}

.lb-file-storage-tag {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: var(--ed-radius-pill);
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-file-storage-tag.is-over {
  color: var(--app-negative, #d64545);
  background: color-mix(in srgb, var(--app-negative, #d64545) 12%, transparent);
}

.lb-file-storage-bar {
  display: flex;
  height: 10px;
  border-radius: var(--ed-radius-pill);
  overflow: hidden;
  background: color-mix(in srgb, var(--ed-ink) 6%, var(--ed-paper));
}

.lb-file-storage-fill {
  height: 100%;
  transition: width 400ms ease;
}

.lb-file-storage-fill--primary {
  background: var(--ed-accent);
}

.lb-file-storage-fill--backup {
  background: color-mix(in srgb, var(--app-warning, #e0a13d) 85%, var(--ed-card));
}

.lb-file-storage-bar.is-alert .lb-file-storage-fill--backup {
  background: var(--app-negative, #d64545);
}

.lb-file-storage-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12px;
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}

.lb-file-storage-key {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.lb-file-storage-key i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
}

.lb-dot-primary {
  background: var(--ed-accent);
}

.lb-dot-backup {
  background: color-mix(in srgb, var(--app-warning, #e0a13d) 85%, var(--ed-card));
}

.lb-file-storage-alert {
  padding: 8px 12px;
  border-radius: var(--ed-radius-sm);
  font-size: 12px;
  color: var(--app-negative, #d64545);
  background: color-mix(in srgb, var(--app-negative, #d64545) 10%, transparent);
}

.lb-file-storage-err {
  margin-bottom: var(--app-gap);
}

/* ---------- 工具栏 ---------- */
.lb-file-upload-slot {
  margin-left: auto;
}

/* ---------- 卡片网格 ---------- */
.lb-file-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 16px;
}

@media (max-width: 560px) {
  .lb-file-grid {
    grid-template-columns: 1fr;
  }
}

.lb-file-card {
  display: flex;
  flex-direction: column;
  background: var(--ed-card);
  border: 1px solid var(--ed-rule-soft);
  border-radius: var(--ed-radius-md);
  overflow: hidden;
  transition: box-shadow 220ms ease, border-color 220ms ease, transform 220ms ease;
}

.lb-file-card:hover {
  box-shadow: var(--ed-shadow-md);
  border-color: color-mix(in srgb, var(--ed-accent) 24%, var(--ed-rule-soft));
  transform: translateY(-3px);
}

/* 缩略图区 */
.lb-file-thumb {
  position: relative;
  aspect-ratio: 4 / 3;
  background: var(--ed-accent-soft);
  overflow: hidden;
  display: grid;
  place-items: center;
}

.lb-file-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform 460ms cubic-bezier(0.22, 1, 0.36, 1);
}

.lb-file-card:hover .lb-file-thumb img {
  transform: scale(1.05);
}

.lb-file-thumb-icon {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--ed-accent-ink);
}

.lb-file-thumb-ext {
  font-family: var(--ed-font-text);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  padding: 2px 8px;
  border-radius: var(--ed-radius-pill);
  background: color-mix(in srgb, var(--ed-accent) 16%, var(--ed-card));
  color: var(--ed-accent-ink);
}

/* 状态药丸叠在图左上 */
.lb-file-pill {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 1;
  font-size: 0.72rem;
  font-weight: 600;
  line-height: 1;
  padding: 4px 10px;
  border-radius: var(--ed-radius-pill);
  color: #fff;
  backdrop-filter: blur(4px);
}

.lb-file-pill.is-active {
  background: color-mix(in srgb, var(--app-positive, #2e9e6b) 90%, transparent);
}

.lb-file-pill.is-trashed {
  background: color-mix(in srgb, var(--app-warning, #e0a13d) 92%, transparent);
}

/* 信息区 */
.lb-file-body {
  padding: 12px 14px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.lb-file-name {
  font-family: var(--ed-font-text);
  font-size: var(--ed-step-small);
  font-weight: 600;
  color: var(--ed-ink);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-file-key {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11px;
  color: var(--ed-ink-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-file-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ed-ink-soft);
}

.lb-file-dot {
  color: var(--ed-ink-faint);
}

.lb-file-uploader {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-file-time {
  font-size: 11.5px;
  color: var(--ed-ink-faint);
}

/* 操作区 */
.lb-file-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  border-top: 1px solid var(--ed-rule-soft);
  padding-top: 8px;
  margin-top: 2px;
  min-height: 32px;
}

.lb-file-action {
  appearance: none;
  border: none;
  background: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--ed-font-text);
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--ed-ink-soft);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--ed-radius-sm);
  transition: color 160ms ease, background 160ms ease;
  white-space: nowrap;
}

.lb-file-action:hover {
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-file-action.is-primary {
  color: var(--ed-accent-ink);
  font-weight: 600;
}

.lb-file-action.is-danger {
  color: var(--app-negative, #d64545);
}

.lb-file-action.is-danger:hover {
  background: color-mix(in srgb, var(--app-negative, #d64545) 12%, transparent);
}

@media (prefers-reduced-motion: reduce) {
  .lb-file-card,
  .lb-file-thumb img,
  .lb-file-action,
  .lb-file-storage-fill {
    transition: none;
  }
  .lb-file-card:hover {
    transform: none;
  }
  .lb-file-card:hover .lb-file-thumb img {
    transform: none;
  }
}
</style>
