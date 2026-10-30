<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getOverviewSummary } from '@/api/admin'
import { ApiError } from '@/api/request'
import { formatCount } from '@/utils/format'
import type { OverviewSummaryDTO } from '@/types/api'

/**
 * 总览（§5.8）：GET /admin/overview/summary → 统计卡片。
 * 后端未实现 → 错误态 + 重试，卡片按可得字段降级。
 */

const summary = ref<Partial<OverviewSummaryDTO> | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const cards = computed(() => [
  { label: '内容总数', value: summary.value?.contentCount },
  { label: '评论总数', value: summary.value?.commentCount },
  { label: '未读私信', value: summary.value?.unreadMessageCount },
  { label: '文件总数', value: summary.value?.fileCount },
  { label: '今日浏览', value: summary.value?.viewsToday },
  { label: '本周浏览', value: summary.value?.viewsWeek },
])

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

onMounted(load)
</script>

<template>
  <div>
    <n-page-header title="总览" subtitle="站点关键指标" />
    <n-alert v-if="errorMessage" type="warning" class="lb-overview-alert">
      {{ errorMessage }}（服务暂未就绪）
      <n-button size="tiny" quaternary @click="load">重试</n-button>
    </n-alert>

    <n-spin :show="loading">
      <n-grid :cols="3" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <n-grid-item v-for="card in cards" :key="card.label" span="3 m:1">
          <n-card>
            <n-statistic :label="card.label">
              <template #default>
                <span v-if="card.value !== undefined && card.value !== null">{{ formatCount(card.value) }}</span>
                <span v-else class="lb-muted">—</span>
              </template>
            </n-statistic>
          </n-card>
        </n-grid-item>
      </n-grid>
    </n-spin>
  </div>
</template>

<style scoped>
.lb-overview-alert {
  margin: 12px 0 16px;
}
</style>
