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
      <h2>画廊管理</h2>
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
        <el-button size="small" @click="search">搜索</el-button>
      </div>

      <el-alert v-if="errorMessage" type="warning" :title="errorMessage" class="lb-load-alert" :closable="false">
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <el-table v-loading="loading" :data="records" row-key="id">
        <el-table-column label="预览" width="96">
          <template #default>
            <div class="lb-cover-placeholder">🖼</div>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="200">
          <template #default="{ row }">
            <RouterLink :to="`/admin/gallery/edit/${row.id}`">{{ row.title || '（无标题）' }}</RouterLink>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="STATE_TAG_TYPE[row.state as ContentState]">
              {{ STATE_OPTIONS.find((item) => item.value === row.state)?.label ?? row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="赞" prop="praises" width="80" />
        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">{{ row.publishedAt ? formatDate(row.publishedAt) : '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="200">
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
  margin-bottom: 12px;
}

.lb-load-alert {
  margin: 0 0 12px;
}

.lb-cover-placeholder {
  width: 80px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  border-radius: 4px;
  background: linear-gradient(135deg, rgba(24, 160, 88, 0.12), rgba(24, 160, 88, 0.04));
}

.lb-manage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
