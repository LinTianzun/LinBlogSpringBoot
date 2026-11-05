<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createSubset,
  deleteSubset,
  getSubsetItems,
  listSubsets,
  replaceSubsetItems,
  updateSubset,
} from '@/api/admin'
import { listPortalContents } from '@/api/portal'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import { formatDate } from '@/utils/format'
import type { ContentView, SubsetDTO, SubsetItemDTO } from '@/types/api'

/**
 * 合集管理（重设计蓝图「合集」，后端 SubsetAdminController /admin/subsets）：
 * 列表（viewAll|viewOwn gate）+ 新建/编辑 + 条目管理（PUT /{id}/items 全量替换定序）。
 * 条目选择自已发布的文章/图片（admin/contents state=published），上下移排序。
 * 形态：合集卡片网格 —— 每个合集一张卡（名称/描述/创建时间/操作）。
 */

const records = ref<SubsetDTO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const errorMessage = ref('')

// 编辑弹窗
const showModal = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const form = ref<{ name: string; description: string; coverMediaId: string }>({ name: '', description: '', coverMediaId: '' })
const formErrors = ref<Record<string, string>>({})

// 条目管理抽屉
const showItems = ref(false)
const itemsSubset = ref<SubsetDTO | null>(null)
const itemPool = ref<ContentView[]>([])
const itemsLoading = ref(false)
const ordered = ref<Array<{ contentId: number; title: string }>>([])
const itemsError = ref('')
const savingItems = ref(false)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listSubsets({ page: page.value, pageSize })
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

function openCreate(): void {
  editingId.value = null
  form.value = { name: '', description: '', coverMediaId: '' }
  formErrors.value = {}
  showModal.value = true
}

function openEdit(row: SubsetDTO): void {
  editingId.value = row.id
  form.value = { name: row.name, description: row.description ?? '', coverMediaId: row.coverMediaId ? String(row.coverMediaId) : '' }
  formErrors.value = {}
  showModal.value = true
}

function closeModal(): void {
  showModal.value = false
  editingId.value = null
}

function validateForm(): boolean {
  const errors: Record<string, string> = {}
  if (form.value.name.trim().length === 0) errors.name = '名称不能为空'
  if (form.value.name.trim().length > 50) errors.name = '名称不能超过 50 个字符'
  if (form.value.description.length > 200) errors.description = '描述不能超过 200 个字符'
  formErrors.value = errors
  return Object.keys(errors).length === 0
}

async function submitForm(): Promise<void> {
  if (!validateForm()) return
  submitting.value = true
  const cover = form.value.coverMediaId.trim()
  const numericCover = cover.length > 0 && /^\d+$/.test(cover) ? Number(cover) : undefined
  try {
    if (editingId.value === null) {
      await createSubset({
        name: form.value.name.trim(),
        description: form.value.description.trim() || undefined,
        coverMediaId: numericCover,
        contentIds: [],
      })
      ElMessage.success('合集已创建')
    } else {
      await updateSubset(editingId.value, {
        name: form.value.name.trim(),
        description: form.value.description.trim() || undefined,
        coverMediaId: numericCover,
      })
      ElMessage.success('合集已保存')
    }
    closeModal()
    await load()
  } catch (error) {
    if (error instanceof ApiError && error.fieldErrors && Object.keys(error.fieldErrors).length > 0) {
      const mapped: Record<string, string> = {}
      for (const item of error.fieldErrors) mapped[item.field] = item.message
      formErrors.value = mapped
    } else {
      ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
    }
  } finally {
    submitting.value = false
  }
}

async function confirmRemove(row: SubsetDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除合集「${row.name}」吗？`, '删除合集', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteSubset(row.id)
    ElMessage.success('合集已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

async function openItems(row: SubsetDTO): Promise<void> {
  itemsSubset.value = row
  showItems.value = true
  itemsError.value = ''
  ordered.value = []
  itemsLoading.value = true
  try {
    const [items, pool] = await Promise.all([
      getSubsetItems(row.id),
      // /portal/contents 是公开端点，天然只返回已发布内容，无需 state 过滤
      listPortalContents({ page: 1, pageSize: 50 }),
    ])
    const titleOf = new Map<number, string>(pool.records.map((content) => [content.id, content.title]))
    ordered.value = items.map((item: SubsetItemDTO) => ({
      contentId: item.contentId,
      title: titleOf.get(item.contentId) ?? `内容 #${item.contentId}`,
    }))
    itemPool.value = pool.records
  } catch (error) {
    itemsError.value = error instanceof ApiError ? error.message : '加载条目失败'
  } finally {
    itemsLoading.value = false
  }
}

const candidateOptions = computed(() =>
  itemPool.value
    .filter((content) => !ordered.value.some((entry) => entry.contentId === content.id))
    .map((content) => ({ label: content.title, value: content.id })),
)

function addCandidate(contentId: number): void {
  const content = itemPool.value.find((item) => item.id === contentId)
  if (!content) return
  ordered.value = [...ordered.value, { contentId: content.id, title: content.title }]
}

const candidateValue = ref<number | null>(null)

function onPickCandidate(value: number | null): void {
  if (value !== null) addCandidate(value)
  candidateValue.value = null
}

function move(index: number, delta: -1 | 1): void {
  const target = index + delta
  if (target < 0 || target >= ordered.value.length) return
  const next = [...ordered.value]
  const [entry] = next.splice(index, 1)
  next.splice(target, 0, entry)
  ordered.value = next
}

function removeAt(index: number): void {
  ordered.value = ordered.value.filter((_, position) => position !== index)
}

async function saveItems(): Promise<void> {
  if (!itemsSubset.value) return
  savingItems.value = true
  try {
    await replaceSubsetItems(itemsSubset.value.id, { contentIds: ordered.value.map((entry) => entry.contentId) })
    ElMessage.success('条目顺序已保存')
    showItems.value = false
    await load()
  } catch (error) {
    itemsError.value = error instanceof ApiError ? error.message : '保存失败'
  } finally {
    savingItems.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="lb-sub">
    <div class="lb-page-header">
      <div>
        <h2>合集管理</h2>
        <span class="lb-muted">文章/图片合集与条目排序</span>
      </div>
      <el-button type="primary" data-testid="subset-create" @click="openCreate">新建合集</el-button>
    </div>

    <el-alert v-if="errorMessage" type="warning" style="margin-bottom: 12px" :closable="false">
      {{ errorMessage }}
      <el-button link size="small" @click="load">重试</el-button>
    </el-alert>

    <!-- 加载骨架 -->
    <div v-if="loading" class="lb-sub-grid">
      <div v-for="n in 4" :key="n" class="lb-sub-card lb-ed-card">
        <div class="lb-ed-skel" style="width: 60%; height: 16px" />
        <div class="lb-ed-skel" style="width: 90%; height: 12px; margin-top: 12px" />
        <div class="lb-ed-skel" style="width: 40%; height: 12px; margin-top: 8px" />
      </div>
    </div>

    <!-- 空态 -->
    <div v-else-if="records.length === 0" class="lb-ed-empty lb-ed-card lb-sub-empty">
      <p>还没有合集</p>
      <span class="lb-ed-caption">点右上角「新建合集」把内容归类</span>
    </div>

    <!-- 合集卡网格 -->
    <div v-else class="lb-sub-grid">
      <article
        v-for="row in records"
        :key="row.id"
        class="lb-sub-card lb-ed-card"
      >
        <div class="lb-sub-card-head">
          <span class="lb-ed-pill lb-ed-pill--soft">#{{ row.id }}</span>
          <span class="lb-sub-date lb-ed-caption">{{ formatDate(row.createdAt) }}</span>
        </div>
        <h3 class="lb-sub-name">{{ row.name }}</h3>
        <p class="lb-sub-desc">{{ row.description || '暂无描述' }}</p>
        <div class="lb-sub-card-foot">
          <el-button link size="small" @click="openItems(row)">条目</el-button>
          <el-button link size="small" type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link size="small" type="danger" @click="confirmRemove(row)">删除</el-button>
        </div>
      </article>
    </div>

    <div class="lb-manage-pagination">
      <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
    </div>

    <!-- 新建/编辑合集 -->
    <el-dialog
      v-model="showModal"
      :title="editingId === null ? '新建合集' : '编辑合集'"
      width="480px"
      @closed="closeModal"
    >
      <div class="lb-form-stack">
        <el-input
          v-model="form.name"
          placeholder="合集名（≤50 字符）"
          maxlength="50"
          data-testid="subset-name"
        />
        <div v-if="formErrors.name" class="lb-field-error">{{ formErrors.name }}</div>
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="描述（≤200 字符，选填）"
        />
        <div v-if="formErrors.description" class="lb-field-error">{{ formErrors.description }}</div>
        <el-input v-model="form.coverMediaId" placeholder="封面 mediaId（数字，选填）" />
      </div>
      <template #footer>
        <el-button size="small" @click="closeModal">取消</el-button>
        <el-button size="small" type="primary" :loading="submitting" data-testid="subset-save" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 条目管理 -->
    <el-drawer v-model="showItems" :size="520" :title="`条目管理 · ${itemsSubset?.name ?? ''}`">
      <div v-loading="itemsLoading">
        <el-alert v-if="itemsError" type="warning" style="margin-bottom: 12px" :closable="false">
          {{ itemsError }}
        </el-alert>

        <el-select
          :model-value="candidateValue"
          filterable
          clearable
          placeholder="添加已发布内容到合集…"
          style="width: 100%; margin-bottom: 12px"
          data-testid="subset-item-add"
          @update:model-value="onPickCandidate"
        >
          <el-option v-for="option in candidateOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>

        <el-empty v-if="ordered.length === 0" description="暂无条目，从上方添加" :image-size="60" />

        <div v-for="(entry, index) in ordered" :key="entry.contentId" class="lb-subset-item" data-testid="subset-item-row">
          <span class="lb-subset-order">{{ index + 1 }}</span>
          <span class="lb-subset-title">{{ entry.title }}</span>
          <el-button size="small" :disabled="index === 0" @click="move(index, -1)">↑</el-button>
          <el-button size="small" :disabled="index === ordered.length - 1" @click="move(index, 1)">↓</el-button>
          <el-button size="small" type="danger" plain @click="removeAt(index)">移除</el-button>
        </div>
      </div>

      <template #footer>
        <el-button size="small" @click="showItems = false">取消</el-button>
        <el-button size="small" type="primary" :loading="savingItems" data-testid="subset-items-save" @click="saveItems">
          保存顺序
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.lb-sub-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.lb-sub-card {
  padding: clamp(16px, 2vw, 20px);
  overflow: visible;
  gap: 0;
}

.lb-sub-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.lb-sub-date {
  color: var(--ed-ink-faint);
}

.lb-sub-name {
  margin: 12px 0 0;
  font-family: var(--ed-font-display);
  font-size: var(--ed-step-h3);
  font-weight: 500;
  line-height: 1.28;
  color: var(--ed-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-sub-desc {
  margin: 8px 0 0;
  color: var(--ed-ink-soft);
  font-size: var(--ed-step-small);
  line-height: var(--ed-leading-body);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: calc(var(--ed-step-small) * var(--ed-leading-body) * 2);
}

.lb-sub-card-foot {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--ed-rule-soft);
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.lb-sub-empty {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.lb-sub-empty p {
  margin: 0;
  font-weight: 600;
  color: var(--ed-ink);
}

/* 弹窗表单与条目排序行 */
.lb-form-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lb-subset-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-subset-order {
  width: 24px;
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}

.lb-subset-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ed-ink);
}

.lb-field-error {
  color: var(--app-negative);
  font-size: 12px;
}

@media (max-width: 640px) {
  .lb-sub-grid {
    grid-template-columns: 1fr;
  }
}
</style>
