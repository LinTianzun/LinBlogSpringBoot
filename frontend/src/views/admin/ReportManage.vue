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
 * 形态：举报工单卡 —— 每条举报一张卡，卡片左上 checkbox 勾选→顶部批量处理。
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

/** checkbox 勾选 ↔ id 列表 */
function isChecked(id: number): boolean {
  return checkedRowKeys.value.includes(id)
}

function toggleCheck(id: number, checked: boolean): void {
  if (checked) {
    if (!checkedRowKeys.value.includes(id)) checkedRowKeys.value = [...checkedRowKeys.value, id]
  } else {
    checkedRowKeys.value = checkedRowKeys.value.filter((key) => key !== id)
  }
}

const allChecked = computed(
  () => records.value.length > 0 && records.value.every((row) => checkedRowKeys.value.includes(row.id)),
)

function toggleAll(checked: boolean): void {
  checkedRowKeys.value = checked ? records.value.map((row) => row.id) : []
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
  <div class="lb-rp">
    <div class="lb-page-header">
      <div>
        <h2>举报处理</h2>
        <span class="lb-muted">评论举报工作台</span>
      </div>
    </div>

    <!-- 工具栏 -->
    <div class="lb-rp-bar">
      <el-select
        :model-value="status"
        style="width: 160px"
        @update:model-value="(value: ReportStatus) => pickStatus(value)"
      >
        <el-option v-for="option in STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>

      <label class="lb-rp-checkall">
        <el-checkbox
          :model-value="allChecked"
          :disabled="records.length === 0"
          @update:model-value="(v: any) => toggleAll(Boolean(v))"
        />
        <span class="lb-ed-caption">全选本页</span>
      </label>

      <span class="lb-rp-bar-spacer" />

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

    <!-- 加载骨架 -->
    <div v-if="loading" class="lb-rp-grid">
      <div v-for="n in 4" :key="n" class="lb-rp-card lb-ed-card">
        <div class="lb-ed-skel" style="width: 70%; height: 15px" />
        <div class="lb-ed-skel" style="width: 94%; height: 12px; margin-top: 12px" />
        <div class="lb-ed-skel" style="width: 52%; height: 12px; margin-top: 8px" />
      </div>
    </div>

    <!-- 空态 -->
    <div v-else-if="records.length === 0" class="lb-ed-empty lb-ed-card lb-rp-empty">
      <p>暂无{{ STATUS_LABEL[status] }}的举报</p>
      <span class="lb-ed-caption">切换到其它状态，或等待新的举报</span>
    </div>

    <!-- 举报工单卡 -->
    <div v-else class="lb-rp-grid">
      <article
        v-for="row in records"
        :key="row.id"
        class="lb-rp-card lb-ed-card"
        :class="{ 'is-checked': isChecked(row.id) }"
      >
        <div class="lb-rp-card-head">
          <el-checkbox
            :model-value="isChecked(row.id)"
            class="lb-rp-card-check"
            @update:model-value="(v: any) => toggleCheck(row.id, Boolean(v))"
          />
          <span class="lb-ed-pill lb-ed-pill--soft">#{{ row.id }}</span>
          <el-tag size="small" effect="plain" :type="STATUS_TAG_TYPE[row.status as ReportStatus]">
            {{ STATUS_LABEL[row.status as ReportStatus] }}
          </el-tag>
          <span class="lb-rp-time lb-ed-caption">{{ formatDateTime(row.createdAt) }}</span>
        </div>

        <!-- 被举报评论 -->
        <div class="lb-rp-quote">
          <div class="lb-rp-quote-meta lb-ed-caption">
            <span>被举报评论 #{{ row.commentId }}</span>
            <span>· {{ row.commentAuthorName ?? '—' }}</span>
            <el-tag size="small" effect="plain">{{ COMMENT_STATUS_LABEL[row.commentStatus ?? ''] ?? (row.commentStatus || '—') }}</el-tag>
          </div>
          <p class="lb-rp-quote-body">{{ row.commentBody ?? '（原文已删除）' }}</p>
        </div>

        <dl class="lb-rp-fields">
          <div>
            <dt>举报原因</dt>
            <dd>{{ row.detail ? `${row.reason}（${row.detail}）` : row.reason }}</dd>
          </div>
          <div>
            <dt>举报人</dt>
            <dd>{{ row.reporterName ?? '—' }}</dd>
          </div>
          <div v-if="row.status !== 'pending'">
            <dt>处理结果</dt>
            <dd>{{ (row.handledByName ?? '—') + (row.handleNote ? `：${row.handleNote}` : '') }}</dd>
          </div>
        </dl>

        <div class="lb-rp-card-foot">
          <el-button
            v-if="row.status === 'pending'"
            type="primary"
            size="small"
            @click="openHandle(row)"
          >
            处理
          </el-button>
          <span v-else class="lb-ed-caption">已处理</span>
        </div>
      </article>
    </div>

    <div class="lb-manage-pagination">
      <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
    </div>

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
.lb-rp-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.lb-rp-checkall {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.lb-rp-bar-spacer {
  flex: 1;
}

.lb-rp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

.lb-rp-card {
  padding: clamp(16px, 2vw, 20px);
  overflow: visible;
  border: 1px solid transparent;
}
.lb-rp-card.is-checked {
  border-color: var(--ed-accent);
  box-shadow: 0 0 0 1px var(--ed-accent-soft), var(--ed-shadow-md);
}

.lb-rp-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.lb-rp-card-check {
  margin-right: 2px;
}

.lb-rp-time {
  margin-left: auto;
}

.lb-rp-quote {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: var(--ed-radius-sm);
  background: color-mix(in srgb, var(--ed-ink) 5%, var(--ed-paper));
}

.lb-rp-quote-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.lb-rp-quote-body {
  margin: 8px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ed-ink);
  line-height: var(--ed-leading-body);
}

.lb-rp-fields {
  margin: 14px 0 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.lb-rp-fields > div {
  display: flex;
  gap: 10px;
  font-size: var(--ed-step-small);
}
.lb-rp-fields dt {
  flex: none;
  width: 64px;
  color: var(--ed-ink-soft);
}
.lb-rp-fields dd {
  margin: 0;
  color: var(--ed-ink);
  word-break: break-word;
}

.lb-rp-card-foot {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.lb-rp-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lb-rp-empty p {
  margin: 0;
  font-weight: 600;
  color: var(--ed-ink);
}

.lb-report-quote {
  padding: 10px 12px;
  border-radius: var(--ed-radius-sm);
  background: color-mix(in srgb, var(--ed-ink) 5%, var(--ed-paper));
}

.lb-report-body {
  margin: 6px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ed-ink);
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

@media (max-width: 640px) {
  .lb-rp-grid {
    grid-template-columns: 1fr;
  }
}
</style>
