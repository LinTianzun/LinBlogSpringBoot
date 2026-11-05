<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteContent, listAdminContents, purgeContent, updateContentState } from '@/api/admin'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import PaginationBar from '@/components/PaginationBar.vue'
import { formatDate, formatDateTime } from '@/utils/format'
import type { AdminContentQuery, ContentView, ContentState } from '@/types/api'

/**
 * 文章管理（§5.3 ContentAdminController）：
 * 状态 tabs（含回收站）+ keyword 搜索（后端 like title）+ 状态机操作。
 * 状态机：draft→published|deleted；published→hidden|deleted；hidden→published|draft|deleted；
 * deleted→draft（还原）。article 进入 published / published→hidden 需 article.publish；
 * 彻底删除（purge）仅 article.manageAll。
 */

const STATE_TABS: Array<{ label: string; value: ContentState | 'all' }> = [
  { label: '全部', value: 'all' },
  { label: '草稿', value: 'draft' },
  { label: '已发布', value: 'published' },
  { label: '已隐藏', value: 'hidden' },
  { label: '回收站', value: 'deleted' },
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

const session = useSessionStore()

const records = ref<ContentView[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const keyword = ref('')
const state = ref<ContentState | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const canPublish = computed(() => session.can('article.publish'))
const canPurge = computed(() => session.can('article.manageAll'))

const query = computed<AdminContentQuery>(() => ({
  contentType: 'article',
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

function pickState(value: ContentState | 'all'): void {
  page.value = 1
  state.value = value === 'all' ? null : value
  void load()
}

async function setState(row: ContentView, nextState: ContentState): Promise<void> {
  try {
    await updateContentState(row.id, nextState)
    ElMessage.success(`「${row.title}」已${STATE_LABEL[nextState]}`)
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

async function confirmRemove(row: ContentView): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定将「${row.title}」移入回收站吗？`, '移入回收站', {
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
    await ElMessageBox.confirm(`「${row.title}」将被永久删除且不可恢复，确定吗？`, '彻底删除', {
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
  switch (row.state) {
    case 'draft':
      return [
        ...(canPublish.value ? [{ label: '发布', type: 'primary' as const, onClick: () => void setState(row, 'published') }] : []),
        { label: '删除', type: 'danger' as const, onClick: () => void confirmRemove(row) },
      ]
    case 'published':
      return [
        ...(canPublish.value ? [{ label: '隐藏', type: '' as const, onClick: () => void setState(row, 'hidden') }] : []),
        { label: '删除', type: 'danger' as const, onClick: () => void confirmRemove(row) },
      ]
    case 'hidden':
      return [
        ...(canPublish.value ? [{ label: '重新发布', type: 'primary' as const, onClick: () => void setState(row, 'published') }] : []),
        { label: '转草稿', type: '' as const, onClick: () => void setState(row, 'draft') },
        { label: '删除', type: 'danger' as const, onClick: () => void confirmRemove(row) },
      ]
    case 'deleted':
      return [
        { label: '还原', type: 'primary' as const, onClick: () => void setState(row, 'draft') },
        ...(canPurge.value ? [{ label: '彻底删除', type: 'danger' as const, onClick: () => void confirmPurge(row) }] : []),
      ]
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="lb-page-header">
      <div>
        <h2>文章管理</h2>
        <span class="lb-muted">共 {{ total }} 篇</span>
      </div>
      <RouterLink to="/admin/articles/edit/">
        <el-button type="primary">新建文章</el-button>
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
        <el-button @click="search">搜索</el-button>
      </div>

      <el-tabs
        :model-value="state ?? 'all'"
        class="lb-state-tabs"
        @update:model-value="(value: string | number) => pickState(value as ContentState | 'all')"
      >
        <el-tab-pane v-for="tab in STATE_TABS" :key="tab.label" :label="tab.label" :name="tab.value" />
      </el-tabs>

      <el-alert v-if="errorMessage" type="warning" :title="errorMessage" class="lb-load-alert" :closable="false">
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <!-- 加载骨架 -->
      <div v-if="loading" class="lb-art-list">
        <div v-for="n in 3" :key="`skel-${n}`" class="lb-art-card lb-art-card--skel">
          <div class="lb-art-thumb lb-ed-skel" />
          <div class="lb-art-main">
            <div class="lb-ed-skel" style="height: 20px; width: 55%" />
            <div class="lb-ed-skel" style="height: 14px; width: 90%; margin-top: 12px" />
            <div class="lb-ed-skel" style="height: 14px; width: 70%; margin-top: 8px" />
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <div v-else-if="records.length === 0 && !errorMessage" class="lb-ed-empty">
        暂无文章，点击右上角「新建文章」开始创作。
      </div>

      <!-- 文章卡片列表 -->
      <div v-else class="lb-art-list">
        <article v-for="row in records" :key="row.id" class="lb-art-card">
          <RouterLink :to="`/admin/articles/edit/${row.id}`" class="lb-art-thumb">
            <img
              v-if="row.coverStorageKey"
              :src="`/api/v1/media/${row.coverStorageKey}?width=320`"
              :alt="row.title || '封面'"
              loading="lazy"
            />
            <div v-else class="lb-art-thumb-ph" aria-hidden="true">
              <span>{{ (row.title || 'A').slice(0, 1).toUpperCase() }}</span>
            </div>
          </RouterLink>

          <div class="lb-art-main">
            <div class="lb-art-head">
              <RouterLink :to="`/admin/articles/edit/${row.id}`" class="lb-art-title">
                {{ row.title || '（无标题）' }}
              </RouterLink>
              <el-tag size="small" effect="plain" :type="STATE_TAG_TYPE[row.state as ContentState]">
                {{ STATE_LABEL[row.state as ContentState] }}
              </el-tag>
            </div>

            <p v-if="row.introduce || row.plainText" class="lb-art-summary">
              {{ row.introduce || row.plainText }}
            </p>

            <div class="lb-art-meta">
              <span v-if="row.authorName" class="lb-art-meta-item">
                <span class="lb-ed-avatar">{{ row.authorName.slice(0, 1).toUpperCase() }}</span>
                {{ row.authorName }}
              </span>
              <span class="lb-art-meta-item">{{ row.publishedAt ? formatDate(row.publishedAt) : (row.updatedAt ? formatDateTime(row.updatedAt) : '未发布') }}</span>
              <span class="lb-art-stats">
                <span>{{ row.views ?? 0 }} 浏览</span>
                <span>{{ row.praises ?? 0 }} 赞</span>
                <span>{{ row.commentsCount ?? 0 }} 评论</span>
              </span>
            </div>
          </div>

          <div class="lb-art-actions">
            <RouterLink :to="`/admin/articles/edit/${row.id}`" class="lb-art-action">编辑</RouterLink>
            <button
              v-for="action in rowActions(row)"
              :key="action.label"
              type="button"
              class="lb-art-action"
              :class="{
                'is-danger': action.type === 'danger',
                'is-primary': action.type === 'primary',
              }"
              @click="action.onClick"
            >
              {{ action.label }}
            </button>
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
/* 页面头 / 工具栏 / 分页 的通用样式由 AdminLayout 的非 scoped admin 设计层统一提供。
   此处仅保留文章管理页特有的卡片列表 + tabs 微调。 */
.lb-state-tabs {
  margin-bottom: 8px;
}

.lb-state-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.lb-state-tabs :deep(.el-tabs__item) {
  font-weight: 500;
  font-size: var(--ed-step-small);
}

.lb-load-alert {
  margin: 0 0 12px;
}

/* 文章卡片列表：纵向堆叠横向卡 */
.lb-art-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.lb-art-card {
  display: flex;
  align-items: stretch;
  gap: 16px;
  padding: 14px;
  background: var(--ed-card);
  border: 1px solid var(--ed-rule-soft);
  border-radius: var(--ed-radius-md);
  transition: box-shadow 220ms ease, border-color 220ms ease, transform 220ms ease;
}

.lb-art-card:hover {
  box-shadow: var(--ed-shadow-md);
  border-color: color-mix(in srgb, var(--ed-accent) 24%, var(--ed-rule-soft));
  transform: translateY(-2px);
}

.lb-art-card--skel {
  pointer-events: none;
}

/* 封面缩略图 */
.lb-art-thumb {
  flex: 0 0 148px;
  width: 148px;
  height: 100px;
  border-radius: var(--ed-radius-sm);
  overflow: hidden;
  background: var(--ed-accent-soft);
  display: block;
  align-self: flex-start;
}

.lb-art-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform 420ms cubic-bezier(0.22, 1, 0.36, 1);
}

.lb-art-card:hover .lb-art-thumb img {
  transform: scale(1.05);
}

.lb-art-thumb-ph {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  font-family: var(--ed-font-display);
  font-size: 2rem;
  font-weight: 500;
  color: var(--ed-accent-ink);
  background: linear-gradient(135deg, var(--ed-accent-soft), color-mix(in srgb, var(--ed-accent) 6%, var(--ed-card)));
}

/* 主体 */
.lb-art-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lb-art-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex-wrap: wrap;
}

.lb-art-title {
  font-family: var(--ed-font-text);
  font-size: 1.06rem;
  font-weight: 700;
  color: var(--ed-ink);
  text-decoration: none;
  letter-spacing: -0.01em;
  line-height: 1.3;
}

.lb-art-title:hover {
  color: var(--ed-accent-ink);
}

.lb-art-summary {
  margin: 0;
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.lb-art-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 0.8125rem;
  color: var(--ed-ink-soft);
  margin-top: auto;
}

.lb-art-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.lb-art-stats {
  display: inline-flex;
  gap: 12px;
  font-variant-numeric: tabular-nums;
  margin-left: auto;
}

/* 操作区 */
.lb-art-actions {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
  gap: 4px;
  padding-left: 4px;
}

.lb-art-action {
  appearance: none;
  border: none;
  background: none;
  font-family: var(--ed-font-text);
  font-size: var(--ed-step-small);
  font-weight: 500;
  color: var(--ed-ink-soft);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--ed-radius-sm);
  text-decoration: none;
  transition: color 160ms ease, background 160ms ease;
  white-space: nowrap;
}

.lb-art-action:hover {
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-art-action.is-danger {
  color: var(--app-negative, #d64545);
}

.lb-art-action.is-danger:hover {
  background: color-mix(in srgb, var(--app-negative, #d64545) 12%, transparent);
}

.lb-art-action.is-primary {
  color: var(--ed-accent-ink);
  font-weight: 600;
}

/* 窄屏：卡片纵向堆叠 */
@media (max-width: 720px) {
  .lb-art-card {
    flex-direction: column;
  }

  .lb-art-thumb {
    flex: none;
    width: 100%;
    height: 168px;
  }

  .lb-art-actions {
    flex-direction: row;
    flex-wrap: wrap;
    justify-content: flex-start;
    border-top: 1px solid var(--ed-rule-soft);
    padding-top: 10px;
  }

  .lb-art-stats {
    margin-left: 0;
  }
}
</style>
