<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getPortalContent, praiseInteraction } from '@/api/portal'
import { ApiError } from '@/api/request'
import ContentRenderer from '@/components/ContentRenderer.vue'
import CommentTree from '@/components/CommentTree.vue'
import { mediaUrl, mediaSrcSet } from '@/utils/media'
import { formatCount, formatDateTime } from '@/utils/format'
import type { ContentDetailDTO } from '@/types/api'

const route = useRoute()

const content = ref<ContentDetailDTO | null>(null)
const loading = ref(false)
const errorMessage = ref('')

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
    <n-spin :show="loading">
      <n-empty v-if="errorMessage && !content" :description="errorMessage" class="lb-detail-empty" />

      <template v-if="content">
        <n-card>
          <img
            v-if="content.coverStorageKey"
            class="lb-gallery-image"
            :src="mediaUrl(content.coverStorageKey, 1280)"
            :srcset="mediaSrcSet(content.coverStorageKey)"
            sizes="(max-width: 1100px) 100vw, 1068px"
            alt=""
          />
          <n-space align="center" size="small" class="lb-gallery-meta">
            <n-h3 class="lb-gallery-name">{{ content.title }}</n-h3>
            <span class="lb-muted">👁 {{ formatCount(content.views) }}</span>
            <n-button size="tiny" secondary @click="togglePraise">👍 {{ formatCount(content.praises) }}</n-button>
          </n-space>
          <p class="lb-muted">{{ content.introduce }}</p>
          <p class="lb-muted">{{ formatDateTime(content.publishedAt ?? content.createdAt) }}</p>
          <ContentRenderer :doc="content.body" />
        </n-card>

        <n-card class="lb-comments-card">
          <CommentTree target-type="content" :target-id="content.id" />
        </n-card>
      </template>
    </n-spin>
  </div>
</template>

<style scoped>
.lb-detail-empty {
  padding: 80px 0;
}

.lb-gallery-image {
  width: 100%;
  border-radius: 10px;
  display: block;
}

.lb-gallery-meta {
  margin-top: 12px;
}

.lb-gallery-name {
  margin: 0;
}

.lb-comments-card {
  margin-top: 16px;
}
</style>
