<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getPortalContent, praiseInteraction, reportView } from '@/api/portal'
import { ApiError } from '@/api/request'
import ContentRenderer from '@/components/ContentRenderer.vue'
import CommentTree from '@/components/CommentTree.vue'
import { parseTiptapDoc } from '@/components/renderContent'
import { formatCount, formatDateTime } from '@/utils/format'
import type { ContentView } from '@/types/api'

/**
 * 作品详情：GET /portal/contents/{id}；⚠️ 无 coverStorageKey（仅 coverMediaId），
 * 大图暂用占位块（待后端暴露 storageKey 后恢复 1280 图 + srcset）。
 */

const route = useRoute()

const content = ref<ContentView | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const doc = computed(() => parseTiptapDoc(content.value?.body))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  const id = Number(route.params.id)
  if (!Number.isFinite(id)) {
    errorMessage.value = '无效的作品 ID'
    loading.value = false
    return
  }
  try {
    content.value = await getPortalContent(id)
    void reportView('content', id).catch(() => undefined)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '作品加载失败'
  } finally {
    loading.value = false
  }
}

async function togglePraise(): Promise<void> {
  if (!content.value) return
  try {
    const result = await praiseInteraction('content', content.value.id)
    content.value = { ...content.value, praises: result.count }
  } catch {
    // 失败不打断
  }
}

onMounted(load)
</script>

<template>
  <div class="lb-gallery-detail">
    <div v-loading="loading">
      <el-empty v-if="errorMessage && !content" :description="errorMessage" class="lb-detail-empty" />

      <template v-if="content">
        <el-card shadow="never">
          <div class="lb-gallery-image lb-cover-fallback" aria-hidden="true">🖼</div>
          <div class="lb-gallery-meta">
            <h3 class="lb-gallery-name">{{ content.title }}</h3>
            <span class="lb-muted">👁 {{ formatCount(content.views) }}</span>
            <el-button size="small" @click="togglePraise">👍 {{ formatCount(content.praises) }}</el-button>
          </div>
          <p class="lb-muted">{{ content.introduce }}</p>
          <p class="lb-muted">{{ formatDateTime(content.publishedAt ?? content.createdAt) }}</p>
          <ContentRenderer :doc="doc" />
        </el-card>

        <el-card shadow="never" class="lb-comments-card">
          <CommentTree target-type="image" :target-id="content.id" />
        </el-card>
      </template>
    </div>
  </div>
</template>

<style scoped>
.lb-detail-empty {
  padding: 80px 0;
}

.lb-gallery-image {
  width: 100%;
  height: 420px;
  border-radius: 10px;
}

.lb-cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48px;
  background: linear-gradient(135deg, rgba(24, 160, 88, 0.12), rgba(24, 160, 88, 0.04));
}

.lb-gallery-meta {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.lb-gallery-name {
  margin: 0;
}

.lb-comments-card {
  margin-top: 16px;
}
</style>
