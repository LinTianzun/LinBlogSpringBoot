<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { getPortalContent, listPortalContentMedia, praiseInteraction, reportView } from '@/api/portal'
import { ApiError } from '@/api/request'
import ContentRenderer from '@/components/ContentRenderer.vue'
import CommentTree from '@/components/CommentTree.vue'
import { parseTiptapDoc } from '@/components/renderContent'
import { formatCount, formatDateTime } from '@/utils/format'
import type { ContentView, PortalMediaDTO } from '@/types/api'

/**
 * 作品详情（方案 A · 亮卡片）：GET /portal/contents/{id}。
 * 构成：面包屑 → Newsreader 大标题 + 导语 + 作者圈/时间/浏览/点赞 → 主图大卡（真实,
 * 1280 srcset, 圆角 .lb-ed-card 包一层的白卡，点击开灯箱）→ 正文（白卡包裹）
 * → 评论区（.lb-ed-section-head 风格抬头）。灯箱 Teleport + Esc/点遮罩关闭。
 * 图片走 /api/v1/media/{coverStorageKey}?width=320|640|1280（参数名 width）。断链回退占位。
 */
const route = useRoute()

const content = ref<ContentView | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const imgBroken = ref(false)
const lightboxOpen = ref(false)

/* ---- 图集（多图）：main 图跟随选中缩略图；空列表回退封面 ---- */
const mediaList = ref<PortalMediaDTO[]>([])
const activeIndex = ref(0)

const doc = computed(() => parseTiptapDoc(content.value?.body))

function storageKey(): string {
  return content.value?.coverStorageKey ?? ''
}

/** 当前主图 key：图集非空取选中项，否则封面 */
const currentKey = computed(() => {
  if (mediaList.value.length > 0) {
    return mediaList.value[activeIndex.value]?.storageKey ?? storageKey()
  }
  return storageKey()
})

const hasImage = computed(() => currentKey.value.length > 0 && !imgBroken.value)
const imageUrl = computed(() => (hasImage.value ? `/api/v1/media/${currentKey.value}?width=1280` : ''))
const imageSrcset = computed(() =>
  hasImage.value
    ? [320, 640, 1280].map((w) => `/api/v1/media/${currentKey.value}?width=${w} ${w}w`).join(', ')
    : undefined,
)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  imgBroken.value = false
  const id = Number(route.params.id)
  if (!Number.isFinite(id)) {
    errorMessage.value = '无效的作品 ID'
    loading.value = false
    return
  }
  try {
    content.value = await getPortalContent(id)
    void reportView('content', id).catch(() => undefined)
    // 图集（多图）失败静默：回退封面展示
    mediaList.value = await listPortalContentMedia(id).catch(() => [])
    activeIndex.value = 0
    imgBroken.value = false
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

function openLightbox(): void {
  if (hasImage.value) lightboxOpen.value = true
}

function closeLightbox(): void {
  lightboxOpen.value = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') lightboxOpen.value = false
}

onMounted(() => {
  void load()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="lb-ed lb-work">
    <div v-loading="loading" class="lb-ed-wrap">
      <el-empty v-if="errorMessage && !content" :description="errorMessage" class="lb-work-empty" />

      <template v-if="content">
        <nav class="lb-work-crumb lb-ed-caption">
          <RouterLink to="/gallery" class="lb-ed-link">画廊</RouterLink>
          <span class="lb-work-sep" aria-hidden="true">/</span>
          <span class="lb-work-current">{{ content.title || '未命名作品' }}</span>
        </nav>

        <header class="lb-work-head">
          <p v-if="content.labels && content.labels.length" class="lb-work-pills">
            <span v-for="label in content.labels.slice(0, 3)" :key="label" class="lb-ed-pill lb-ed-pill--soft">
              {{ label }}
            </span>
          </p>
          <h1 class="lb-ed-display lb-work-title">{{ content.title || '未命名作品' }}</h1>
          <p v-if="content.introduce" class="lb-ed-lead lb-work-intro">
            {{ content.introduce }}
          </p>

          <div class="lb-work-meta">
            <span v-if="content.authorName" class="lb-work-author">
              <span class="lb-ed-avatar lb-work-avatar" aria-hidden="true">{{ content.authorName.slice(0, 1) }}</span>
              {{ content.authorName }}
            </span>
            <span class="lb-work-dot" aria-hidden="true">·</span>
            <span>{{ formatCount(content.views) }} 浏览</span>
            <span class="lb-work-dot" aria-hidden="true">·</span>
            <span>{{ formatDateTime(content.publishedAt ?? content.createdAt) }}</span>
            <button type="button" class="lb-ed-btn lb-ed-btn--primary lb-work-praise" @click="togglePraise">
              ♥ 赞 {{ formatCount(content.praises) }}
            </button>
          </div>
        </header>

        <!-- 主导大图（白卡包裹） -->
        <figure
          class="lb-ed-card lb-work-figure"
          :class="{ 'is-clickable': hasImage }"
          @click="openLightbox"
        >
          <div class="lb-ed-thumb lb-work-media">
            <img
              v-if="hasImage"
              class="lb-work-img"
              :src="imageUrl"
              :srcset="imageSrcset"
              sizes="(max-width: 1180px) 100vw, 1180px"
              :alt="content.title"
              decoding="async"
              @error="imgBroken = true"
            />
            <div v-else class="lb-work-fallback" aria-hidden="true"></div>
          </div>
        </figure>

        <!-- 图集缩略图条（>1 张时出现） -->
        <div v-if="mediaList.length > 1" class="lb-work-thumbs" role="tablist" aria-label="作品图集">
          <button
            v-for="(m, i) in mediaList"
            :key="m.mediaId"
            type="button"
            class="lb-work-thumb"
            :class="{ 'is-active': i === activeIndex }"
            :aria-label="`查看第 ${i + 1} 张`"
            @click="activeIndex = i; imgBroken = false"
          >
            <img :src="`/api/v1/media/${m.storageKey}?width=320`" :alt="`第 ${i + 1} 张`" loading="lazy" />
          </button>
        </div>

        <div v-if="doc" class="lb-ed-card lb-work-body">
          <ContentRenderer :doc="doc" />
        </div>

        <section class="lb-work-comments">
          <div class="lb-ed-section-head lb-work-comments-head">
            <h2 class="lb-ed-head">评论</h2>
            <span class="lb-ed-caption">{{ formatCount(content.commentsCount) }} 条</span>
          </div>
          <CommentTree target-type="image" :target-id="content.id" />
        </section>
      </template>
    </div>

    <Teleport to="body">
      <div v-if="lightboxOpen" class="lb-lightbox" @click="closeLightbox">
        <img
          class="lb-lightbox-img"
          :src="`/api/v1/media/${currentKey}?width=1280`"
          :alt="content?.title"
          @click.stop
        />
        <button class="lb-lightbox-close" type="button" aria-label="关闭" @click="closeLightbox">✕</button>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.lb-work {
  padding-top: clamp(20px, 3vw, 36px);
  padding-bottom: clamp(40px, 6vw, 88px);
}

.lb-work-empty {
  padding: clamp(60px, 10vw, 140px) 0;
}

/* ---------- 面包屑 ---------- */
.lb-work-crumb {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: clamp(24px, 3.6vw, 44px);
}

.lb-work-sep {
  opacity: 0.5;
}

.lb-work-current {
  color: var(--ed-ink-soft);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 头部 ---------- */
.lb-work-head {
  display: flex;
  flex-direction: column;
  gap: clamp(16px, 2.4vw, 24px);
  padding-bottom: clamp(28px, 4vw, 48px);
  max-width: var(--ed-wrap);
}

.lb-work-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
}

.lb-work-title {
  color: var(--ed-ink);
  margin: 0;
  overflow-wrap: anywhere;
}

.lb-work-intro {
  margin: 0;
  max-width: 56ch;
}

.lb-work-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

.lb-work-author {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--ed-ink);
  font-weight: 600;
}

.lb-work-avatar {
  width: 28px;
  height: 28px;
  font-size: 13px;
}

.lb-work-dot {
  opacity: 0.45;
}

.lb-work-praise {
  margin-left: auto;
  min-height: 40px;
  padding: 0 20px;
}

/* ---------- 主导大图 ---------- */
.lb-work-figure {
  margin: 0;
  padding: 0;
  width: 100%;
}

/* 图集缩略图条 */
.lb-work-thumbs {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin: 0 0 clamp(24px, 3vw, 36px);
}

.lb-work-thumb {
  width: 96px;
  aspect-ratio: 4 / 3;
  padding: 0;
  border: 2px solid var(--ed-rule-soft);
  border-radius: var(--ed-radius-sm);
  overflow: hidden;
  cursor: pointer;
  background: var(--ed-card);
  transition: border-color var(--lb-dur-fast, 160ms) ease;
}

.lb-work-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.lb-work-thumb.is-active {
  border-color: var(--ed-accent);
}

.lb-work-thumb:hover {
  border-color: var(--ed-accent-ink);
}

.lb-work-figure.is-clickable {
  cursor: zoom-in;
}

.lb-work-media {
  border-radius: var(--ed-radius-lg);
  min-height: 180px;
}

.lb-work-img {
  display: block;
  width: 100%;
  height: auto;
  max-height: 82vh;
  object-fit: contain;
}

.lb-work-fallback {
  width: 100%;
  aspect-ratio: 4 / 3;
  background: var(--ed-accent-soft);
}

/* ---------- 正文（白卡包裹） ---------- */
.lb-work-body {
  max-width: var(--ed-measure);
  margin: clamp(40px, 6vw, 72px) auto 0;
  padding: clamp(24px, 3.4vw, 44px);
  font-size: var(--ed-step-body);
  line-height: 1.8;
  color: var(--ed-ink);
}

.lb-work-body :deep(.lb-content img) {
  max-width: 100%;
  border-radius: var(--ed-radius-md);
  margin: 1.4em 0;
}

.lb-work-body :deep(.lb-content p) {
  margin: 0 0 1.35em;
}

.lb-work-body :deep(.lb-content blockquote) {
  margin: 1.6em 0;
  padding-left: 22px;
  border-left: 3px solid var(--ed-accent);
  color: var(--ed-ink-soft);
  font-style: italic;
}

/* ---------- 评论 ---------- */
.lb-work-comments {
  max-width: var(--ed-measure);
  margin: 0 auto;
  padding-top: clamp(48px, 7vw, 96px);
}

.lb-work-comments-head {
  margin-bottom: clamp(20px, 3vw, 32px);
}

/* ---------- 灯箱 ---------- */
.lb-lightbox {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4vh 4vw;
  background: rgba(0, 0, 0, 0.88);
  cursor: zoom-out;
}

.lb-lightbox-img {
  max-width: 100%;
  max-height: 92vh;
  border-radius: var(--ed-radius-md);
  cursor: default;
}

.lb-lightbox-close {
  position: absolute;
  top: 22px;
  right: 26px;
  width: 42px;
  height: 42px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 50%;
  font-size: 16px;
  color: #fff;
  background: transparent;
  cursor: pointer;
  transition: background 160ms ease;
}

.lb-lightbox-close:hover {
  background: rgba(255, 255, 255, 0.16);
}

@media (max-width: 640px) {
  .lb-work-praise {
    margin-left: 0;
  }

  .lb-work-body {
    padding: clamp(18px, 4vw, 26px);
  }
}
</style>
