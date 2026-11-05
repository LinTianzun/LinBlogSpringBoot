<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { listLabels } from '@/api/admin'
import { listPortalContents } from '@/api/portal'
import { ApiError } from '@/api/request'
import CoverImage from '@/components/CoverImage.vue'
import PaginationBar from '@/components/PaginationBar.vue'
import { formatCount, formatRelative } from '@/utils/format'
import type { ContentView, LabelDTO } from '@/types/api'

/**
 * 文章列表（亮卡片 · 方案 A）：GET /portal/contents?contentType=article（门户口径恒 published）。
 * 标签过滤来自 GET /labels。⚠️ 后端未消费 keyword/sort——sort 仍随请求发送，就绪即生效。
 *
 * 构成：页眉 Newsreader 大标题「文章」+ 一句导语；静默筛选（pill 段控排序 + 标签 chip + 计数）；
 * 列表 = 「领读大卡」（首篇）＋「亮卡片文章行」（其余，左 16:9 真实封面 / 右标题·摘要·标签·元信息）。
 * 全部白面圆角卡 + 柔和阴影 + hover 抬升（.lb-ed-card / .lb-ed-thumb / .lb-ed-pill）。
 * 颜色全走 --ed-* 变量（4 主题 + 暗色）。真实数据 / 路由 / 分页 / 空载错态逻辑全部保留。
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

const PAGE_SIZE = 10

const activeLabel = computed(() => labels.value.find((item) => item.id === labelId.value) ?? null)

/** 首篇「领读」：大卡；其余为卡片行。 */
const lead = computed<ContentView | null>(() => records.value[0] ?? null)
const rest = computed<ContentView[]>(() => records.value.slice(1))

/* ---------- 进入渐显（自带 IO；尊重 reduced-motion；无 JS 不丢） ---------- */
const reducedMotion =
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

let io: IntersectionObserver | null = null

function observeReveals(): void {
  if (reducedMotion) return
  io?.disconnect()
  io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in')
          io?.unobserve(entry.target)
        }
      }
    },
    { threshold: 0.08, rootMargin: '0px 0px -40px 0px' },
  )
  document.querySelectorAll<HTMLElement>('.lb-ed-reveal.is-armed').forEach((el) => {
    if (!el.classList.contains('is-in')) io?.observe(el)
  })
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listPortalContents({
      contentType: 'article',
      page: page.value,
      pageSize: PAGE_SIZE,
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
    await nextTick()
    observeReveals()
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

onBeforeUnmount(() => {
  io?.disconnect()
  io = null
})
</script>

<template>
  <div class="lb-ed lb-articles">
    <div class="lb-ed-wrap">
      <!-- 页眉 -->
      <header class="lb-articles-head">
        <p class="lb-articles-kicker lb-ed-caption">
          <span class="lb-ed-dot" aria-hidden="true"></span>
          {{ total > 0 ? `共 ${total} 篇 · 记录正在发生的事` : '记录正在发生的事' }}
        </p>
        <h1 class="lb-ed-h1 lb-articles-title">文章</h1>
        <p class="lb-ed-lead lb-articles-intro">
          写代码，也写生活。这里收藏对工程的思考、镜头下的光影，和那些值得被重读的小事。
        </p>
      </header>

      <!-- 静默筛选 -->
      <div class="lb-articles-controls">
        <div class="lb-seg" role="group" aria-label="排序方式">
          <button
            type="button"
            class="lb-seg-btn"
            :class="{ 'is-active': sort === 'latest' }"
            :aria-pressed="sort === 'latest'"
            @click="sort = 'latest'"
          >
            最新
          </button>
          <button
            type="button"
            class="lb-seg-btn"
            :class="{ 'is-active': sort === 'hot' }"
            :aria-pressed="sort === 'hot'"
            @click="sort = 'hot'"
          >
            最热
          </button>
        </div>

        <div v-if="labels.length > 0" class="lb-articles-tags" role="group" aria-label="按标签筛选">
          <button
            type="button"
            class="lb-chip"
            :class="{ 'is-active': labelId === null }"
            @click="pickLabel(null)"
          >
            全部
          </button>
          <button
            v-for="item in labels"
            :key="item.id"
            type="button"
            class="lb-chip"
            :class="{ 'is-active': labelId === item.id }"
            @click="pickLabel(item.id)"
          >
            {{ item.name }}
          </button>
        </div>
      </div>

      <!-- 主体 -->
      <el-alert
        v-if="errorMessage"
        type="error"
        class="lb-articles-alert"
        :title="errorMessage"
        :closable="false"
        show-icon
      />

      <!-- 加载骨架 -->
      <div v-if="loading && records.length === 0" class="lb-skeleton-list" aria-hidden="true">
        <div class="lb-ed-card lb-skeleton-lead">
          <div class="lb-ed-skel lb-sk-lead-cover"></div>
          <div class="lb-sk-lines">
            <div class="lb-ed-skel lb-sk-line w60 lg"></div>
            <div class="lb-ed-skel lb-sk-line w90"></div>
            <div class="lb-ed-skel lb-sk-line w40"></div>
          </div>
        </div>
        <div v-for="n in 3" :key="n" class="lb-ed-card lb-skeleton-row">
          <div class="lb-ed-skel lb-sk-cover"></div>
          <div class="lb-sk-lines">
            <div class="lb-ed-skel lb-sk-line w70"></div>
            <div class="lb-ed-skel lb-sk-line w90"></div>
            <div class="lb-ed-skel lb-sk-line w40"></div>
          </div>
        </div>
      </div>

      <div v-else-if="records.length === 0 && !loading && !errorMessage" class="lb-ed-empty">
        暂无文章
      </div>

      <template v-else>
        <!-- 领读大卡 -->
        <RouterLink
          v-if="lead"
          :to="`/articles/${lead.id}`"
          class="lb-ed-card lb-lead lb-ed-reveal"
          :class="{ 'is-armed': !reducedMotion }"
        >
          <div class="lb-ed-thumb lb-lead-thumb">
            <span class="lb-ed-pill lb-ed-pill--onmedia">置顶阅读</span>
            <CoverImage
              :storage-key="lead.coverStorageKey"
              ratio="16/10"
              sizes="(max-width: 860px) 100vw, 560px"
              :alt="lead.title"
            />
          </div>
          <div class="lb-ed-card-body lb-lead-body">
            <div v-if="lead.labels && lead.labels.length" class="lb-lead-labels">
              <span v-for="l in lead.labels.slice(0, 3)" :key="l" class="lb-ed-pill lb-ed-pill--soft">
                {{ l }}
              </span>
            </div>
            <h2 class="lb-ed-h3 lb-lead-title">{{ lead.title }}</h2>
            <p class="lb-lead-intro lb-ed-muted">
              {{ lead.introduce || '暂无摘要' }}
            </p>
            <div class="lb-ed-byline lb-lead-byline">
              <span class="lb-ed-avatar" aria-hidden="true">
                {{ (lead.authorName ?? '佚').trim().charAt(0) || '佚' }}
              </span>
              {{ lead.authorName ?? '佚名' }}
              <span aria-hidden="true">·</span>
              {{ formatRelative(lead.publishedAt ?? lead.createdAt) }}
              <span aria-hidden="true">·</span>
              {{ formatCount(lead.views) }} 阅读
            </div>
          </div>
        </RouterLink>

        <!-- 卡片文章行 -->
        <ul v-if="rest.length > 0" class="lb-cards">
          <li v-for="(item, idx) in rest" :key="item.id">
            <RouterLink
              :to="`/articles/${item.id}`"
              class="lb-ed-card lb-row-card lb-ed-reveal"
              :class="{ 'is-armed': !reducedMotion }"
              :data-delay="String((idx % 5) + 1)"
            >
              <div class="lb-ed-thumb lb-row-thumb">
                <CoverImage
                  :storage-key="item.coverStorageKey"
                  ratio="16/9"
                  sizes="(max-width: 640px) 40vw, 240px"
                  :alt="item.title"
                />
              </div>
              <div class="lb-ed-card-body lb-row-body">
                <div class="lb-row-tags">
                  <span v-if="item.labels && item.labels.length" class="lb-ed-pill lb-ed-pill--soft">
                    {{ item.labels[0] }}
                  </span>
                </div>
                <h3 class="lb-ed-h3 lb-row-title">{{ item.title }}</h3>
                <p v-if="item.introduce" class="lb-row-intro lb-ed-muted">{{ item.introduce }}</p>
                <div class="lb-row-meta lb-ed-caption">
                  <span>{{ item.authorName ?? '佚名' }}</span>
                  <span aria-hidden="true">·</span>
                  <span>{{ formatRelative(item.publishedAt ?? item.createdAt) }}</span>
                  <span class="lb-row-stats">
                    <span>{{ formatCount(item.views) }} 阅读</span>
                    <span aria-hidden="true">·</span>
                    <span>{{ formatCount(item.praises) }} 赞</span>
                    <span aria-hidden="true">·</span>
                    <span>{{ formatCount(item.commentsCount) }} 评</span>
                  </span>
                </div>
              </div>
            </RouterLink>
          </li>
        </ul>

        <div v-if="activeLabel" class="lb-articles-filter-note lb-ed-caption">
          正在查看标签「{{ activeLabel.name }}」
          <button type="button" class="lb-filter-clear" @click="pickLabel(null)">清除</button>
        </div>

        <div class="lb-articles-pager">
          <PaginationBar v-model:page="page" :page-size="PAGE_SIZE" :total="total" :disabled="loading" />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.lb-articles {
  padding-bottom: clamp(48px, 7vw, 96px);
}

/* ---------- 页眉 ---------- */
.lb-articles-head {
  padding-top: clamp(32px, 6vw, 72px);
  padding-bottom: clamp(28px, 4vw, 44px);
  max-width: 760px;
}

.lb-articles-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 clamp(14px, 2vw, 20px);
}

.lb-articles-title {
  color: var(--ed-ink);
}

.lb-articles-intro {
  margin: clamp(16px, 2.4vw, 24px) 0 0;
  max-width: 52ch;
}

/* ---------- 筛选 ---------- */
.lb-articles-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: clamp(14px, 3vw, 28px);
  padding-bottom: clamp(28px, 4vw, 44px);
}

/* 排序 pill 段控 */
.lb-seg {
  display: inline-flex;
  padding: 4px;
  gap: 4px;
  border-radius: var(--ed-radius-pill);
  background: color-mix(in srgb, var(--ed-ink) 6%, var(--ed-card));
}

.lb-seg-btn {
  border: none;
  background: transparent;
  padding: 7px 18px;
  border-radius: var(--ed-radius-pill);
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  font-weight: 500;
  color: var(--ed-ink-soft);
  cursor: pointer;
  transition: color 160ms ease, background 160ms ease, box-shadow 160ms ease;
}

.lb-seg-btn.is-active {
  background: var(--ed-card);
  color: var(--ed-ink);
  box-shadow: var(--ed-shadow-sm);
}

/* 标签 chip */
.lb-articles-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.lb-chip {
  border: 1px solid var(--ed-rule-soft);
  background: transparent;
  padding: 7px 15px;
  border-radius: var(--ed-radius-pill);
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  color: var(--ed-ink-soft);
  cursor: pointer;
  transition: color 160ms ease, border-color 160ms ease, background 160ms ease;
}

.lb-chip:hover {
  color: var(--ed-ink);
  border-color: var(--ed-ink-faint);
}

.lb-chip.is-active {
  background: var(--ed-accent);
  border-color: var(--ed-accent);
  color: #fff;
}

/* ---------- 主体 ---------- */
.lb-articles-alert {
  margin-bottom: 28px;
}

/* 领读大卡 */
.lb-lead {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: stretch;
  margin-bottom: clamp(28px, 4vw, 44px);
}

.lb-lead-thumb {
  min-height: 100%;
}

.lb-lead :deep(.lb-cover) {
  height: 100%;
}

.lb-lead-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  justify-content: center;
  padding: clamp(24px, 3.4vw, 44px);
}

.lb-lead-labels {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.lb-lead-title {
  color: var(--ed-ink);
}

.lb-lead-intro {
  margin: 0;
  font-size: var(--ed-step-body);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.lb-lead-byline {
  margin-top: 2px;
}

/* 卡片行 */
.lb-cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: clamp(16px, 2.4vw, 24px);
}

.lb-row-card {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  align-items: stretch;
  height: 100%;
}

.lb-row-thumb {
  min-height: 100%;
}

.lb-row-card :deep(.lb-cover) {
  height: 100%;
}

.lb-row-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.lb-row-tags {
  min-height: 0;
}

.lb-row-title {
  color: var(--ed-ink);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.lb-row-intro {
  margin: 0;
  font-size: var(--ed-step-small);
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.lb-row-meta {
  margin-top: auto;
  padding-top: 6px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.lb-row-stats {
  display: inline-flex;
  gap: 6px;
  color: var(--ed-ink-faint);
}

/* 筛选说明 + 分页 */
.lb-articles-filter-note {
  margin-top: 28px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.lb-filter-clear {
  border: none;
  background: transparent;
  padding: 0;
  font: inherit;
  color: var(--ed-accent);
  text-decoration: underline;
  text-underline-offset: 4px;
  cursor: pointer;
}

.lb-articles-pager {
  margin-top: clamp(32px, 5vw, 56px);
  display: flex;
  justify-content: center;
}

/* ---------- 骨架 ---------- */
.lb-skeleton-list {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.lb-skeleton-lead {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  min-height: 280px;
}

.lb-sk-lead-cover {
  width: 100%;
  height: 100%;
  min-height: 280px;
  border-radius: 0;
}

.lb-skeleton-row {
  display: grid;
  grid-template-columns: 200px 1fr;
  align-items: center;
}

.lb-sk-cover {
  width: 100%;
  height: 132px;
  border-radius: 0;
}

.lb-sk-lines {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: clamp(20px, 2.6vw, 28px);
}

.lb-sk-line {
  height: 14px;
  border-radius: 6px;
}
.lb-sk-line.lg { height: 24px; }
.lb-sk-line.w40 { width: 40%; }
.lb-sk-line.w60 { width: 60%; }
.lb-sk-line.w70 { width: 70%; }
.lb-sk-line.w90 { width: 90%; }

/* ---------- 响应式 ---------- */
@media (max-width: 960px) {
  .lb-cards {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .lb-lead {
    grid-template-columns: 1fr;
  }
  .lb-lead-thumb {
    min-height: 0;
  }
  .lb-row-card {
    grid-template-columns: 132px minmax(0, 1fr);
  }
  .lb-row-intro,
  .lb-row-stats {
    display: none;
  }
  .lb-skeleton-lead {
    grid-template-columns: 1fr;
  }
  .lb-skeleton-row {
    grid-template-columns: 110px 1fr;
  }
  .lb-sk-cover {
    height: 96px;
  }
}
</style>
