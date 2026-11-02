<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listPortalDiaries } from '@/api/portal'
import { ApiError } from '@/api/request'
import ContentRenderer from '@/components/ContentRenderer.vue'
import { parseTiptapDoc } from '@/components/renderContent'
import PaginationBar from '@/components/PaginationBar.vue'
import WeatherBadge from '@/components/WeatherBadge.vue'
import { formatCount } from '@/utils/format'
import type { DiaryDTO } from '@/types/api'

/**
 * 日记时间轴（§3.2 / §5.4）：GET /portal/diaries（published）。
 * body 是纯字符串或 ProseMirror JSON 字符串（写入端未强校验）→ parseTiptapDoc 防御渲染。
 * 详情页浏览由服务端顺带记；列表不做 POST /views。
 */

const records = ref<DiaryDTO[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const errorMessage = ref('')

const docs = computed(() => new Map(records.value.map((d) => [d.id, parseTiptapDoc(d.body)])))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listPortalDiaries({ page: page.value, pageSize: 10 })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '日记加载失败'
    records.value = []
  } finally {
    loading.value = false
  }
}

watch(page, () => void load())
onMounted(load)
</script>

<template>
  <div class="lb-diary-wall">
    <h3 class="lb-diary-title">日记</h3>
    <div v-loading="loading">
      <el-alert v-if="errorMessage" type="warning" class="lb-diary-alert" :closable="false">
        {{ errorMessage }}
      </el-alert>
      <el-empty v-if="records.length === 0 && !loading && !errorMessage" description="还没有日记" class="lb-diary-empty" />

      <div v-else-if="records.length > 0" class="lb-timeline">
        <div v-for="diary in records" :key="diary.id" class="lb-timeline-item">
          <div class="lb-timeline-dot" />
          <el-card shadow="never" class="lb-diary-card">
            <template #header>
              <div class="lb-diary-head">
                <span class="lb-diary-date">{{ diary.diaryDate }}</span>
                <WeatherBadge :weather="diary.weather" />
                <span v-if="diary.mood" class="lb-muted">心情：{{ diary.mood }}</span>
              </div>
            </template>
            <span class="lb-muted lb-diary-praise">👍 {{ formatCount(diary.praises) }}</span>
            <ContentRenderer :doc="docs.get(diary.id)" />
          </el-card>
        </div>
      </div>

      <PaginationBar v-model:page="page" :page-size="10" :total="total" :disabled="loading" />
    </div>
  </div>
</template>

<style scoped>
.lb-diary-title {
  margin-top: 0;
}

.lb-diary-alert {
  margin-bottom: 16px;
}

.lb-diary-empty {
  padding: 60px 0;
}

.lb-diary-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.lb-diary-praise {
  display: block;
  margin-bottom: 4px;
}

.lb-timeline {
  position: relative;
  padding-left: 20px;
}

.lb-timeline::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 4px;
  bottom: 4px;
  width: 2px;
  background: var(--lb-border);
}

.lb-timeline-item {
  position: relative;
  margin-bottom: 16px;
}

.lb-timeline-dot {
  position: absolute;
  left: -20px;
  top: 20px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--lb-primary);
  box-shadow: 0 0 0 3px rgba(24, 160, 88, 0.2);
}

.lb-diary-date {
  font-weight: 700;
}
</style>
