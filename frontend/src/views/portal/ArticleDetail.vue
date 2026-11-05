<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getPortalContent, praiseInteraction, reportView } from '@/api/portal'
import { ApiError } from '@/api/request'
import ContentRenderer from '@/components/ContentRenderer.vue'
import CommentTree from '@/components/CommentTree.vue'
import CoverImage from '@/components/CoverImage.vue'
import { parseTiptapDoc } from '@/components/renderContent'
import { formatCount, formatDateTime } from '@/utils/format'
import type { ContentView } from '@/types/api'

/**
 * 文章详情（亮卡片 · 方案 A）：GET /portal/contents/{id}（?preview=1 作者/管理者预览）。
 * body 是 ProseMirror JSON 字符串 → parseTiptapDoc 防御解析 → ContentRenderer。
 * 浏览：GET 已顺带记（30 分钟去重）+ POST /views 双保险，失败静默；点赞 toggle 失败静默。
 *
 * 构成：顶部 3px 主题色阅读进度；文章头（分类 pill + Newsreader 大标题 + 作者/时间/阅读/标签）；
 * 有封面时宽幅大图（16:9）；正文包在白色圆角卡里（行高 1.75，图片圆角 + 点击放大灯箱）；
 * 点赞 pill（乐观更新 + 回滚）；评论 CommentTree 保留。颜色全走 --ed-* 变量。
 */
const route = useRoute()

const content = ref<ContentView | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const praised = ref(false)

/** 阅读进度（0–100） */
const progress = ref(0)
/** 正文图片灯箱 */
const lightboxSrc = ref('')

const doc = computed(() => parseTiptapDoc(content.value?.body))

const coverStorageKey = computed<string>(() => content.value?.coverStorageKey ?? '')

const authorInitial = computed(() => (content.value?.authorName ?? '佚').trim().charAt(0) || '佚')

/** 估算阅读时长（分钟），按中文 ~350 字/分钟粗算 */
const readingMinutes = computed(() => {
  const text = content.value?.body ? (content.value?.plainText ?? '') : ''
  const len = text.length || (content.value?.introduce?.length ?? 0) * 8
  return Math.max(1, Math.round(len / 350))
})

/* ---------- 进入渐显（编排；尊重 reduced-motion） ---------- */
const reducedMotion =
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

const revealed = ref(false)

function armReveal(): void {
  if (reducedMotion) {
    revealed.value = true
    return
  }
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      revealed.value = true
    })
  })
}

function onScroll(): void {
  const el = document.documentElement
  const scrollable = el.scrollHeight - el.clientHeight
  const top = window.scrollY || el.scrollTop
  progress.value = scrollable > 0 ? Math.min(100, Math.max(0, (top / scrollable) * 100)) : 0
}

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
    content.value = await getPortalContent(id, route.query.preview === '1')
    void reportView('content', id).catch(() => undefined)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '文章加载失败'
  } finally {
    loading.value = false
    await nextTick()
    armReveal()
  }
}

async function togglePraise(): Promise<void> {
  if (!content.value) return
  const prevPraised = praised.value
  const prevCount = content.value.praises ?? 0
  praised.value = !prevPraised
  content.value = { ...content.value, praises: prevCount + (praised.value ? 1 : -1) }
  try {
    const result = await praiseInteraction('content', content.value.id)
    praised.value = result.active
    content.value = { ...content.value, praises: result.count }
  } catch {
    praised.value = prevPraised
    if (content.value) content.value = { ...content.value, praises: prevCount }
  }
}

function onContentClick(event: MouseEvent): void {
  const target = event.target as HTMLElement | null
  if (target && target.tagName === 'IMG') {
    const src = (target as HTMLImageElement).src
    if (src) lightboxSrc.value = src
  }
}

function closeLightbox(): void {
  lightboxSrc.value = ''
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') lightboxSrc.value = ''
}

onMounted(() => {
  void load()
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('keydown', onKeydown)
  onScroll()
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="lb-ed lb-article">
    <!-- 阅读进度：主题色 3px -->
    <div class="lb-read-progress" aria-hidden="true">
      <div class="lb-read-fill" :style="{ width: progress + '%' }"></div>
    </div>

    <div v-loading="loading" class="lb-ed-wrap lb-article-stage">
      <div v-if="errorMessage && !content" class="lb-ed-empty">
        {{ errorMessage }}
      </div>

      <template v-if="content">
        <!-- 文章头 -->
        <header class="lb-article-head">
          <div class="lb-head-labels">
            <span v-for="label in content.labels" :key="label" class="lb-ed-pill">{{ label }}</span>
          </div>
          <h1 class="lb-ed-h1 lb-article-title">{{ content.title }}</h1>

          <div class="lb-head-meta">
            <span class="lb-head-author">
              <span class="lb-ed-avatar" aria-hidden="true">{{ authorInitial }}</span>
              {{ content.authorName ?? '佚名' }}
            </span>
            <span class="lb-head-dot" aria-hidden="true">·</span>
            <span>{{ formatDateTime(content.publishedAt ?? content.createdAt) }}</span>
            <span class="lb-head-dot" aria-hidden="true">·</span>
            <span>{{ formatCount(content.views) }} 阅读</span>
            <span class="lb-head-dot" aria-hidden="true">·</span>
            <span>约 {{ readingMinutes }} 分钟</span>
          </div>
        </header>

        <!-- 宽幅封面 -->
        <div v-if="coverStorageKey" class="lb-article-cover-wrap">
          <CoverImage
            class="lb-article-cover"
            :storage-key="coverStorageKey"
            ratio="16/9"
            sizes="(max-width: 1180px) 100vw, 1180px"
            :alt="content.title"
          />
        </div>

        <!-- 正文白卡 -->
        <article
          class="lb-ed-card lb-article-main"
          :class="{ 'lb-ed-reveal': !reducedMotion, 'is-armed': !reducedMotion, 'is-in': revealed }"
          @click="onContentClick"
        >
          <div class="lb-article-body">
            <ContentRenderer :doc="doc" />
          </div>

          <!-- 点赞 pill -->
          <div class="lb-article-praise">
            <button
              type="button"
              class="lb-praise"
              :class="{ 'is-on': praised }"
              :aria-pressed="praised"
              @click="togglePraise"
            >
              <span class="lb-praise-heart" aria-hidden="true">{{ praised ? '♥' : '♡' }}</span>
              <span class="lb-praise-word">{{ praised ? '已赞' : '点赞' }}</span>
              <span class="lb-praise-num">{{ formatCount(content.praises) }}</span>
            </button>
          </div>
        </article>

        <!-- 评论 -->
        <section
          class="lb-article-comments lb-ed-reveal"
          :class="{ 'is-armed': !reducedMotion, 'is-in': revealed }"
        >
          <h2 class="lb-ed-head lb-comments-head">评论</h2>
          <div class="lb-ed-card lb-comments-card">
            <CommentTree target-type="article" :target-id="content.id" />
          </div>
        </section>
      </template>
    </div>

    <!-- 图片灯箱 -->
    <Teleport to="body">
      <transition name="lb-fade">
        <div v-if="lightboxSrc" class="lb-lightbox" @click="closeLightbox">
          <img :src="lightboxSrc" alt="预览" class="lb-lightbox-img" @click.stop />
          <button type="button" class="lb-lightbox-close" aria-label="关闭" @click="closeLightbox">×</button>
        </div>
      </transition>
    </Teleport>
  </div>
</template>

<style scoped>
.lb-article {
  position: relative;
  padding-bottom: clamp(48px, 7vw, 96px);
}

/* ---------- 进度 ---------- */
.lb-read-progress {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  z-index: 100;
  background: transparent;
  pointer-events: none;
}

.lb-read-fill {
  height: 100%;
  background: var(--ed-accent);
  transition: width 0.1s linear;
}

.lb-article-stage {
  padding-top: clamp(28px, 5vw, 56px);
}

/* ---------- 文章头 ---------- */
.lb-article-head {
  max-width: 860px;
  display: flex;
  flex-direction: column;
  gap: clamp(16px, 2.4vw, 24px);
  padding-bottom: clamp(28px, 4vw, 44px);
}

.lb-head-labels {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.lb-article-title {
  color: var(--ed-ink);
  max-width: 22ch;
  overflow-wrap: anywhere;
}

.lb-head-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
}

.lb-head-author {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ed-ink);
  font-weight: 600;
}

.lb-head-dot {
  opacity: 0.5;
}

/* ---------- 封面 ---------- */
.lb-article-cover-wrap {
  margin-bottom: clamp(32px, 5vw, 56px);
}

.lb-article-cover {
  border-radius: var(--ed-radius-lg);
  box-shadow: var(--ed-shadow-md);
}

/* ---------- 正文白卡 ---------- */
.lb-article-main {
  max-width: 820px;
  margin: 0 auto;
  padding: clamp(28px, 4.4vw, 56px);
  cursor: default;
}

.lb-article-body {
  max-width: var(--ed-measure);
  margin: 0 auto;
  font-size: var(--ed-step-body);
  line-height: 1.75;
  color: var(--ed-ink);
}

.lb-article-body :deep(.lb-content p) {
  margin: 0 0 1.4em;
}

.lb-article-body :deep(.lb-content > p:first-child) {
  font-size: 1.16em;
  color: var(--ed-ink-soft);
}

.lb-article-body :deep(.lb-content img) {
  max-width: 100%;
  border-radius: var(--ed-radius-md);
  cursor: zoom-in;
  margin: 1.6em 0;
}

.lb-article-body :deep(.lb-content h2),
.lb-article-body :deep(.lb-content h3) {
  margin: 1.8em 0 0.7em;
  font-family: var(--ed-font-display);
  font-weight: 500;
  line-height: 1.2;
  letter-spacing: -0.015em;
}

.lb-article-body :deep(.lb-content blockquote) {
  margin: 1.6em 0;
  padding: 4px 22px;
  border-left: 3px solid var(--ed-accent);
  background: var(--ed-accent-soft);
  border-radius: 0 var(--ed-radius-sm) var(--ed-radius-sm) 0;
  color: var(--ed-ink-soft);
  font-style: italic;
}

.lb-article-body :deep(.lb-content pre) {
  border-radius: var(--ed-radius-md);
  padding: 20px;
  overflow-x: auto;
  background: color-mix(in srgb, var(--ed-ink) 6%, var(--ed-card));
}

.lb-article-body :deep(.lb-content a) {
  color: var(--ed-accent);
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* ---------- 点赞 pill ---------- */
.lb-article-praise {
  max-width: var(--ed-measure);
  margin: clamp(32px, 5vw, 56px) auto 0;
  display: flex;
  justify-content: center;
}

.lb-praise {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 12px 26px;
  border: 1.5px solid var(--ed-rule-soft);
  border-radius: var(--ed-radius-pill);
  background: var(--ed-card);
  color: var(--ed-ink);
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  font-weight: 500;
  cursor: pointer;
  transition: transform 220ms cubic-bezier(0.22, 1, 0.36, 1),
    border-color 160ms ease, background 160ms ease, color 160ms ease;
}

.lb-praise:hover {
  transform: translateY(-2px);
  border-color: var(--ed-accent);
}

.lb-praise:active {
  transform: translateY(0) scale(0.98);
}

.lb-praise.is-on {
  background: var(--ed-accent);
  border-color: var(--ed-accent);
  color: #fff;
}

.lb-praise-heart {
  font-size: 1.1em;
  line-height: 1;
}

.lb-praise.is-on .lb-praise-heart {
  color: #fff;
}

.lb-praise-num {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

/* ---------- 评论 ---------- */
.lb-article-comments {
  max-width: 820px;
  margin: 0 auto;
  padding-top: clamp(56px, 8vw, 100px);
}

.lb-comments-head {
  color: var(--ed-ink);
  margin: 0 0 clamp(20px, 3vw, 32px);
}

.lb-comments-card {
  padding: clamp(20px, 3vw, 36px);
  cursor: default;
}

/* ---------- 灯箱 ---------- */
.lb-lightbox {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4vw;
  background: rgba(0, 0, 0, 0.86);
  cursor: zoom-out;
}

.lb-lightbox-img {
  max-width: 92vw;
  max-height: 88vh;
  border-radius: var(--ed-radius-md);
  cursor: default;
}

.lb-lightbox-close {
  position: absolute;
  top: 20px;
  right: 26px;
  width: 40px;
  height: 40px;
  border: 1px solid rgba(255, 255, 255, 0.4);
  border-radius: 50%;
  background: transparent;
  color: #fff;
  font-size: 24px;
  line-height: 1;
  cursor: pointer;
  transition: background 160ms ease;
}

.lb-lightbox-close:hover {
  background: rgba(255, 255, 255, 0.16);
}

.lb-fade-enter-active,
.lb-fade-leave-active {
  transition: opacity 0.18s ease;
}

.lb-fade-enter-from,
.lb-fade-leave-to {
  opacity: 0;
}

@media (max-width: 720px) {
  .lb-article-title { max-width: none; }
  .lb-article-main { padding: clamp(20px, 5vw, 28px); }
}
</style>
