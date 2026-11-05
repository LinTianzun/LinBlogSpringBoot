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
 * 形态：审计日志时间线 —— 左侧竖线 + 节点，每条一行（时间/操作人/模块/操作/详情）。
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
  <div class="lb-log">
    <div class="lb-page-header">
      <div>
        <h2>系统日志</h2>
        <span class="lb-muted">操作审计记录（按时间倒序）</span>
      </div>
    </div>

    <el-alert v-if="errorMessage" type="warning" :closable="false" class="lb-log-alert">
      {{ errorMessage }}
      <el-button link @click="load">重试</el-button>
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

    <el-card shadow="never" class="lb-log-panel">
      <!-- 加载骨架 -->
      <div v-if="loading" class="lb-log-timeline">
        <div v-for="n in 5" :key="n" class="lb-log-row">
          <span class="lb-log-node lb-ed-skel" />
          <div class="lb-log-row-main">
            <div class="lb-ed-skel" style="width: 40%; height: 13px" />
            <div class="lb-ed-skel" style="width: 72%; height: 12px; margin-top: 8px" />
          </div>
        </div>
      </div>

      <!-- 空态 -->
      <div v-else-if="records.length === 0" class="lb-ed-empty">
        <p>暂无日志</p>
        <span class="lb-ed-caption">调整筛选条件后再试</span>
      </div>

      <!-- 时间线 -->
      <div v-else class="lb-log-timeline">
        <div
          v-for="row in records"
          :key="row.id"
          class="lb-log-row"
        >
          <span class="lb-log-node" aria-hidden="true" />
          <div class="lb-log-row-main">
            <div class="lb-log-row-head">
              <span class="lb-log-time">{{ formatDateTime(row.createdAt) }}</span>
              <span class="lb-log-operator">{{ row.userName ?? '系统' }}</span>
              <el-tag size="small" effect="plain" class="lb-log-module">{{ row.module }}</el-tag>
              <span class="lb-log-action">{{ row.action }}</span>
              <span class="lb-log-id lb-ed-caption">#{{ row.id }}</span>
            </div>
            <div class="lb-log-row-detail lb-ed-caption">
              <span v-if="row.targetType">目标：{{ targetLabel(row) }}</span>
              <span v-if="row.detail" class="lb-log-detail-text">{{ row.detail }}</span>
              <span v-if="row.ip" class="lb-log-ip">{{ row.ip }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="lb-log-pagination">
        <PaginationBar v-model:page="page" :total="total" :page-size="PAGE_SIZE" @update:page="load" />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
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

.lb-log-panel :deep(.el-card__body) {
  padding-top: 6px;
  padding-bottom: 6px;
}

/* 时间线：左侧竖线 + 节点 */
.lb-log-timeline {
  position: relative;
  padding-left: 22px;
}

.lb-log-timeline::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  background: var(--ed-rule-soft);
  border-radius: 2px;
}

.lb-log-row {
  position: relative;
  padding: 14px 0;
  border-bottom: 1px solid var(--ed-rule-soft);
}
.lb-log-row:last-child {
  border-bottom: 0;
}

.lb-log-node {
  position: absolute;
  left: -22px;
  top: 18px;
  width: 12px;
  height: 12px;
  margin-left: -1px;
  border-radius: 50%;
  background: var(--ed-card);
  border: 2px solid var(--ed-accent);
  box-shadow: 0 0 0 3px var(--ed-accent-soft);
  z-index: 1;
}
.lb-log-row:hover .lb-log-node {
  background: var(--ed-accent);
}

.lb-log-row-main {
  min-width: 0;
}

.lb-log-row-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.lb-log-time {
  font-size: var(--ed-step-small);
  font-weight: 600;
  color: var(--ed-ink);
  font-variant-numeric: tabular-nums;
}

.lb-log-operator {
  font-size: var(--ed-step-small);
  color: var(--ed-ink);
}

.lb-log-module {
  flex: none;
}

.lb-log-action {
  font-size: var(--ed-step-caption);
  font-weight: 600;
  color: var(--ed-accent-ink);
  padding: 2px 9px;
  border-radius: var(--ed-radius-pill);
  background: var(--ed-accent-soft);
}

.lb-log-id {
  margin-left: auto;
}

.lb-log-row-detail {
  margin-top: 6px;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.lb-log-detail-text {
  color: var(--ed-ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.lb-log-ip {
  color: var(--ed-ink-faint);
  font-variant-numeric: tabular-nums;
  margin-left: auto;
}

@media (max-width: 640px) {
  .lb-log-id {
    margin-left: 0;
  }
  .lb-log-ip {
    margin-left: 0;
  }
  .lb-log-detail-text {
    white-space: normal;
  }
}
</style>
