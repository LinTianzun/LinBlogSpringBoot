<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions, UploadProps } from 'element-plus'
import { getStorageStatus, listAdminFiles, purgeFile, restoreFile, trashFile, uploadMedia } from '@/api/admin'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import { mediaUrl } from '@/utils/media'
import { formatBytes, formatDateTime } from '@/utils/format'
import type { FileAdminViewDTO, StorageStatusDTO } from '@/types/api'

/**
 * 文件管理（§5.7 FileAdminController，file.view）：
 * 上传（POST /files/upload）+ 列表（?state=active|trashed）+ 存储用量 + trash/restore/purge。
 */

const STATE_OPTIONS: Array<{ label: string; value: string }> = [
  { label: '主区（正常）', value: 'active' },
  { label: '备份区（回收站）', value: 'trashed' },
]

const STATE_TAG_TYPE: Record<string, 'success' | 'warning' | 'info'> = {
  active: 'success',
  trashed: 'warning',
}

const records = ref<FileAdminViewDTO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const state = ref('active')
const loading = ref(false)
const errorMessage = ref('')

const storageStatus = ref<StorageStatusDTO | null>(null)
const storageError = ref('')
const uploading = ref(false)

const isImage = (row: FileAdminViewDTO): boolean => row.mimeType.startsWith('image/')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminFiles({ page: page.value, pageSize, state: state.value })
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

const backupOver = computed(() => (storageStatus.value?.backupPercent ?? 0) >= 80)

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

    <!-- 存储用量 -->
    <el-card v-if="storageStatus" shadow="never" style="margin-bottom: 16px">
      <div class="lb-storage-row">
        <span>
          主区：{{ formatBytes(storageStatus.primaryBytes) }} / {{ storageStatus.primaryCount }} 个文件
        </span>
        <span>
          备份区：{{ formatBytes(storageStatus.backupBytes) }} / {{ storageStatus.backupCount }} 个文件
        </span>
        <el-tag :type="backupOver ? 'danger' : 'info'" size="small" effect="plain">
          备份区占用 {{ storageStatus.backupPercent.toFixed(1) }}%（配额 {{ formatBytes(storageStatus.quotaBytes) }}）
        </el-tag>
      </div>
      <el-alert v-if="backupOver" type="error" style="margin-top: 10px" :closable="false">
        备份区占用已超过 80%，请清理回收站（彻底删除）。
      </el-alert>
    </el-card>
    <el-alert v-else-if="storageError" type="warning" style="margin-bottom: 16px" :closable="false">
      {{ storageError }}
    </el-alert>

    <el-card shadow="never">
      <div class="lb-manage-filters">
        <el-select :model-value="state" style="width: 180px" @update:model-value="pickState">
          <el-option v-for="option in STATE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-upload
          :http-request="uploadFile"
          :before-upload="beforeUpload"
          :show-file-list="false"
          :disabled="uploading"
          accept="image/*,application/pdf"
          data-testid="file-upload"
        >
          <el-button type="primary" size="small" :loading="uploading" data-testid="file-upload-button">上传文件</el-button>
        </el-upload>
      </div>

      <el-alert v-if="errorMessage" type="warning" style="margin: 12px 0" :closable="false">
        {{ errorMessage }}
        <el-button link size="small" @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="records" row-key="id">
        <el-table-column label="预览" width="80">
          <template #default="{ row }">
            <el-image v-if="isImage(row as FileAdminViewDTO)" :src="mediaUrl(row.storageKey, 320)" fit="cover" lazy style="width: 56px; height: 56px" />
            <span v-else class="lb-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="文件名" prop="originalName" show-overflow-tooltip />
        <el-table-column label="storageKey" width="220" show-overflow-tooltip>
          <template #default="{ row }"><code class="lb-file-key">{{ row.storageKey }}</code></template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="上传者" width="110">
          <template #default="{ row }">{{ row.uploaderName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="STATE_TAG_TYPE[row.state] ?? 'info'" size="small" effect="plain">
              {{ row.state === 'active' ? '主区' : '备份区' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <template v-if="row.state === 'active'">
              <el-button link size="small" type="warning" @click="confirmTrash(row as FileAdminViewDTO)">移入备份区</el-button>
            </template>
            <template v-else>
              <el-button link size="small" type="primary" @click="doRestore(row as FileAdminViewDTO)">还原</el-button>
              <el-button link size="small" type="danger" @click="confirmPurge(row as FileAdminViewDTO)">彻底删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <div class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.lb-page-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-storage-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 24px;
}

.lb-manage-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.lb-manage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.lb-file-key {
  font-size: 12px;
  background: rgba(128, 128, 128, 0.1);
  padding: 1px 4px;
  border-radius: 3px;
}
</style>
