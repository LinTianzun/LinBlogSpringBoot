<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listSystemLogs } from '@/api/admin'
import { ApiError } from '@/api/request'
import { formatDateTime } from '@/utils/format'
import PaginationBar from '@/components/PaginationBar.vue'
import type { SystemLogQuery, SystemLogViewDTO } from '@/types/api'

/**
 * 系统日志（§5.8）：GET /admin/system-logs?module&action&page（systemLog.view）。
 * module 精确匹配（预置全站写入值，见 backend SystemLogService.record 调用方），
 * action 精确匹配自由输入；按 id 倒序分页。
 */

const MODULE_OPTIONS = [
  'auth',
  'content',
  'diary',
  'comment',
  'guestbook',
  'message',
  'report',
  'label',
  'subset',
  'setting',
  'permission',
  'user',
  'media',
  'file',
  'mail',
]

const PAGE_SIZE = 20

const records = ref<SystemLogViewDTO[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const errorMessage = ref('')

const filterModule = ref('')
const filterAction = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  const query: SystemLogQuery = { page: page.value, pageSize: PAGE_SIZE }
  if (filterModule.value.trim() !== '') query.module = filterModule.value.trim()
  if (filterAction.value.trim() !== '') query.action = filterAction.value.trim()
  try {
    const result = await listSystemLogs(query)
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

function resetFilters(): void {
  filterModule.value = ''
  filterAction.value = ''
  search()
}

function targetLabel(row: SystemLogViewDTO): string {
  if (!row.targetType) return '—'
  return row.targetId === null || row.targetId === undefined ? row.targetType : `${row.targetType} #${row.targetId}`
}

onMounted(load)
</script>

<template>
  <div>
    <div class="lb-page-header">
      <h2>系统日志</h2>
      <span class="lb-muted">操作审计记录（按时间倒序）</span>
    </div>

    <el-alert v-if="errorMessage" type="warning" :closable="false" class="lb-log-alert">
      {{ errorMessage }}
      <el-button link size="small" @click="load">重试</el-button>
    </el-alert>

    <el-card shadow="never" class="lb-log-filter" data-testid="log-filter">
      <el-select
        v-model="filterModule"
        filterable
        clearable
        allow-create
        default-first-option
        placeholder="模块（如 content）"
        data-testid="log-module"
        style="width: 180px"
      >
        <el-option v-for="module in MODULE_OPTIONS" :key="module" :label="module" :value="module" />
      </el-select>
      <el-input
        v-model="filterAction"
        clearable
        placeholder="操作（如 create / trash）"
        data-testid="log-action"
        style="width: 200px"
        @keyup.enter="search"
      />
      <el-button type="primary" data-testid="log-search" @click="search">查询</el-button>
      <el-button data-testid="log-reset" @click="resetFilters">重置</el-button>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="records" row-key="id" empty-text="暂无日志">
        <el-table-column label="ID" prop="id" width="70" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="130">
          <template #default="{ row }">{{ row.userName ?? '系统' }}</template>
        </el-table-column>
        <el-table-column label="模块" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.module }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" prop="action" width="140" show-overflow-tooltip />
        <el-table-column label="目标" width="150">
          <template #default="{ row }">{{ targetLabel(row as SystemLogViewDTO) }}</template>
        </el-table-column>
        <el-table-column label="详情" prop="detail" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.detail ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="IP" width="130">
          <template #default="{ row }">{{ row.ip ?? '—' }}</template>
        </el-table-column>
      </el-table>

      <div class="lb-log-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="PAGE_SIZE" @update:page="load" />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.lb-page-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}

.lb-page-header h2 {
  margin: 0;
  font-size: 18px;
}

.lb-log-alert {
  margin-bottom: 16px;
}

.lb-log-filter {
  margin-bottom: 16px;
}

.lb-log-filter :deep(.el-card__body) {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.lb-log-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
