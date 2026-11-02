<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { getPortalHome, listPortalContents, listPortalDiaries } from '@/api/portal'
import { ApiError } from '@/api/request'
import { formatCount } from '@/utils/format'
import { weatherEmoji } from '@/utils/weather'
import type { ContentView, DiaryDTO, PortalHomeDTO } from '@/types/api'

/**
 * 门户首页：GET /portal/home 返回 {latest, pinned, labels}（backend PortalController），
 * 画廊/日记预览按契约补充：/portal/contents?contentType=image&pageSize=4、/portal/diaries?pageSize=5，
 * 三组请求 allSettled 并行，任一失败各自降级为空态。
 * ⚠️ ContentView 只有 coverMediaId，媒体端点按 storageKey 取图 → 封面暂渲染占位块（待后端暴露 key）。
 */

const home = ref<Partial<PortalHomeDTO> | null>(null)
const galleryPreview = ref<ContentView[]>([])
const diaryPreview = ref<DiaryDTO[]>([])
const loading = ref(false)
const errorMessage = ref('')

const pinned = computed(() => home.value?.pinned ?? [])
const latest = computed(() => home.value?.latest ?? [])
const labels = computed(() => home.value?.labels ?? [])

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  const [homeResult, galleryResult, diaryResult] = await Promise.allSettled([
    getPortalHome(),
    listPortalContents({ contentType: 'image', page: 1, pageSize: 4 }),
    listPortalDiaries({ page: 1, pageSize: 5 }),
  ])
  if (homeResult.status === 'fulfilled') {
    home.value = homeResult.value
  } else {
    errorMessage.value =
      homeResult.reason instanceof ApiError ? homeResult.reason.message : '加载失败'
  }
  galleryPreview.value = galleryResult.status === 'fulfilled' ? galleryResult.value.records : []
  diaryPreview.value = diaryResult.status === 'fulfilled' ? diaryResult.value.records : []
  loading.value = false
}

onMounted(load)
</script>

<template>
  <div class="lb-portal-home">
    <div v-loading="loading">
      <el-alert v-if="errorMessage" type="error" class="lb-home-alert" :closable="false">
        {{ errorMessage }}
        <el-button size="small" link @click="load">重试</el-button>
      </el-alert>

      <div class="lb-home-stack">
        <!-- 置顶 -->
        <section v-if="pinned.length > 0" class="lb-card">
          <h3>置顶推荐</h3>
          <div class="lb-pinned-stack">
            <RouterLink v-for="item in pinned" :key="item.id" :to="`/articles/${item.id}`" class="lb-post-row">
              <el-tag type="warning" size="small">置顶</el-tag>
              <span class="lb-post-title">{{ item.title }}</span>
              <span class="lb-muted">{{ formatCount(item.views) }} 阅读</span>
            </RouterLink>
          </div>
        </section>

        <!-- 最新文章 -->
        <section class="lb-card">
          <h3>最新文章</h3>
          <el-empty v-if="latest.length === 0" description="暂无文章" :image-size="60" />
          <div v-else class="lb-latest-list">
            <div v-for="item in latest" :key="item.id" class="lb-latest-row">
              <div class="lb-latest-cover lb-cover-fallback" aria-hidden="true">📝</div>
              <div class="lb-latest-text">
                <RouterLink :to="`/articles/${item.id}`" class="lb-post-title">{{ item.title }}</RouterLink>
                <p class="lb-muted lb-intro">{{ item.introduce }}</p>
                <div class="lb-latest-meta">
                  <el-tag v-for="label in item.labels" :key="label" size="small" effect="plain" round>
                    {{ label }}
                  </el-tag>
                  <span class="lb-muted">
                    👁 {{ formatCount(item.views) }} · 👍 {{ formatCount(item.praises) }} · 💬 {{ formatCount(item.commentsCount) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <div class="lb-home-grid">
          <!-- 画廊预览 -->
          <section class="lb-card lb-preview-card">
            <div class="lb-section-head">
              <h3>画廊</h3>
              <RouterLink to="/gallery" class="lb-muted">更多 →</RouterLink>
            </div>
            <el-empty v-if="galleryPreview.length === 0" description="暂无作品" :image-size="60" />
            <div v-else class="lb-gallery-strip">
              <RouterLink v-for="item in galleryPreview" :key="item.id" :to="`/gallery/${item.id}`">
                <div class="lb-gallery-cover lb-cover-fallback" aria-hidden="true">🖼</div>
              </RouterLink>
            </div>
          </section>

          <!-- 日记预览 -->
          <section class="lb-card lb-preview-card">
            <div class="lb-section-head">
              <h3>日记</h3>
              <RouterLink to="/diary" class="lb-muted">更多 →</RouterLink>
            </div>
            <el-empty v-if="diaryPreview.length === 0" description="暂无日记" :image-size="60" />
            <div v-else class="lb-diary-stack">
              <RouterLink v-for="diary in diaryPreview" :key="diary.id" to="/diary" class="lb-diary-row">
                <span>{{ diary.diaryDate }}</span>
                <span v-if="weatherEmoji(diary.weather)">{{ weatherEmoji(diary.weather) }}</span>
                <span class="lb-muted lb-diary-excerpt">{{ diary.mood ?? '' }}</span>
              </RouterLink>
            </div>
          </section>
        </div>

        <!-- 标签云 -->
        <section v-if="labels.length > 0" class="lb-card">
          <h3>标签</h3>
          <div class="lb-tag-cloud">
            <RouterLink v-for="label in labels" :key="label.id" :to="`/articles?labelId=${label.id}`">
              <el-tag round effect="plain" type="primary">{{ label.name }}</el-tag>
            </RouterLink>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lb-home-alert {
  margin-bottom: 16px;
}

.lb-home-stack {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.lb-home-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 900px) {
  .lb-home-grid {
    grid-template-columns: 1fr;
  }
}

.lb-pinned-stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lb-post-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.lb-post-title {
  color: var(--lb-text);
  font-weight: 600;
}

.lb-post-title:hover {
  color: var(--lb-primary);
}

.lb-latest-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lb-latest-row {
  display: flex;
  gap: 12px;
}

.lb-latest-cover {
  width: 160px;
  height: 96px;
  flex-shrink: 0;
}

.lb-cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  background: linear-gradient(135deg, rgba(24, 160, 88, 0.12), rgba(24, 160, 88, 0.04));
  border-radius: 6px;
}

.lb-intro {
  margin: 4px 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.lb-latest-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.lb-preview-card {
  height: 100%;
}

.lb-section-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.lb-gallery-strip {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.lb-gallery-cover {
  width: 100%;
  height: 90px;
}

.lb-diary-stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lb-diary-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
}

.lb-diary-excerpt {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
