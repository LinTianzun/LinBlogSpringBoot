<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteContent, listAdminContents, purgeContent, updateContentState } from '@/api/admin'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import PaginationBar from '@/components/PaginationBar.vue'
import { formatDate } from '@/utils/format'
import type { AdminContentQuery, ContentView, ContentState } from '@/types/api'

/**
 * 画廊管理（§5.3）：/admin/contents?contentType=image 列表 + 状态机操作 + 回收站。
 * ⚠️ 无 coverStorageKey（仅 coverMediaId）→ 预览列用占位块（待后端暴露 key）。
 */

const STATE_OPTIONS: Array<{ label: string; value: ContentState }> = [
  { label: '草稿', value: 'draft' },
  { label: '已发布', value: 'published' },
  { label: '已隐藏', value: 'hidden' },
  { label: '已删除', value: 'deleted' },
]

const STATE_TAG_TYPE: Record<ContentState, 'info' | 'success' | 'warning' | 'danger'> = {
  draft: 'info',
  published: 'success',
  hidden: 'warning',
  deleted: 'danger',
}

const session = useSessionStore()

const records = ref<ContentView[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const keyword = ref('')
const state = ref<ContentState | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const canPublish = computed(() => session.can('gallery.manageAll') || session.can('gallery.manageOwn'))
const canPurge = computed(() => session.can('gallery.manageAll'))

const query = computed<AdminContentQuery>(() => ({
  contentType: 'image',
  page: page.value,
  pageSize,
  keyword: keyword.value || undefined,
  state: state.value ?? undefined,
}))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminContents(query.value)
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

function search(): void {
  page.value = 1
  void load()
}

async function setState(row: ContentView, nextState: ContentState): Promise<void> {
  try {
    await updateContentState(row.id, nextState)
    ElMessage.success('状态已更新')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

async function remove(row: ContentView): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定将「${row.title || '未命名图片'}」移入回收站吗？`, '移入回收站', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteContent(row.id)
    ElMessage.success('已移入回收站')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

async function confirmPurge(row: ContentView): Promise<void> {
  try {
    await ElMessageBox.confirm(`「${row.title || '未命名图片'}」将被永久删除且不可恢复，确定吗？`, '彻底删除', {
      type: 'error',
      confirmButtonText: '彻底删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await purgeContent(row.id)
    ElMessage.success('已彻底删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

/** 状态机 → 每行可用操作（type: '' 表示默认按钮） */
interface RowAction {
  label: string
  type: 'primary' | '' | 'danger'
  onClick: () => void
}

function rowActions(row: ContentView): RowAction[] {
  const actions: RowAction[] = []
  if (row.state === 'draft' && canPublish.value) {
    actions.push({ label: '发布', type: 'primary', onClick: () => void setState(row, 'published') })
  }
  if (row.state === 'published' && canPublish.value) {
    actions.push({ label: '隐藏', type: '', onClick: () => void setState(row, 'hidden') })
  }
  if (row.state === 'hidden') {
    if (canPublish.value) actions.push({ label: '重新发布', type: 'primary', onClick: () => void setState(row, 'published') })
    actions.push({ label: '转草稿', type: '', onClick: () => void setState(row, 'draft') })
  }
  if (row.state === 'deleted') {
    actions.push({ label: '还原', type: 'primary', onClick: () => void setState(row, 'draft') })
    if (canPurge.value) actions.push({ label: '彻底删除', type: 'danger', onClick: () => void confirmPurge(row) })
  }
  if (row.state !== 'deleted') {
    actions.push({ label: '删除', type: 'danger', onClick: () => void remove(row) })
  }
  return actions
}

onMounted(load)
</script>

<template>
  <div>
    <div class="lb-page-header">
      <div>
        <h2>画廊管理</h2>
        <span class="lb-muted">共 {{ total }} 张图片</span>
      </div>
      <RouterLink to="/admin/gallery/edit/">
        <el-button type="primary">新建图片</el-button>
      </RouterLink>
    </div>

    <el-card shadow="never">
      <div class="lb-manage-filters">
        <el-input
          v-model="keyword"
          placeholder="搜索标题"
          clearable
          style="width: 240px"
          @keyup.enter="search"
        />
        <el-select
          v-model="state"
          placeholder="状态"
          clearable
          style="width: 140px"
          @update:model-value="search"
        >
          <el-option v-for="option in STATE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button @click="search">搜索</el-button>
      </div>

      <el-alert v-if="errorMessage" type="warning" :title="errorMessage" class="lb-load-alert" :closable="false">
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <!-- 加载骨架 -->
      <div v-if="loading" class="lb-gallery-grid">
        <div v-for="n in 8" :key="`skel-${n}`" class="lb-gallery-card">
          <div class="lb-gallery-cover lb-ed-skel" />
          <div class="lb-gallery-body">
            <div class="lb-ed-skel" style="height: 16px; width: 70%" />
            <div class="lb-ed-skel" style="height: 12px; width: 40%; margin-top: 10px" />
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <div v-else-if="records.length === 0 && !errorMessage" class="lb-ed-empty">
        暂无图片，点击右上角「新建图片」上传。
      </div>

      <!-- 图片卡片墙 -->
      <div v-else class="lb-gallery-grid">
        <div v-for="row in records" :key="row.id" class="lb-gallery-card">
          <RouterLink :to="`/admin/gallery/edit/${row.id}`" class="lb-gallery-cover">
            <img
              v-if="row.coverStorageKey"
              :src="`/api/v1/media/${row.coverStorageKey}?width=320`"
              :alt="row.title || '图片'"
              loading="lazy"
            />
            <div v-else class="lb-gallery-ph" aria-hidden="true" />
            <el-tag
              class="lb-gallery-pill"
              size="small"
              effect="dark"
              :type="STATE_TAG_TYPE[row.state as ContentState]"
            >
              {{ STATE_OPTIONS.find((item) => item.value === row.state)?.label ?? row.state }}
            </el-tag>
          </RouterLink>

          <div class="lb-gallery-body">
            <RouterLink :to="`/admin/gallery/edit/${row.id}`" class="lb-gallery-title">
              {{ row.title || '（无标题）' }}
            </RouterLink>
            <div class="lb-gallery-meta">
              <span>{{ row.publishedAt ? formatDate(row.publishedAt) : '未发布' }}</span>
              <span>{{ row.praises ?? 0 }} 赞</span>
            </div>
            <div class="lb-gallery-actions">
              <RouterLink :to="`/admin/gallery/edit/${row.id}`" class="lb-gallery-action">编辑</RouterLink>
              <button
                v-for="action in rowActions(row)"
                :key="action.label"
                type="button"
                class="lb-gallery-action"
                :class="{
                  'is-danger': action.type === 'danger',
                  'is-primary': action.type === 'primary',
                }"
                @click="action.onClick"
              >
                {{ action.label }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
/* 页头 / 工具栏 / 分页 由 AdminLayout 设计层统一；此处保留画廊卡片墙样式。 */
.lb-gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.lb-gallery-card {
  display: flex;
  flex-direction: column;
  background: var(--ed-card);
  border: 1px solid var(--ed-rule-soft);
  border-radius: var(--ed-radius-md);
  overflow: hidden;
  transition: box-shadow 220ms ease, border-color 220ms ease, transform 220ms ease;
}

.lb-gallery-card:hover {
  box-shadow: var(--ed-shadow-md);
  border-color: color-mix(in srgb, var(--ed-accent) 24%, var(--ed-rule-soft));
  transform: translateY(-3px);
}

/* 封面 */
.lb-gallery-cover {
  position: relative;
  display: block;
  aspect-ratio: 4 / 3;
  background: var(--ed-accent-soft);
  overflow: hidden;
}

.lb-gallery-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform 460ms cubic-bezier(0.22, 1, 0.36, 1);
}

.lb-gallery-card:hover .lb-gallery-cover img {
  transform: scale(1.05);
}

.lb-gallery-ph {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, var(--ed-accent-soft), color-mix(in srgb, var(--ed-accent) 6%, var(--ed-card)));
}

/* 状态药丸叠在图左上 */
.lb-gallery-pill {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 1;
  font-size: 0.72rem;
  font-weight: 600;
  line-height: 1;
  padding: 4px 10px;
  backdrop-filter: blur(4px);
}

/* 主体 */
.lb-gallery-body {
  padding: 12px 14px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lb-gallery-title {
  font-family: var(--ed-font-text);
  font-size: var(--ed-step-small);
  font-weight: 700;
  color: var(--ed-ink);
  text-decoration: none;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.lb-gallery-title:hover {
  color: var(--ed-accent-ink);
}

.lb-gallery-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 0.75rem;
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}

.lb-gallery-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  border-top: 1px solid var(--ed-rule-soft);
  padding-top: 8px;
  margin-top: 2px;
}

.lb-gallery-action {
  appearance: none;
  border: none;
  background: none;
  font-family: var(--ed-font-text);
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--ed-ink-soft);
  cursor: pointer;
  padding: 3px 8px;
  border-radius: var(--ed-radius-sm);
  text-decoration: none;
  transition: color 160ms ease, background 160ms ease;
  white-space: nowrap;
}

.lb-gallery-action:hover {
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-gallery-action.is-danger {
  color: var(--app-negative, #d64545);
}

.lb-gallery-action.is-danger:hover {
  background: color-mix(in srgb, var(--app-negative, #d64545) 12%, transparent);
}

.lb-gallery-action.is-primary {
  color: var(--ed-accent-ink);
  font-weight: 600;
}

/* 窄屏：单列 */
@media (max-width: 560px) {
  .lb-gallery-grid {
    grid-template-columns: 1fr;
  }
}
</style>
