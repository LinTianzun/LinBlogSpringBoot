<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { listPortalContents } from '@/api/portal'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import { mediaUrl, mediaSrcSet } from '@/utils/media'
import { formatCount, formatDate } from '@/utils/format'
import type { ContentSummaryDTO, LabelDTO } from '@/types/api'

const route = useRoute()
const router = useRouter()

const records = ref<ContentSummaryDTO[]>([])
const total = ref(0)
const page = ref(Number(route.query.page ?? 1) || 1)
const keyword = ref(typeof route.query.keyword === 'string' ? route.query.keyword : '')
const sort = ref<'latest' | 'hot'>((route.query.sort as 'latest' | 'hot') ?? 'latest')
const labelId = ref<number | null>(route.query.labelId ? Number(route.query.labelId) : null)
const labels = ref<LabelDTO[]>([])
const loading = ref(false)
const errorMessage = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listPortalContents({
      contentType: 'article',
      page: page.value,
      pageSize: 10,
      sort: sort.value,
      keyword: keyword.value || undefined,
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

function syncQuery(): void {
  void router.replace({
    query: {
      ...(page.value > 1 ? { page: String(page.value) } : {}),
      ...(keyword.value ? { keyword: keyword.value } : {}),
      ...(sort.value !== 'latest' ? { sort: sort.value } : {}),
      ...(labelId.value ? { labelId: String(labelId.value) } : {}),
    },
  })
}

watch([page, sort, labelId], () => {
  syncQuery()
  void load()
})

function search(): void {
  page.value = 1
  syncQuery()
  void load()
}

onMounted(load)
</script>

<template>
  <div class="lb-article-list">
    <n-card class="lb-toolbar">
      <n-space align="center" size="small">
        <n-input
          v-model:value="keyword"
          placeholder="搜索文章"
          clearable
          size="small"
          style="width: 220px"
          @keyup.enter="search"
        />
        <n-button size="small" type="primary" @click="search">搜索</n-button>
        <n-radio-group v-model:value="sort" size="small">
          <n-radio-button value="latest">最新</n-radio-button>
          <n-radio-button value="hot">最热</n-radio-button>
        </n-radio-group>
      </n-space>
    </n-card>

    <n-spin :show="loading">
      <n-alert v-if="errorMessage" type="error" class="lb-list-alert">{{ errorMessage }}</n-alert>
      <n-empty v-if="records.length === 0 && !loading" description="暂无文章" class="lb-list-empty" />

      <n-space vertical size="medium">
        <n-card v-for="item in records" :key="item.id" class="lb-article-card">
          <div class="lb-article-row">
            <img
              v-if="item.coverStorageKey"
              class="lb-article-cover"
              :src="mediaUrl(item.coverStorageKey, 320)"
              :srcset="mediaSrcSet(item.coverStorageKey)"
              sizes="(max-width: 600px) 100vw, 200px"
              loading="lazy"
              alt=""
            />
            <div class="lb-article-info">
              <RouterLink :to="`/articles/${item.id}`" class="lb-article-title">
                {{ item.title }}
              </RouterLink>
              <p class="lb-muted lb-article-intro">{{ item.introduce }}</p>
              <n-space size="small" align="center">
                <n-tag v-for="label in item.labels" :key="label.id" size="small" round>{{ label.name }}</n-tag>
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
  object-fit: cover;
  border-radius: 8px;
  flex-shrink: 0;
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
