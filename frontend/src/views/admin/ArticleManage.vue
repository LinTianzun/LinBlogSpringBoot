<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { NButton, NCard, NInput, NSelect, NSpace, NTag, useDialog, useMessage } from 'naive-ui'
import { deleteContent, listAdminContents, updateContentState } from '@/api/admin'
import { ApiError } from '@/api/request'
import { formatDate } from '@/utils/format'
import type { AdminContentQuery, ContentState, ContentSummaryDTO } from '@/types/api'

/**
 * 文章管理（§5.5）：/admin/contents 列表 + 搜索 + 状态操作。
 * 后端未实现 → 错误态 + 空表，接口就绪后自动可用。
 */

const STATE_OPTIONS = [
  { label: '草稿', value: 'draft' },
  { label: '已发布', value: 'published' },
  { label: '已隐藏', value: 'hidden' },
  { label: '已删除', value: 'deleted' },
]

const STATE_TAG_TYPE: Record<ContentState, 'default' | 'success' | 'warning' | 'error'> = {
  draft: 'default',
  published: 'success',
  hidden: 'warning',
  deleted: 'error',
}

const message = useMessage()
const dialog = useDialog()

const records = ref<ContentSummaryDTO[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const keyword = ref('')
const state = ref<ContentState | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const query = computed<AdminContentQuery>(() => ({
  contentType: 'article',
  page: page.value,
  pageSize,
  keyword: keyword.value || undefined,
  state: state.value ?? undefined,
}))

const rowKey = (row: ContentSummaryDTO): number => row.id

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

async function setState(row: ContentSummaryDTO, nextState: Exclude<ContentState, 'deleted'>): Promise<void> {
  try {
    await updateContentState(row.id, nextState)
    message.success(`已${nextState === 'published' ? '发布' : nextState === 'hidden' ? '隐藏' : '存为草稿'}「${row.title}」`)
    await load()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

function remove(row: ContentSummaryDTO): void {
  dialog.warning({
    title: '删除确认',
    content: `确定删除「${row.title}」吗？`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteContent(row.id)
        message.success('已删除')
        await load()
      } catch (error) {
        message.error(error instanceof ApiError ? error.message : '删除失败')
      }
    },
  })
}

const columns = computed(() => [
  { title: '标题', key: 'title', render: (row: ContentSummaryDTO) => row.title || '（无标题）' },
  {
    title: '状态',
    key: 'state',
    render: (row: ContentSummaryDTO) => {
      const option = STATE_OPTIONS.find((item) => item.value === row.state)
      return h(
        NTag,
        { type: STATE_TAG_TYPE[row.state], size: 'small', bordered: false },
        { default: () => option?.label ?? row.state },
      )
    },
  },
  { title: '浏览', key: 'views', width: 80 },
  { title: '赞', key: 'praises', width: 80 },
  { title: '评论', key: 'comments', width: 80 },
  {
    title: '发布时间',
    key: 'publishedAt',
    render: (row: ContentSummaryDTO) => (row.publishedAt ? formatDate(row.publishedAt) : '—'),
  },
  {
    title: '操作',
    key: 'actions',
    render: (row: ContentSummaryDTO) =>
      h(NSpace, { size: 'small' }, {
        default: () => [
          h(RouterLink, { to: `/admin/articles/edit/${row.id}` }, { default: () => '编辑' }),
          row.state !== 'published'
            ? h(NButton, { size: 'tiny', type: 'primary', quaternary: true, onClick: () => void setState(row, 'published') }, { default: () => '发布' })
            : h(NButton, { size: 'tiny', quaternary: true, onClick: () => void setState(row, 'hidden') }, { default: () => '隐藏' }),
          h(NButton, { size: 'tiny', type: 'error', quaternary: true, onClick: () => remove(row) }, { default: () => '删除' }),
        ],
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
        <n-select
          v-model:value="state"
          placeholder="状态"
          :options="STATE_OPTIONS"
          clearable
          style="width: 140px"
          @update:value="search"
        />
        <NButton size="small" @click="search">搜索</NButton>
      </n-space>

      <n-alert v-if="errorMessage" type="warning" style="margin: 12px 0">
        {{ errorMessage }}（服务暂未就绪）
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
