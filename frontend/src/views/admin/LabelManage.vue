<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createLabel, deleteLabel, listLabels, updateLabel } from '@/api/admin'
import { ApiError } from '@/api/request'
import { formatDateTime } from '@/utils/format'
import type { LabelDTO, LabelPayload } from '@/types/api'

/**
 * 标签管理（§5.3 LabelController，label.manage）：
 * GET /labels 公开全量 + POST/PATCH/DELETE /admin/labels。
 * 形态：标签云 chip + 标签列表卡网格 —— 每个标签是一枚可悬停出「编辑/删除」的 pill 卡。
 */

const records = ref<LabelDTO[]>([])
const loading = ref(false)
const errorMessage = ref('')

const showModal = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const name = ref('')
const nameError = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    records.value = await listLabels()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
    records.value = []
  } finally {
    loading.value = false
  }
}

/** LabelDTO 未声明 createdAt（后端标签实体直出），有则格式化，无则占位 */
function labelCreatedAt(row: LabelDTO): string {
  const value = (row as LabelDTO & { createdAt?: string }).createdAt
  return value ? formatDateTime(value) : '—'
}

function openCreate(): void {
  editingId.value = null
  name.value = ''
  nameError.value = ''
  showModal.value = true
}

function openEdit(row: LabelDTO): void {
  editingId.value = row.id
  name.value = row.name
  nameError.value = ''
  showModal.value = true
}

function closeModal(): void {
  showModal.value = false
  editingId.value = null
}

async function submitForm(): Promise<void> {
  const trimmed = name.value.trim()
  if (trimmed.length === 0) {
    nameError.value = '标签名不能为空'
    return
  }
  if (trimmed.length > 30) {
    nameError.value = '标签名不能超过 30 个字符'
    return
  }
  nameError.value = ''
  submitting.value = true
  const payload: LabelPayload = { name: trimmed }
  try {
    if (editingId.value === null) {
      await createLabel(payload)
      ElMessage.success('标签已创建')
    } else {
      await updateLabel(editingId.value, payload)
      ElMessage.success('标签已保存')
    }
    closeModal()
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function confirmRemove(row: LabelDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除标签「${row.name}」吗？文章上的该标签将同步移除。`, '删除标签', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteLabel(row.id)
    ElMessage.success('标签已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="lb-lb">
    <div class="lb-page-header">
      <div>
        <h2>标签管理</h2>
        <span class="lb-muted">文章与图片的标签维护（label.manage）</span>
      </div>
      <el-button type="primary" data-testid="label-create" @click="openCreate">新建标签</el-button>
    </div>

    <el-alert v-if="errorMessage" type="warning" style="margin-bottom: 12px" :closable="false">
      {{ errorMessage }}
      <el-button link size="small" @click="load">重试</el-button>
    </el-alert>

    <!-- 标签云（只读、指向列表编辑） -->
    <section v-if="!loading && records.length > 0" class="lb-lb-cloud">
      <p class="lb-lb-cloud-label lb-ed-caption">标签云</p>
      <div class="lb-lb-cloud-items">
        <span v-for="row in records" :key="`cloud-${row.id}`" class="lb-lb-chip">#{{ row.name }}</span>
      </div>
    </section>

    <!-- 加载骨架 -->
    <div v-if="loading" class="lb-lb-grid">
      <div v-for="n in 6" :key="n" class="lb-ed-skel lb-lb-skel" />
    </div>

    <!-- 空态 -->
    <div v-else-if="records.length === 0" class="lb-ed-empty lb-ed-card lb-lb-empty">
      <p>还没有标签</p>
      <span class="lb-ed-caption">点右上角「新建标签」开始</span>
    </div>

    <!-- 标签列表卡 -->
    <div v-else class="lb-lb-grid">
      <div
        v-for="row in records"
        :key="row.id"
        class="lb-lb-card"
      >
        <div class="lb-lb-card-main">
          <span class="lb-ed-dot" />
          <span class="lb-lb-name">#{{ row.name }}</span>
        </div>
        <span class="lb-lb-time lb-ed-caption">{{ labelCreatedAt(row) }}</span>
        <div class="lb-lb-actions">
          <button type="button" class="lb-lb-act lb-lb-act--edit" @click="openEdit(row)">编辑</button>
          <button type="button" class="lb-lb-act lb-lb-act--del" @click="confirmRemove(row)">删除</button>
        </div>
      </div>
    </div>

    <el-dialog
      v-model="showModal"
      :title="editingId === null ? '新建标签' : '编辑标签'"
      width="420px"
      @closed="closeModal"
    >
      <el-input
        v-model="name"
        placeholder="标签名（≤30 字符）"
        maxlength="30"
        show-word-limit
        data-testid="label-name"
        @keyup.enter="submitForm"
      />
      <div v-if="nameError" class="lb-field-error">{{ nameError }}</div>
      <template #footer>
        <el-button size="small" @click="closeModal">取消</el-button>
        <el-button size="small" type="primary" :loading="submitting" data-testid="label-save" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lb-lb-cloud {
  margin-bottom: 20px;
}
.lb-lb-cloud-label {
  margin: 0 0 10px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.lb-lb-cloud-items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.lb-lb-chip {
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-caption);
  font-weight: 600;
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
  padding: 5px 12px;
  border-radius: var(--ed-radius-pill);
}

.lb-lb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 14px;
}
.lb-lb-skel {
  height: 92px;
  border-radius: var(--ed-radius-md);
}

.lb-lb-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lb-lb-empty p {
  margin: 0;
  font-weight: 600;
  color: var(--ed-ink);
}

/* 标签列表卡：白卡 + 圆角 + 阴影；hover 出操作 */
.lb-lb-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 18px;
  min-height: 92px;
  background: var(--ed-card);
  border-radius: var(--ed-radius-md);
  border: 1px solid color-mix(in srgb, var(--ed-ink) 5%, transparent);
  box-shadow: var(--ed-shadow-sm);
  transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 220ms ease, border-color 160ms ease;
}
.lb-lb-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--ed-shadow-md);
  border-color: color-mix(in srgb, var(--ed-accent) 40%, transparent);
}

.lb-lb-card-main {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.lb-lb-name {
  font-size: var(--ed-step-body);
  font-weight: 600;
  color: var(--ed-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.lb-lb-time {
  color: var(--ed-ink-faint);
}

.lb-lb-actions {
  display: flex;
  gap: 8px;
  opacity: 0;
  transition: opacity 160ms ease;
}
.lb-lb-card:hover .lb-lb-actions,
.lb-lb-card:focus-within .lb-lb-actions {
  opacity: 1;
}

.lb-lb-act {
  border: 0;
  background: transparent;
  cursor: pointer;
  padding: 2px 0;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-caption);
  font-weight: 500;
  transition: opacity 160ms ease;
}
.lb-lb-act:hover {
  opacity: 0.65;
}
.lb-lb-act--edit {
  color: var(--ed-accent-ink);
}
.lb-lb-act--del {
  color: var(--app-negative, #c0392b);
}

.lb-field-error {
  color: var(--app-negative);
  font-size: 12px;
  margin-top: 6px;
}

/* 触屏设备：始终显示操作（无 hover） */
@media (hover: none) {
  .lb-lb-actions {
    opacity: 1;
  }
}
</style>
