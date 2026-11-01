<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { NButton, NCard, NInput, NSelect, NSpace, NTag, useDialog, useMessage } from 'naive-ui'
import { deleteContent, listAdminContents, purgeContent, updateContentState } from '@/api/admin'
import { ApiError } from '@/api/request'
import { useSessionStore } from '@/stores/session'
import { formatDate } from '@/utils/format'
import type { AdminContentQuery, ContentView, ContentState } from '@/types/api'

/**
 * 画廊管理（§5.3）：/admin/contents?contentType=image 列表 + 状态机操作 + 回收站。
 * ⚠️ 无 coverStorageKey（仅 coverMediaId）→ 预览列用占位块（待后端暴露 key）。
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

async function setState(row: ContentView, nextState: ContentState): Promise<void> {
  try {
    await updateContentState(row.id, nextState)
    message.success('状态已更新')
    await load()
  } catch (error) {
    message.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

function remove(row: ContentView): void {
  dialog.warning({
    title: '移入回收站',
    content: `确定将「${row.title || '未命名图片'}」移入回收站吗？`,
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
    content: `「${row.title || '未命名图片'}」将被永久删除且不可恢复，确定吗？`,
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

const columns = computed(() => [
  {
    title: '预览',
    key: 'cover',
    width: 96,
    render: () =>
      h(
        'div',
        {
          style:
            'width:80px;height:60px;display:flex;align-items:center;justify-content:center;font-size:22px;border-radius:4px;background:linear-gradient(135deg,rgba(24,160,88,0.12),rgba(24,160,88,0.04))',
        },
        { default: () => '🖼' },
      ),
  },
  {
    title: '标题',
    key: 'title',
    render: (row: ContentView) =>
      h(RouterLink, { to: `/admin/gallery/edit/${row.id}` }, { default: () => row.title || '（无标题）' }),
  },
  {
    title: '状态',
    key: 'state',
    width: 90,
    render: (row: ContentView) => {
      const option = STATE_OPTIONS.find((item) => item.value === row.state)
      return h(
        NTag,
        { type: STATE_TAG_TYPE[row.state], size: 'small', bordered: false },
        { default: () => option?.label ?? row.state },
      )
    },
  },
  { title: '赞', key: 'praises', width: 80 },
  {
    title: '发布时间',
    key: 'publishedAt',
    width: 150,
    render: (row: ContentView) => (row.publishedAt ? formatDate(row.publishedAt) : '—'),
  },
  {
    title: '操作',
    key: 'actions',
    render: (row: ContentView) => {
      const actions: Array<{ label: string; type: 'primary' | 'default' | 'error'; onClick: () => void }> = []
      if (row.state === 'draft' && canPublish.value) {
        actions.push({ label: '发布', type: 'primary', onClick: () => void setState(row, 'published') })
      }
      if (row.state === 'published' && canPublish.value) {
        actions.push({ label: '隐藏', type: 'default', onClick: () => void setState(row, 'hidden') })
      }
      if (row.state === 'hidden') {
        if (canPublish.value) actions.push({ label: '重新发布', type: 'primary', onClick: () => void setState(row, 'published') })
        actions.push({ label: '转草稿', type: 'default', onClick: () => void setState(row, 'draft') })
      }
      if (row.state === 'deleted') {
        actions.push({ label: '还原', type: 'primary', onClick: () => void setState(row, 'draft') })
        if (canPurge.value) actions.push({ label: '彻底删除', type: 'error', onClick: () => confirmPurge(row) })
      }
      if (row.state !== 'deleted') {
        actions.push({ label: '删除', type: 'error', onClick: () => remove(row) })
      }
      return h(NSpace, { size: 'small', wrap: false }, { default: () => actions.map((a) => h(NButton, { size: 'tiny', type: a.type, quaternary: true, onClick: a.onClick }, { default: () => a.label })) })
    },
  },
])

onMounted(load)
</script>

<template>
  <div>
    <n-space justify="space-between" align="center" style="margin-bottom: 16px">
      <n-page-header title="画廊管理" />
      <RouterLink to="/admin/gallery/edit/">
        <NButton type="primary">新建图片</NButton>
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
