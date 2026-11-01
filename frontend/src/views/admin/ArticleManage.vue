<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { NButton, NCard, NInput, NSpace, NTag, useDialog, useMessage } from 'naive-ui'
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

const STATE_TABS: Array<{ label: string; value: ContentState | null }> = [
  { label: '全部', value: null },
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

const STATE_TAG_TYPE: Record<ContentState, 'default' | 'success' | 'warning' | 'error'> = {
  draft: 'default',
  published: 'success',
  hidden: 'warning',
  deleted: 'error',
}

const message = useMessage()
const dialog = useDialog()
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

const rowKey = (row: ContentView): number => row.id

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

function pickState(value: ContentState | null): void {
  page.value = 1
  state.value = value
  void load()
}

async function setState(row: ContentView, nextState: ContentState): Promise<void> {
  try {
    await updateContentState(row.id, nextState)
    message.success(`「${row.title}」已${STATE_LABEL[nextState]}`)
    await load()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

function confirmRemove(row: ContentView): void {
  dialog.warning({
    title: '移入回收站',
    content: `确定将「${row.title}」移入回收站吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteContent(row.id)
        message.success('已移入回收站')
        await load()
      } catch (error) {
        message.error(error instanceof ApiError ? error.message : '删除失败')
      }
    },
  })
}

function confirmPurge(row: ContentView): void {
  dialog.error({
    title: '彻底删除',
    content: `「${row.title}」将被永久删除且不可恢复，确定吗？`,
    positiveText: '彻底删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await purgeContent(row.id)
        message.success('已彻底删除')
        await load()
      } catch (error) {
        message.error(error instanceof ApiError ? error.message : '操作失败')
      }
    },
  })
}

/** 状态机 → 每行可用操作 */
function rowActions(row: ContentView): Array<{ label: string; type: 'primary' | 'default' | 'error'; onClick: () => void }> {
  switch (row.state) {
    case 'draft':
      return [
        ...(canPublish.value ? [{ label: '发布', type: 'primary' as const, onClick: () => void setState(row, 'published') }] : []),
        { label: '删除', type: 'error' as const, onClick: () => confirmRemove(row) },
      ]
    case 'published':
      return [
        ...(canPublish.value ? [{ label: '隐藏', type: 'default' as const, onClick: () => void setState(row, 'hidden') }] : []),
        { label: '删除', type: 'error' as const, onClick: () => confirmRemove(row) },
      ]
    case 'hidden':
      return [
        ...(canPublish.value ? [{ label: '重新发布', type: 'primary' as const, onClick: () => void setState(row, 'published') }] : []),
        { label: '转草稿', type: 'default' as const, onClick: () => void setState(row, 'draft') },
        { label: '删除', type: 'error' as const, onClick: () => confirmRemove(row) },
      ]
    case 'deleted':
      return [
        { label: '还原', type: 'primary' as const, onClick: () => void setState(row, 'draft') },
        ...(canPurge.value ? [{ label: '彻底删除', type: 'error' as const, onClick: () => confirmPurge(row) }] : []),
      ]
  }
}

const columns = computed(() => [
  {
    title: '标题',
    key: 'title',
    render: (row: ContentView) =>
      h(RouterLink, { to: `/admin/articles/edit/${row.id}` }, { default: () => row.title || '（无标题）' }),
  },
  { title: '作者', key: 'authorName', width: 110, render: (row: ContentView) => row.authorName ?? '—' },
  {
    title: '状态',
    key: 'state',
    width: 90,
    render: (row: ContentView) =>
      h(NTag, { type: STATE_TAG_TYPE[row.state], size: 'small', bordered: false }, { default: () => STATE_LABEL[row.state] }),
  },
  { title: '浏览', key: 'views', width: 70 },
  { title: '赞', key: 'praises', width: 70 },
  {
    title: '发布时间',
    key: 'publishedAt',
    width: 150,
    render: (row: ContentView) => (row.publishedAt ? formatDate(row.publishedAt) : '—'),
  },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 150,
    render: (row: ContentView) => (row.updatedAt ? formatDateTime(row.updatedAt) : '—'),
  },
  {
    title: '操作',
    key: 'actions',
    render: (row: ContentView) =>
      h(NSpace, { size: 'small', wrap: false }, {
        default: () =>
          rowActions(row).map((action) =>
            h(NButton, { size: 'tiny', type: action.type, quaternary: true, onClick: action.onClick }, { default: () => action.label }),
          ),
      }),
  },
])

onMounted(load)
</script>

<template>
  <div>
    <n-space justify="space-between" align="center" style="margin-bottom: 16px">
      <n-page-header title="文章管理" />
      <RouterLink to="/admin/articles/edit/">
        <NButton type="primary">新建文章</NButton>
      </RouterLink>
    </n-space>

    <n-card>
      <n-space class="lb-manage-filters" size="small" align="center">
        <n-input
          v-model:value="keyword"
          placeholder="搜索标题"
          clearable
          style="width: 240px"
          @keyup.enter="search"
        />
        <NButton size="small" @click="search">搜索</NButton>
      </n-space>

      <n-tabs
        type="line"
        size="small"
        :value="state ?? 'all'"
        style="margin-bottom: 8px"
        @update:value="(value: string) => pickState(value === 'all' ? null : (value as ContentState))"
      >
        <n-tab v-for="tab in STATE_TABS" :key="tab.label" :name="tab.value ?? 'all'">
          {{ tab.label }}
        </n-tab>
      </n-tabs>

      <n-alert v-if="errorMessage" type="warning" style="margin: 12px 0">
        {{ errorMessage }}
        <n-button size="tiny" quaternary @click="load">重试</n-button>
      </n-alert>

      <n-data-table :columns="columns" :data="records" :loading="loading" :row-key="rowKey" />

      <div class="lb-manage-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="pageSize" @update:page="load" />
      </div>
    </n-card>
  </div>
</template>

<style scoped>
.lb-manage-filters {
  margin-bottom: 12px;
}

.lb-manage-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
