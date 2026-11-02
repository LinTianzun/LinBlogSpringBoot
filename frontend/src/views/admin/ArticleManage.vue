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
      <h2>文章管理</h2>
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
        <el-button size="small" @click="search">搜索</el-button>
      </div>

      <el-tabs
        :model-value="state ?? 'all'"
        class="lb-state-tabs"
        @update:model-value="(value) => pickState(value as ContentState | 'all')"
      >
        <el-tab-pane v-for="tab in STATE_TABS" :key="tab.label" :label="tab.label" :name="tab.value" />
      </el-tabs>

      <el-alert v-if="errorMessage" type="warning" :title="errorMessage" class="lb-load-alert" :closable="false">
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="records" row-key="id">
        <el-table-column label="标题" min-width="200">
          <template #default="{ row }">
            <RouterLink :to="`/admin/articles/edit/${row.id}`">{{ row.title || '（无标题）' }}</RouterLink>
          </template>
        </el-table-column>
        <el-table-column label="作者" width="110">
          <template #default="{ row }">{{ row.authorName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="STATE_TAG_TYPE[row.state as ContentState]">
              {{ STATE_LABEL[row.state as ContentState] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="浏览" prop="views" width="70" />
        <el-table-column label="赞" prop="praises" width="70" />
        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">{{ row.publishedAt ? formatDate(row.publishedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">{{ row.updatedAt ? formatDateTime(row.updatedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="180">
          <template #default="{ row }">
            <el-button
              v-for="action in rowActions(row as ContentView)"
              :key="action.label"
              link
              size="small"
              :type="action.type || undefined"
              @click="action.onClick"
            >
              {{ action.label }}
            </el-button>
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
  margin-bottom: 4px;
}

.lb-state-tabs {
  margin-bottom: 8px;
}

.lb-load-alert {
  margin: 0 0 12px;
}

.lb-manage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
