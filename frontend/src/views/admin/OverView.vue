<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getOverviewSummary, getVisitStats } from '@/api/admin'
import { ApiError } from '@/api/request'
import { formatCount } from '@/utils/format'
import type { OverviewSummaryDTO, VisitStatsRange, VisitStatsResultDTO } from '@/types/api'

/**
 * 总览（§5.8，f5e1747 已交付）：GET /admin/overview/summary → 统计卡片；
 * GET /admin/overview/visit-stats?range=week|moon → 浏览趋势条形（纯 CSS，不引图表库）。
 */

const RANGE_OPTIONS: Array<{ label: string; value: VisitStatsRange }> = [
  { label: '近一周', value: 'week' },
  { label: '近一月', value: 'moon' },
]

const summary = ref<Partial<OverviewSummaryDTO> | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const visitRange = ref<VisitStatsRange>('week')
const visitStats = ref<VisitStatsResultDTO | null>(null)
const visitLoading = ref(false)
const visitError = ref('')

const cards = computed(() => [
  { label: '内容总数', value: summary.value?.contents },
  { label: '评论总数', value: summary.value?.comments },
  { label: '未读私信', value: summary.value?.unreadMessages },
  { label: '文件总数', value: summary.value?.files },
  { label: '今日浏览', value: summary.value?.viewsToday },
  { label: '本周浏览', value: summary.value?.viewsWeek },
])

const maxVisitCount = computed(() =>
  visitStats.value?.points.reduce((max, point) => Math.max(max, point.count), 0) ?? 0,
)

const barHeight = (count: number): string => {
  if (maxVisitCount.value <= 0) return '2px'
  return `${Math.max(2, Math.round((count / maxVisitCount.value) * 120))}px`
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    summary.value = await getOverviewSummary()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
    summary.value = null
  } finally {
    loading.value = false
  }
}

async function loadVisits(): Promise<void> {
  visitLoading.value = true
  visitError.value = ''
  try {
    visitStats.value = await getVisitStats(visitRange.value)
  } catch (error) {
    visitError.value = error instanceof ApiError ? error.message : '加载失败'
    visitStats.value = null
  } finally {
    visitLoading.value = false
  }
}

function pickRange(range: VisitStatsRange): void {
  visitRange.value = range
  void loadVisits()
}

onMounted(() => {
  void load()
  void loadVisits()
})
</script>

<template>
  <div>
    <div class="lb-page-header">
      <h2>总览</h2>
      <span class="lb-muted">站点关键指标</span>
    </div>

    <el-alert v-if="errorMessage" type="warning" :title="`${errorMessage}（服务暂未就绪）`" class="lb-overview-alert" :closable="false">
      <el-button size="small" link @click="load">重试</el-button>
    </el-alert>

    <div v-loading="loading" class="lb-overview-grid">
      <el-card v-for="card in cards" :key="card.label" shadow="never" class="lb-overview-card">
        <span class="lb-overview-label">{{ card.label }}</span>
        <strong v-if="card.value !== undefined && card.value !== null" class="lb-overview-value">
          {{ formatCount(card.value) }}
        </strong>
        <strong v-else class="lb-overview-value lb-muted">—</strong>
      </el-card>
    </div>

    <!-- 浏览趋势（visit-stats）：range 切换 + 纯 CSS 条形序列 -->
    <el-card shadow="never" class="lb-visit-card">
      <div class="lb-visit-head">
        <span class="lb-visit-title">浏览趋势</span>
        <el-select :model-value="visitRange" size="small" style="width: 120px" @update:model-value="pickRange">
          <el-option v-for="option in RANGE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </div>

      <el-alert v-if="visitError" type="warning" :closable="false">
        {{ visitError }}
        <el-button size="small" link @click="loadVisits">重试</el-button>
      </el-alert>

      <div v-else-if="visitLoading" v-loading="true" class="lb-visit-chart lb-visit-loading" />
      <template v-else-if="visitStats && visitStats.points.length > 0">
        <div class="lb-visit-chart">
          <div
            v-for="point in visitStats.points"
            :key="point.date"
            class="lb-visit-bar"
            :title="`${point.date}：${formatCount(point.count)} 次`"
          >
            <span class="lb-visit-bar-fill" :style="{ height: barHeight(point.count) }" />
          </div>
        </div>
        <div class="lb-visit-axis lb-muted">
          <span>{{ visitStats.points[0].date }}</span>
          <span>{{ visitStats.points[visitStats.points.length - 1].date }}</span>
        </div>
      </template>
      <el-empty v-else description="暂无浏览数据" :image-size="60" />
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

.lb-overview-alert {
  margin: 0 0 16px;
}

.lb-overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 900px) {
  .lb-overview-grid {
    grid-template-columns: 1fr;
  }
}

.lb-overview-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.lb-overview-label {
  font-size: 13px;
  color: var(--lb-muted);
}

.lb-overview-value {
  font-size: 24px;
  line-height: 1.2;
}

.lb-visit-card {
  margin-top: 16px;
}

.lb-visit-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.lb-visit-title {
  font-weight: 600;
  font-size: 14px;
}

.lb-visit-chart {
  display: flex;
  align-items: flex-end;
  gap: 3px;
  height: 132px;
}

.lb-visit-loading {
  min-height: 132px;
}

.lb-visit-bar {
  flex: 1;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  height: 100%;
  min-width: 6px;
}

.lb-visit-bar-fill {
  width: 100%;
  max-width: 28px;
  border-radius: 3px 3px 0 0;
  background: var(--main-color, var(--lb-primary));
  opacity: 0.85;
  transition: height 0.2s;
}

.lb-visit-axis {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 12px;
}
</style>
