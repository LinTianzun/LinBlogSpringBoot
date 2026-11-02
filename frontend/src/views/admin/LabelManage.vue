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
  <div>
    <div class="lb-page-header">
      <div>
        <h2>标签管理</h2>
        <span class="lb-muted">文章与图片的标签维护（label.manage）</span>
      </div>
      <el-button type="primary" data-testid="label-create" @click="openCreate">新建标签</el-button>
    </div>

    <el-card shadow="never">
      <el-alert v-if="errorMessage" type="warning" style="margin-bottom: 12px" :closable="false">
        {{ errorMessage }}
        <el-button link size="small" @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="records" row-key="id">
        <el-table-column label="ID" prop="id" width="80" />
        <el-table-column label="标签名" prop="name" />
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ row.createdAt ? formatDateTime(row.createdAt) : '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="openEdit(row as LabelDTO)">编辑</el-button>
            <el-button link size="small" type="danger" @click="confirmRemove(row as LabelDTO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

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
.lb-page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-field-error {
  color: var(--el-color-danger, #f56c6c);
  font-size: 12px;
  margin-top: 6px;
}
</style>
