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

      <!-- 加载骨架 -->
      <div v-if="loading" class="lb-diary-timeline">
        <div v-for="n in 3" :key="`skel-${n}`" class="lb-diary-item">
          <div class="lb-diary-rail">
            <span class="lb-diary-dot lb-ed-skel" />
          </div>
          <div class="lb-diary-card">
            <div class="lb-ed-skel" style="height: 18px; width: 40%" />
            <div class="lb-ed-skel" style="height: 13px; width: 90%; margin-top: 12px" />
            <div class="lb-ed-skel" style="height: 13px; width: 65%; margin-top: 8px" />
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <div v-else-if="records.length === 0 && !errorMessage" class="lb-ed-empty">
        暂无日记{{ canCreate ? '，点击右上角「写日记」记录今天。' : '。' }}
      </div>

      <!-- 日记时间轴卡片 -->
      <div v-else class="lb-diary-timeline">
        <div v-for="row in records" :key="row.id" class="lb-diary-item">
          <div class="lb-diary-rail">
            <span class="lb-diary-dot" />
          </div>

          <div class="lb-diary-card">
            <div class="lb-diary-head">
              <span class="lb-diary-date">{{ row.diaryDate }}</span>
              <WeatherBadge :weather="row.weather ?? null" />
              <span v-if="row.mood" class="lb-diary-mood">{{ row.mood }}</span>
              <el-tag size="small" effect="plain" :type="STATE_TAG_TYPE[row.state as ContentState]">
                {{ STATE_LABEL[row.state as ContentState] }}
              </el-tag>
            </div>

            <p class="lb-diary-body">{{ row.body.slice(0, 120) }}{{ row.body.length > 120 ? '…' : '' }}</p>

            <div class="lb-diary-foot">
              <span class="lb-diary-meta">
                <span>{{ row.praises ?? 0 }} 赞</span>
                <span v-if="row.updatedAt">{{ formatDateTime(row.updatedAt) }}</span>
              </span>
              <span class="lb-diary-actions">
                <button v-if="canManage" type="button" class="lb-diary-action" @click="openEdit(row)">编辑</button>
                <button type="button" class="lb-diary-action is-danger" @click="confirmRemove(row)">删除</button>
              </span>
            </div>
          </div>
        </div>
      </div>

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
/* 页头/工具栏/分页由 AdminLayout 设计层统一；此处保留日记时间轴样式与弹窗内表单行样式。 */
.lb-diary-timeline {
  display: flex;
  flex-direction: column;
}

.lb-diary-item {
  display: flex;
  gap: 16px;
}

.lb-diary-rail {
  flex: 0 0 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 20px;
}

.lb-diary-dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: var(--ed-accent);
  box-shadow: 0 0 0 4px var(--ed-accent-soft);
  flex: 0 0 auto;
}

/* 竖线（除最后一项） */
.lb-diary-item:not(:last-child) .lb-diary-rail::after {
  content: '';
  flex: 1;
  width: 2px;
  margin-top: 8px;
  background: var(--ed-rule-soft);
}

.lb-diary-card {
  flex: 1;
  min-width: 0;
  margin-bottom: 14px;
  padding: 16px 18px;
  background: var(--ed-card);
  border: 1px solid var(--ed-rule-soft);
  border-radius: var(--ed-radius-md);
  transition: box-shadow 220ms ease, border-color 220ms ease;
}

.lb-diary-card:hover {
  box-shadow: var(--ed-shadow-md);
  border-color: color-mix(in srgb, var(--ed-accent) 22%, var(--ed-rule-soft));
}

.lb-diary-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.lb-diary-date {
  font-family: var(--ed-font-display);
  font-size: 1.1rem;
  font-weight: 500;
  color: var(--ed-ink);
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
}

.lb-diary-mood {
  font-size: 0.8125rem;
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
  padding: 3px 10px;
  border-radius: var(--ed-radius-pill);
  font-weight: 500;
}

.lb-diary-body {
  margin: 0 0 12px;
  font-size: var(--ed-step-small);
  line-height: 1.65;
  color: var(--ed-ink);
  white-space: pre-wrap;
  word-break: break-word;
}

.lb-diary-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.lb-diary-meta {
  display: inline-flex;
  gap: 14px;
  font-size: 0.78rem;
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}

.lb-diary-actions {
  display: inline-flex;
  gap: 4px;
}

.lb-diary-action {
  appearance: none;
  border: none;
  background: none;
  font-family: var(--ed-font-text);
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--ed-ink-soft);
  cursor: pointer;
  padding: 3px 9px;
  border-radius: var(--ed-radius-sm);
  transition: color 160ms ease, background 160ms ease;
}

.lb-diary-action:hover {
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-diary-action.is-danger {
  color: var(--app-negative, #d64545);
}

.lb-diary-action.is-danger:hover {
  background: color-mix(in srgb, var(--app-negative, #d64545) 12%, transparent);
}

@media (max-width: 560px) {
  .lb-diary-rail {
    flex-basis: 12px;
  }
}

.lb-form-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.lb-form-label {
  font-size: 13px;
  color: var(--ed-ink-soft);
  white-space: nowrap;
}

.lb-field-error {
  color: var(--app-negative);
  font-size: 12px;
  margin-bottom: 10px;
}

.lb-dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
