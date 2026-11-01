<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { listLabels } from '@/api/admin'
import { listPortalContents } from '@/api/portal'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import { formatCount, formatDate } from '@/utils/format'
import type { ContentView, LabelDTO } from '@/types/api'

/**
 * 文章列表：GET /portal/contents?contentType=article（门户口径恒 published）。
 * 标签过滤项来自 GET /labels（公开全量）。
 * ⚠️ 后端 portalPage 未消费 keyword/sort——keyword 搜索暂不提供（列入偏差）；
 * sort 仍随请求发送，后端就绪即生效。
 */

const route = useRoute()
const router = useRouter()

const records = ref<ContentView[]>([])
const total = ref(0)
const page = ref(Number(route.query.page ?? 1) || 1)
const sort = ref<'latest' | 'hot'>((route.query.sort as 'latest' | 'hot') ?? 'latest')
const labelId = ref<number | null>(route.query.labelId ? Number(route.query.labelId) : null)
const labels = ref<LabelDTO[]>([])
const loading = ref(false)
const errorMessage = ref('')

const activeLabel = computed(() => labels.value.find((item) => item.id === labelId.value) ?? null)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listPortalContents({
      contentType: 'article',
      page: page.value,
      pageSize: 10,
      sort: sort.value,
      labelId: labelId.value ?? undefined,
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '文章加载失败'
    records.value = []
  } finally {
    loading.value = false
  }
}

async function loadLabels(): Promise<void> {
  try {
    labels.value = await listLabels()
  } catch {
    labels.value = [] // 标签栏降级为空，不影响列表
  }
}

function syncQuery(): void {
  void router.replace({
    query: {
      ...(page.value > 1 ? { page: String(page.value) } : {}),
      ...(sort.value !== 'latest' ? { sort: sort.value } : {}),
      ...(labelId.value ? { labelId: String(labelId.value) } : {}),
    },
  })
}

watch([page, sort, labelId], () => {
  syncQuery()
  void load()
})

function pickLabel(id: number | null): void {
  page.value = 1
  labelId.value = id
}

onMounted(() => {
  void load()
  void loadLabels()
})
</script>

<template>
  <div class="lb-article-list">
    <n-card class="lb-toolbar">
      <n-space align="center" size="small">
        <n-radio-group v-model:value="sort" size="small">
          <n-radio-button value="latest">最新</n-radio-button>
          <n-radio-button value="hot">最热</n-radio-button>
        </n-radio-group>
        <n-select
          v-if="labels.length > 0"
          :value="labelId"
          clearable
          filterable
          placeholder="按标签过滤"
          size="small"
          style="width: 200px"
          :options="labels.map((item) => ({ label: item.name, value: item.id }))"
          @update:value="pickLabel"
        />
      </n-space>
    </n-card>

    <n-spin :show="loading">
      <n-alert v-if="errorMessage" type="error" class="lb-list-alert">{{ errorMessage }}</n-alert>
      <n-alert v-if="activeLabel" type="info" class="lb-list-alert" closable @close="pickLabel(null)">
        标签「{{ activeLabel.name }}」下的文章
      </n-alert>
      <n-empty v-if="records.length === 0 && !loading" description="暂无文章" class="lb-list-empty" />

      <n-space vertical size="medium">
        <n-card v-for="item in records" :key="item.id" class="lb-article-card">
          <div class="lb-article-row">
            <div class="lb-article-cover lb-cover-fallback" aria-hidden="true">📝</div>
            <div class="lb-article-info">
              <RouterLink :to="`/articles/${item.id}`" class="lb-article-title">
                {{ item.title }}
              </RouterLink>
              <p class="lb-muted lb-article-intro">{{ item.introduce }}</p>
              <n-space size="small" align="center">
                <n-tag v-for="label in item.labels" :key="label" size="small" round>{{ label }}</n-tag>
                <span class="lb-muted">
                  {{ formatDate(item.publishedAt ?? item.createdAt) }} · 👁 {{ formatCount(item.views) }} · 👍
                  {{ formatCount(item.praises) }} · 💬 {{ formatCount(item.commentsCount) }}
                </span>
              </n-space>
            </div>
          </div>
        </n-card>
      </n-space>

      <PaginationBar v-model:page="page" :page-size="10" :total="total" :disabled="loading" />
    </n-spin>
  </div>
</template>

<style scoped>
.lb-toolbar {
  margin-bottom: 16px;
}

.lb-list-alert {
  margin-bottom: 16px;
}

.lb-list-empty {
  padding: 60px 0;
}

.lb-article-row {
  display: flex;
  gap: 16px;
}

.lb-article-cover {
  width: 200px;
  height: 120px;
  flex-shrink: 0;
}

.lb-cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  background: linear-gradient(135deg, rgba(24, 160, 88, 0.12), rgba(24, 160, 88, 0.04));
  border-radius: 8px;
}

.lb-article-title {
  font-size: 17px;
  font-weight: 700;
  color: var(--lb-text);
}

.lb-article-title:hover {
  color: var(--lb-primary);
}

.lb-article-intro {
  margin: 6px 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
