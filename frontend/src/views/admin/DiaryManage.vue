<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createDiary, deleteDiary, listAdminDiaries, updateDiary } from '@/api/admin'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import PaginationBar from '@/components/PaginationBar.vue'
import WeatherBadge from '@/components/WeatherBadge.vue'
import { WEATHER_LIST } from '@/utils/weather'
import { formatDateTime } from '@/utils/format'
import type { ContentState, DiaryDTO, Weather } from '@/types/api'

/**
 * 日记管理（§5.4 DiaryAdminController）：列表（viewAll/viewOwn 自动收窄）+ state 过滤 +
 * 新建/编辑（diary.create；PATCH null 字段不更新，state 可经 PATCH 迁移）+ 删除。
 */

const STATE_OPTIONS: Array<{ label: string; value: ContentState | 'all' }> = [
  { label: '全部', value: 'all' },
  { label: '草稿', value: 'draft' },
  { label: '已发布', value: 'published' },
]

const STATE_LABEL: Record<ContentState, string> = {
  draft: '草稿',
  published: '已发布',
  hidden: '已隐藏',
  deleted: '已删除',
}

const STATE_TAG_TYPE: Record<ContentState, 'info' | 'success' | 'warning' | 'danger'> = {
  draft: 'info',
  published: 'success',
  hidden: 'warning',
  deleted: 'danger',
}

const WEATHER_OPTIONS: Array<{ label: string; value: Weather }> = WEATHER_LIST.map((w) => ({ label: w, value: w }))

const session = useSessionStore()

const records = ref<DiaryDTO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const state = ref<ContentState | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const canCreate = computed(() => session.can('diary.create'))
const canManage = computed(() => session.can('diary.manageAll') || session.can('diary.manageOwn'))

/** 编辑弹窗：editingId=null 表示新建 */
const showModal = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)
const form = reactive<{ diaryDate: string; weather: Weather | null; mood: string; body: string; state: ContentState }>({
  diaryDate: '',
  weather: null,
  mood: '',
  body: '',
  state: 'draft',
})
const fieldErrors = ref<Record<string, string>>({})

const query = computed(() => ({
  page: page.value,
  pageSize,
  state: state.value ?? undefined,
}))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminDiaries(query.value)
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

function pickState(value: ContentState | 'all'): void {
  page.value = 1
  state.value = value === 'all' ? null : value
  void load()
}

function openCreate(): void {
  editingId.value = null
  form.diaryDate = new Date().toISOString().slice(0, 10)
  form.weather = null
  form.mood = ''
  form.body = ''
  form.state = 'draft'
  fieldErrors.value = {}
  showModal.value = true
}

function openEdit(row: DiaryDTO): void {
  editingId.value = row.id
  form.diaryDate = row.diaryDate
  form.weather = row.weather ?? null
  form.mood = row.mood ?? ''
  form.body = row.body
  form.state = row.state
  fieldErrors.value = {}
  showModal.value = true
}

function closeModal(): void {
  showModal.value = false
  editingId.value = null
}

async function submitForm(): Promise<void> {
  fieldErrors.value = {}
  if (form.diaryDate.length === 0) {
    fieldErrors.value.diaryDate = '请选择日记日期'
  }
  if (form.body.trim().length === 0) {
    fieldErrors.value.body = '正文不能为空'
  }
  if (Object.keys(fieldErrors.value).length > 0) return
  submitting.value = true
  const payload = {
    diaryDate: form.diaryDate,
    weather: form.weather ?? undefined,
    mood: form.mood.trim() || undefined,
    body: form.body,
    state: form.state,
  }
  try {
    if (editingId.value === null) {
      await createDiary(payload)
      ElMessage.success('日记已创建')
    } else {
      await updateDiary(editingId.value, payload)
      ElMessage.success('日记已保存')
    }
    closeModal()
    await load()
  } catch (error) {
    if (error instanceof ApiError) {
      const list = error.fieldErrors ?? []
      if (list.length > 0) {
        const map: Record<string, string> = {}
        for (const item of list) map[item.field] = item.message
        fieldErrors.value = map
      } else {
        ElMessage.error(error.message)
      }
    } else {
      ElMessage.error('保存失败')
    }
  } finally {
    submitting.value = false
  }
}

async function confirmRemove(row: DiaryDTO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除 ${row.diaryDate} 的日记吗？`, '删除日记', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteDiary(row.id)
    ElMessage.success('日记已删除')
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
      <h2>日记管理</h2>
      <el-button v-if="canCreate" type="primary" @click="openCreate">写日记</el-button>
    </div>

    <el-card shadow="never">
      <div class="lb-manage-filters">
        <el-select :model-value="state ?? 'all'" style="width: 140px" @update:model-value="pickState">
          <el-option v-for="option in STATE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </div>

      <el-alert v-if="errorMessage" type="warning" :title="`${errorMessage}（服务暂未就绪）`" class="lb-load-alert" :closable="false">
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="records" row-key="id">
        <el-table-column label="日期" prop="diaryDate" width="110" />
        <el-table-column label="天气" width="90">
          <template #default="{ row }">
            <WeatherBadge :weather="row.weather ?? null" />
          </template>
        </el-table-column>
        <el-table-column label="心情" width="120">
          <template #default="{ row }">{{ row.mood ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="正文摘要" show-overflow-tooltip>
          <template #default="{ row }">{{ row.body.slice(0, 60) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="STATE_TAG_TYPE[row.state as ContentState]">
              {{ STATE_LABEL[row.state as ContentState] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="赞" width="70">
          <template #default="{ row }">{{ row.praises ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ row.updatedAt ? formatDateTime(row.updatedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button v-if="canManage" link size="small" @click="openEdit(row as DiaryDTO)">编辑</el-button>
            <el-button link type="danger" size="small" @click="confirmRemove(row as DiaryDTO)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
      </div>
    </el-card>

    <!-- 新建/编辑 -->
    <el-dialog
      v-model="showModal"
      width="560px"
      :title="editingId === null ? '写日记' : '编辑日记'"
      @update:model-value="(show: boolean) => { if (!show) closeModal() }"
    >
      <div class="lb-form-row">
        <span class="lb-form-label">日期</span>
        <el-input v-model="form.diaryDate" placeholder="YYYY-MM-DD" style="width: 160px" />
      </div>
      <div v-if="fieldErrors.diaryDate" class="lb-field-error">{{ fieldErrors.diaryDate }}</div>
      <div class="lb-form-row">
        <span class="lb-form-label">天气</span>
        <el-select v-model="form.weather" clearable placeholder="选择天气" style="width: 160px">
          <el-option v-for="option in WEATHER_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <span class="lb-form-label">心情</span>
        <el-input v-model="form.mood" placeholder="心情（选填）" style="width: 200px" />
      </div>
      <div v-if="editingId !== null" class="lb-form-row">
        <span class="lb-form-label">状态</span>
        <el-select v-model="form.state" style="width: 140px">
          <el-option label="草稿" value="draft" />
          <el-option label="已发布" value="published" />
        </el-select>
      </div>
      <el-input
        v-model="form.body"
        type="textarea"
        :rows="6"
        maxlength="20000"
        show-word-limit
        placeholder="今天……"
      />
      <div v-if="fieldErrors.body" class="lb-field-error">{{ fieldErrors.body }}</div>
      <template #footer>
        <div class="lb-dialog-actions">
          <el-button size="small" @click="closeModal">取消</el-button>
          <el-button size="small" type="primary" :loading="submitting" @click="submitForm">保存</el-button>
        </div>
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

.lb-form-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.lb-form-label {
  font-size: 13px;
  color: var(--lb-muted);
  white-space: nowrap;
}

.lb-field-error {
  color: var(--lb-danger);
  font-size: 12px;
  margin-bottom: 10px;
}

.lb-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
