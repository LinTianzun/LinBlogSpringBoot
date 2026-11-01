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
 * 文章详情：GET /portal/contents/{id}（?preview=1 作者/管理者预览非 published）。
 * body 是 ProseMirror JSON 字符串 → parseTiptapDoc 防御性解析后交给渲染器。
 * 浏览：GET 端点服务端已顺带记（30 分钟去重）；POST /views 双保险，失败静默。
 * 点赞 /interactions 属 M3（未交付）→ 失败静默不打断阅读。
 */

const route = useRoute()

const content = ref<ContentView | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const praised = ref(false)

const doc = computed(() => parseTiptapDoc(content.value?.body))

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  const id = Number(route.params.id)
  if (!Number.isFinite(id)) {
    errorMessage.value = '无效的文章 ID'
    loading.value = false
    return
  }
  try {
    // ?preview=1：作者/管理者预览草稿（§5.3）
    content.value = await getPortalContent(id, route.query.preview === '1')
    // 浏览上报双保险：服务端 GET 已记，POST /views 兜底（30 分钟去重），失败静默
    void reportView('content', id).catch(() => undefined)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '文章加载失败'
  } finally {
    loading.value = false
  }
}

async function togglePraise(): Promise<void> {
  if (!content.value) return
  try {
    const result = await praiseInteraction('content', content.value.id)
    praised.value = result.active
    content.value = { ...content.value, praises: result.count }
  } catch {
    // M3 未交付/失败：不打断阅读
  }
}

onMounted(load)
</script>

<template>
  <div class="lb-article-detail">
    <n-spin :show="loading">
      <n-empty v-if="errorMessage && !content" :description="errorMessage" class="lb-detail-empty" />

      <template v-if="content">
        <n-card>
          <n-h1 class="lb-detail-title">{{ content.title }}</n-h1>
          <n-space size="small" align="center" class="lb-detail-meta">
            <span class="lb-muted">{{ content.authorName ?? '佚名' }}</span>
            <span class="lb-muted">{{ formatDateTime(content.publishedAt ?? content.createdAt) }}</span>
            <span class="lb-muted">👁 {{ formatCount(content.views) }}</span>
            <n-tag v-for="label in content.labels" :key="label" size="small" round>{{ label }}</n-tag>
          </n-space>

          <ContentRenderer :doc="doc" />

          <template #action>
            <n-space justify="center">
              <n-button :type="praised ? 'primary' : 'default'" secondary @click="togglePraise">
                👍 点赞 {{ formatCount(content.praises) }}
              </n-button>
            </n-space>
          </template>
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

.lb-detail-title {
  margin-top: 0;
}

.lb-detail-meta {
  margin-bottom: 20px;
}

.lb-comments-card {
  margin-top: 16px;
}
</style>
