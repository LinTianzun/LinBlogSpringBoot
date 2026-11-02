<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { batchHandleCommentReports, handleCommentReport, listCommentReports } from '@/api/admin'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import { formatDateTime } from '@/utils/format'
import type { ReportAction, ReportStatus, ReportViewDTO } from '@/types/api'

/**
 * 评论举报工作台（§5.5 CommentReportAdminController，comment.moderate）：
 * 状态过滤（缺省 pending）+ 单条处理（{action, note≤200}）+ 批量处理（{ids≤200, action}）。
 */

const STATUS_OPTIONS: Array<{ label: string; value: ReportStatus }> = [
  { label: '待处理', value: 'pending' },
  { label: '已解决', value: 'resolved' },
  { label: '已驳回', value: 'dismissed' },
]

const STATUS_LABEL: Record<ReportStatus, string> = {
  pending: '待处理',
  resolved: '已解决',
  dismissed: '已驳回',
}

const STATUS_TAG_TYPE: Record<ReportStatus, 'warning' | 'success' | 'info'> = {
  pending: 'warning',
  resolved: 'success',
  dismissed: 'info',
}

const COMMENT_STATUS_LABEL: Record<string, string> = {
  visible: '可见',
  deleted: '已删除',
}

const records = ref<ReportViewDTO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const status = ref<ReportStatus>('pending')
const loading = ref(false)
const errorMessage = ref('')

const checkedRowKeys = ref<Array<number>>([])

/** 单条处理弹窗 */
const handleTarget = ref<ReportViewDTO | null>(null)
const handleAction = ref<ReportAction>('resolved')
const handleNote = ref('')
const handleSubmitting = ref(false)

const batchSubmitting = ref(false)

const query = computed(() => ({
  page: page.value,
  pageSize,
  status: status.value,
}))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listCommentReports(query.value)
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

function onSelectionChange(rows: ReportViewDTO[]): void {
  checkedRowKeys.value = rows.map((row) => row.id)
}

function pickStatus(value: ReportStatus): void {
  page.value = 1
  checkedRowKeys.value = []
  status.value = value
  void load()
}

function openHandle(row: ReportViewDTO): void {
  handleTarget.value = row
  handleAction.value = 'resolved'
  handleNote.value = ''
}

function cancelHandle(): void {
  handleTarget.value = null
  handleNote.value = ''
}

async function submitHandle(): Promise<void> {
  if (!handleTarget.value) return
  handleSubmitting.value = true
  try {
    await handleCommentReport(handleTarget.value.id, {
      action: handleAction.value,
      note: handleNote.value.trim() || undefined,
    })
    ElMessage.success(`举报 #${handleTarget.value.id} 已${STATUS_LABEL[handleAction.value]}`)
    cancelHandle()
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '处理失败')
  } finally {
    handleSubmitting.value = false
  }
}

async function confirmBatch(action: ReportAction): Promise<void> {
  const ids = [...checkedRowKeys.value]
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(
      `确定将选中的 ${ids.length} 条举报标记为「${STATUS_LABEL[action]}」吗？`,
      '批量处理',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  batchSubmitting.value = true
  try {
    const result = await batchHandleCommentReports({ ids, action })
    ElMessage.success(`已更新 ${result.updated} 条，跳过 ${result.skipped} 条`)
    checkedRowKeys.value = []
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '批量处理失败')
  } finally {
    batchSubmitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="lb-page-header">
      <h2>举报处理</h2>
      <span class="lb-muted">评论举报工作台</span>
    </div>

    <el-card shadow="never">
      <div class="lb-manage-filters">
        <el-select
          :model-value="status"
          style="width: 160px"
          @update:model-value="(value: ReportStatus) => pickStatus(value)"
        >
          <el-option v-for="option in STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button
          size="small"
          :disabled="checkedRowKeys.length === 0"
          :loading="batchSubmitting"
          @click="confirmBatch('resolved')"
        >
          批量标记已解决（{{ checkedRowKeys.length }}）
        </el-button>
        <el-button
          size="small"
          :disabled="checkedRowKeys.length === 0"
          :loading="batchSubmitting"
          @click="confirmBatch('dismissed')"
        >
          批量驳回（{{ checkedRowKeys.length }}）
        </el-button>
      </div>

      <el-alert v-if="errorMessage" type="warning" :title="errorMessage" class="lb-load-alert" :closable="false">
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="records" row-key="id" @selection-change="onSelectionChange">
        <el-table-column type="selection" width="44" />
        <el-table-column label="被举报评论" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.commentBody ?? '（原文已删除）' }}</template>
        </el-table-column>
        <el-table-column label="评论作者" width="110">
          <template #default="{ row }">{{ row.commentAuthorName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="评论状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ COMMENT_STATUS_LABEL[row.commentStatus ?? ''] ?? (row.commentStatus || '—') }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="举报人" width="110">
          <template #default="{ row }">{{ row.reporterName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="原因" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.detail ? `${row.reason}（${row.detail}）` : row.reason }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="STATUS_TAG_TYPE[row.status as ReportStatus]">
              {{ STATUS_LABEL[row.status as ReportStatus] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="举报时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="处理结果" width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <template v-if="row.status !== 'pending'">
              {{ (row.handledByName ?? '—') + (row.handleNote ? `：${row.handleNote}` : '') }}
            </template>
            <template v-else>—</template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button v-if="row.status === 'pending'" link type="primary" size="small" @click="openHandle(row as ReportViewDTO)">处理</el-button>
            <span v-else class="lb-muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
      </div>
    </el-card>

    <!-- 单条处理 -->
    <el-dialog
      :model-value="handleTarget !== null"
      title="处理举报"
      width="480px"
      @update:model-value="(show: boolean) => { if (!show) cancelHandle() }"
    >
      <div class="lb-report-quote">
        <div class="lb-muted">被举报评论（#{{ handleTarget?.commentId }}）</div>
        <p class="lb-report-body">{{ handleTarget?.commentBody ?? '（原文已删除）' }}</p>
      </div>
      <el-radio-group v-model="handleAction" class="lb-handle-actions">
        <el-radio value="resolved">标记已解决</el-radio>
        <el-radio value="dismissed">驳回举报</el-radio>
      </el-radio-group>
      <el-input
        v-model="handleNote"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="处理备注（选填，≤200 字）"
      />
      <template #footer>
        <div class="lb-dialog-actions">
          <el-button size="small" @click="cancelHandle">取消</el-button>
          <el-button size="small" type="primary" :loading="handleSubmitting" @click="submitHandle">提交</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lb-page-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-manage-filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.lb-load-alert {
  margin: 0 0 12px;
}

.lb-manage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.lb-report-quote {
  padding: 10px 12px;
  border-radius: 4px;
  background: var(--art-bg-hover, rgba(128, 128, 128, 0.08));
}

.lb-report-body {
  margin: 6px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.lb-handle-actions {
  display: flex;
  margin: 14px 0;
}

.lb-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
