<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { listPortalContents } from '@/api/portal'
import { ApiError } from '@/api/request'
import PaginationBar from '@/components/PaginationBar.vue'
import type { ContentView } from '@/types/api'

/**
 * 画廊瀑布流：CSS columns 方案（无 JS 布局依赖）。
 * ⚠️ 后端 ContentView 只有 coverMediaId，媒体端点按 storageKey 取图（?width=），
 * 暂无 storageKey → 卡片用占位块渲染（待后端在 ContentView 暴露 coverStorageKey，
 * 届时恢复 img + ?w=320|640|1280 srcset）。
 */
const route = useRoute()
const router = useRouter()

const records = ref<ContentView[]>([])
const total = ref(0)
const page = ref(Number(route.query.page ?? 1) || 1)
const loading = ref(false)
const errorMessage = ref('')

const columns = computed(() => (records.value.length > 0 ? records.value : []))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listPortalContents({
      contentType: 'image',
      page: page.value,
      pageSize: 24,
      sort: 'latest',
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '画廊加载失败'
    records.value = []
  } finally {
    loading.value = false
  }
}

watch(page, () => {
  void router.replace({ query: page.value > 1 ? { page: String(page.value) } : {} })
  void load()
})

onMounted(load)
</script>

<template>
  <div class="lb-gallery-wall">
    <n-h3 class="lb-gallery-title">画廊</n-h3>
    <n-spin :show="loading">
      <n-alert v-if="errorMessage" type="error" class="lb-wall-alert">{{ errorMessage }}</n-alert>
      <n-empty v-if="columns.length === 0 && !loading" description="暂无作品" class="lb-wall-empty" />
      <div v-else class="lb-masonry">
        <RouterLink v-for="item in columns" :key="item.id" :to="`/gallery/${item.id}`" class="lb-masonry-item">
          <div class="lb-masonry-media lb-cover-fallback" aria-hidden="true">🖼</div>
          <div class="lb-masonry-caption">
            <span class="lb-masonry-title">{{ item.title }}</span>
            <span class="lb-muted">👍 {{ item.praises }}</span>
          </div>
        </RouterLink>
      </div>
      <PaginationBar v-model:page="page" :page-size="24" :total="total" :disabled="loading" />
    </n-spin>
  </div>
</template>

<style scoped>
.lb-gallery-title {
  margin-top: 0;
}

.lb-wall-alert {
  margin-bottom: 16px;
}

.lb-wall-empty {
  padding: 60px 0;
}

.lb-masonry {
  columns: 4 240px;
  column-gap: 14px;
}

.lb-masonry-item {
  display: block;
  break-inside: avoid;
  margin-bottom: 14px;
  background: var(--lb-card-bg);
  border: 1px solid var(--lb-border);
  border-radius: 8px;
  overflow: hidden;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.lb-masonry-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.08);
}

.lb-masonry-item img {
  width: 100%;
  display: block;
}

.lb-masonry-media {
  width: 100%;
  height: 180px;
}

.lb-cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  background: linear-gradient(135deg, rgba(24, 160, 88, 0.12), rgba(24, 160, 88, 0.04));
}

.lb-masonry-caption {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
}

.lb-masonry-title {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
