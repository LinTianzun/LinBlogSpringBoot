<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { getPortalHome, listPortalContents, listPortalDiaries } from '@/api/portal'
import { ApiError } from '@/api/request'
import { formatCount, formatRelative } from '@/utils/format'
import CoverImage from '@/components/CoverImage.vue'
import type { ContentView, DiaryDTO, PortalHomeDTO } from '@/types/api'

/**
 * 门户首页 —— 方案 A「亮卡片 Bright Cards」。
 * 浅暖米底 + 纯白圆角卡片 + 柔和分层阴影 + 图片唱主角 + 主题色点缀（editorial.less v2）。
 * 全局导航条与页脚由 PortalLayout 提供，本页只渲染正文内容。
 *
 * 结构（自上而下）：
 *   1. Hero     左点+眉题 / 大标题(斜体强调字) / 导语 / 两枚 pill 按钮
 *   2. Collage  拼贴：1 张大卡(跨两行) + 4 张小卡，真实封面 + 底部 scrim + 分类药丸 + 标题
 *   3. Latest   最新文章三列卡片：封面 thumb(16:11) + 内联药丸 + 标题 + 2 行摘要 + 作者行
 *   4. Gallery  光影画廊 5 连竖图墙(3/4)，hover 微放大，底部标题 scrim
 *   5. Stats    深色圆角数字带：文章/作品/日记/来访 四数不同主题色
 *
 * 数据仅走现有 API：getPortalHome(latest/pinned/labels) / listPortalContents(image) / listPortalDiaries。
 * 4 主题 + 暗色由 --main-color / --ed-* 驱动；动效为一次编排渐显，prefers-reduced-motion 直显。
 */
const PAGE_SIZE_COLLAGE = 5      // 1 大卡 + 4 小卡
const PAGE_SIZE_LATEST = 6       // 三列 × 2 行
const PAGE_SIZE_GALLERY = 5      // 五连竖图
const PAGE_SIZE_DIARIES = 5

const home = ref<Partial<PortalHomeDTO> | null>(null)
const galleryPreview = ref<ContentView[]>([])
const diaryPreview = ref<DiaryDTO[]>([])
const galleryTotal = ref(0)
const diaryTotal = ref(0)
const loading = ref(false)
const errorMessage = ref('')

const pinned = computed(() => home.value?.pinned ?? [])
const latest = computed(() => home.value?.latest ?? [])

/** 文章总数（用于数字带）：latest 与 pinned 去重后合计，取较大者兜底。 */
const articleTotal = computed(() => {
  const merged = new Set<number>()
  latest.value.forEach((c) => merged.add(c.id))
  pinned.value.forEach((c) => merged.add(c.id))
  return merged.size
})

/** 拼贴区：置顶优先，其余用最新补齐，共 5 张；第一张为大卡。 */
const collageItems = computed<ContentView[]>(() => {
  const seen = new Set<number>()
  const out: ContentView[] = []
  for (const c of [...pinned.value, ...latest.value]) {
    if (seen.has(c.id)) continue
    seen.add(c.id)
    out.push(c)
    if (out.length >= PAGE_SIZE_COLLAGE) break
  }
  return out
})
const collageLead = computed<ContentView | null>(() => collageItems.value[0] ?? null)
const collageRest = computed<ContentView[]>(() => collageItems.value.slice(1))

/** 最新文章：排除拼贴已用的，避免重复；不足则回退到全部 latest。 */
const latestItems = computed<ContentView[]>(() => {
  const used = new Set(collageItems.value.map((c) => c.id))
  const rest = latest.value.filter((c) => !used.has(c.id))
  const pool = rest.length >= 3 ? rest : latest.value
  return pool.slice(0, PAGE_SIZE_LATEST)
})

/** 画廊：优先真实图片内容，空则隐藏整节。 */
const galleryTiles = computed<ContentView[]>(() => galleryPreview.value.slice(0, PAGE_SIZE_GALLERY))
const hasGallery = computed(() => galleryTiles.value.length > 0)

/* 数字带：文章 / 作品(画廊) / 日记 / 来访（来访用阅读量合计兜底） */
const visitTotal = computed(() => {
  let sum = 0
  for (const c of latest.value) sum += c.views ?? 0
  for (const d of diaryPreview.value) sum += d.views ?? 0
  return sum
})
const stats = computed(() => [
  { label: '文章', value: articleTotal.value, accent: '1' },
  { label: '作品', value: galleryTotal.value, accent: '2' },
  { label: '日记', value: diaryTotal.value, accent: '3' },
  { label: '来访', value: visitTotal.value, accent: '4' },
])

/** 首篇标签名（分类药丸）；无标签时用「文章」。 */
function firstLabel(item: ContentView | null): string {
  return item?.labels?.[0] ?? '文章'
}

/** 作者首字母/首字，用于头像圆点。 */
function initial(name?: string): string {
  return name?.trim().charAt(0).toUpperCase() || 'L'
}

/* ---------------- 编排式渐显（一次 stagger） ---------------- */
const revealRoot = ref<HTMLElement | null>(null)
let io: IntersectionObserver | null = null

function armReveals(): void {
  const root = revealRoot.value
  if (!root) return
  const nodes = Array.from(root.querySelectorAll<HTMLElement>('.lb-ed-reveal'))
  const reduce =
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  if (reduce || typeof IntersectionObserver === 'undefined') {
    nodes.forEach((n) => n.classList.add('is-in'))
    return
  }
  nodes.forEach((n) => n.classList.add('is-armed'))
  io = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-in')
          io?.unobserve(entry.target)
        }
      })
    },
    { rootMargin: '0px 0px -8% 0px', threshold: 0.06 },
  )
  nodes.forEach((n) => io!.observe(n))
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  const [homeResult, galleryResult, diaryResult] = await Promise.allSettled([
    getPortalHome(),
    listPortalContents({ contentType: 'image', page: 1, pageSize: PAGE_SIZE_GALLERY }),
    listPortalDiaries({ page: 1, pageSize: PAGE_SIZE_DIARIES }),
  ])
  if (homeResult.status === 'fulfilled') {
    home.value = homeResult.value
  } else {
    errorMessage.value =
      homeResult.reason instanceof ApiError ? homeResult.reason.message : '加载失败，请稍后重试'
  }
  if (galleryResult.status === 'fulfilled') {
    galleryPreview.value = galleryResult.value.records
    galleryTotal.value = galleryResult.value.total
  } else {
    galleryPreview.value = []
    galleryTotal.value = 0
  }
  if (diaryResult.status === 'fulfilled') {
    diaryPreview.value = diaryResult.value.records
    diaryTotal.value = diaryResult.value.total
  } else {
    diaryPreview.value = []
    diaryTotal.value = 0
  }
  loading.value = false
  requestAnimationFrame(() => armReveals())
}

onMounted(load)
onBeforeUnmount(() => {
  io?.disconnect()
  io = null
})
</script>

<template>
  <div ref="revealRoot" class="lb-ed lb-home">
    <!-- ===================== 1. HERO ===================== -->
    <section class="lb-ed-wrap lb-home-hero">
      <p class="lb-home-eyebrow lb-ed-reveal" data-delay="1">
        <span class="lb-ed-dot" />
        <span>LinBlog · 写作即产品</span>
      </p>
      <h1 class="lb-ed-display lb-home-title lb-ed-reveal" data-delay="1">
        把想法写成<span class="lb-ed-em">作品</span>，<br />
        让它们被看见。
      </h1>
      <p class="lb-ed-lead lb-home-lede lb-ed-reveal" data-delay="2">
        这里收录公开发表的文章、画作与日常日记——一篇文章，一件完整的作品，
        从第一句到最后一句。
      </p>
      <div class="lb-home-cta lb-ed-reveal" data-delay="3">
        <RouterLink to="/articles" class="lb-ed-btn lb-ed-btn--primary">开始阅读</RouterLink>
        <RouterLink to="/gallery" class="lb-ed-btn lb-ed-btn--ghost">逛逛画廊</RouterLink>
      </div>
    </section>

    <!-- 数据错误：保留容错 -->
    <div v-if="errorMessage" class="lb-ed-wrap lb-home-err">
      <p class="lb-home-err-msg">{{ errorMessage }}</p>
      <button type="button" class="lb-ed-btn lb-ed-btn--primary" @click="load">重试</button>
    </div>

    <!-- ===================== 2. 拼贴 COLLAGE ===================== -->
    <section class="lb-ed-wrap lb-ed-section lb-home-collage-wrap">
      <div class="lb-ed-section-head">
        <h2 class="lb-ed-head lb-ed-reveal" data-delay="1">精选</h2>
        <RouterLink to="/articles" class="lb-ed-more">查看全部 →</RouterLink>
      </div>

      <!-- 加载骨架 -->
      <div v-if="loading && !collageItems.length" class="lb-home-collage">
        <div class="lb-ed-skel lb-home-collage-lead" />
        <div v-for="n in 4" :key="n" class="lb-ed-skel" />
      </div>

      <!-- 空态 -->
      <div v-else-if="!collageItems.length" class="lb-ed-empty">还没有文章，稍后再来看看。</div>

      <!-- 拼贴网格 -->
      <div v-else class="lb-home-collage">
        <RouterLink
          v-if="collageLead"
          :to="`/articles/${collageLead.id}`"
          class="lb-ed-card lb-home-collage-lead lb-ed-reveal"
          data-delay="1"
        >
          <div class="lb-ed-thumb lb-home-collage-media">
            <CoverImage :storage-key="collageLead.coverStorageKey" ratio="16/10" alt="" />
            <span class="lb-ed-pill lb-ed-pill--onmedia">{{ firstLabel(collageLead) }}</span>
          </div>
          <div class="lb-home-collage-scrim">
            <h3 class="lb-ed-h3 lb-home-collage-title">{{ collageLead.title }}</h3>
          </div>
        </RouterLink>

        <RouterLink
          v-for="(item, i) in collageRest"
          :key="item.id"
          :to="`/articles/${item.id}`"
          class="lb-ed-card lb-home-collage-small lb-ed-reveal"
          :data-delay="(i % 3) + 2"
        >
          <div class="lb-ed-thumb lb-home-collage-media">
            <CoverImage :storage-key="item.coverStorageKey" ratio="4/3" alt="" />
            <span class="lb-ed-pill lb-ed-pill--onmedia">{{ firstLabel(item) }}</span>
          </div>
          <div class="lb-home-collage-scrim">
            <h3 class="lb-home-collage-small-title">{{ item.title }}</h3>
          </div>
        </RouterLink>
      </div>
    </section>

    <!-- ===================== 3. LATEST 最新文章 ===================== -->
    <section class="lb-ed-wrap lb-ed-section lb-home-latest">
      <div class="lb-ed-section-head">
        <h2 class="lb-ed-head lb-ed-reveal" data-delay="1">最新文章</h2>
        <RouterLink to="/articles" class="lb-ed-more">全部文章 →</RouterLink>
      </div>

      <div v-if="loading && !latestItems.length" class="lb-home-grid">
        <div v-for="n in 3" :key="n" class="lb-home-skel-card">
          <div class="lb-ed-skel lb-home-skel-thumb" />
          <div class="lb-ed-skel lb-home-skel-line" style="width: 70%" />
          <div class="lb-ed-skel lb-home-skel-line" style="width: 90%" />
        </div>
      </div>

      <div v-else-if="!latestItems.length" class="lb-ed-empty">暂无最新文章。</div>

      <div v-else class="lb-home-grid">
        <RouterLink
          v-for="(item, i) in latestItems"
          :key="item.id"
          :to="`/articles/${item.id}`"
          class="lb-ed-card lb-ed-reveal"
          :data-delay="(i % 3) + 1"
        >
          <div class="lb-ed-thumb lb-home-card-thumb">
            <CoverImage :storage-key="item.coverStorageKey" ratio="16/11" alt="" />
          </div>
          <div class="lb-ed-card-body">
            <span class="lb-ed-pill lb-ed-pill--soft">{{ firstLabel(item) }}</span>
            <h3 class="lb-ed-h3 lb-home-card-title">{{ item.title }}</h3>
            <p v-if="item.introduce" class="lb-home-card-excerpt">{{ item.introduce }}</p>
            <div class="lb-ed-byline">
              <span class="lb-ed-avatar">{{ initial(item.authorName) }}</span>
              <span>{{ item.authorName || '匿名' }}</span>
              <span v-if="item.publishedAt" class="lb-home-byline-dot">·</span>
              <span v-if="item.publishedAt">{{ formatRelative(item.publishedAt) }}</span>
            </div>
          </div>
        </RouterLink>
      </div>
    </section>

    <!-- ===================== 4. GALLERY 光影画廊 ===================== -->
    <section v-if="hasGallery" class="lb-ed-wrap lb-ed-section lb-home-gallery">
      <div class="lb-ed-section-head">
        <h2 class="lb-ed-head lb-ed-reveal" data-delay="1">光影画廊</h2>
        <RouterLink to="/gallery" class="lb-ed-more">进入画廊 →</RouterLink>
      </div>
      <div class="lb-home-strip">
        <RouterLink
          v-for="(tile, i) in galleryTiles"
          :key="tile.id"
          :to="`/gallery/${tile.id}`"
          class="lb-home-tile lb-ed-reveal"
          :data-delay="(i % 5) + 1"
        >
          <div class="lb-ed-thumb lb-home-tile-media">
            <CoverImage :storage-key="tile.coverStorageKey" ratio="3/4" alt="" />
            <div class="lb-home-tile-scrim">
              <span class="lb-home-tile-title">{{ tile.title }}</span>
            </div>
          </div>
        </RouterLink>
      </div>
    </section>

    <!-- ===================== 5. STATS 数字带 ===================== -->
    <section class="lb-ed-wrap lb-ed-section lb-home-stats-wrap">
      <div class="lb-home-stats lb-ed-reveal" data-delay="1">
        <div v-for="s in stats" :key="s.label" class="lb-home-stat">
          <span class="lb-ed-figure lb-home-stat-num" :class="`lb-home-accent-${s.accent}`">
            {{ formatCount(s.value) }}
          </span>
          <span class="lb-home-stat-label">{{ s.label }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* =================== HERO =================== */
.lb-home-hero {
  padding-top: clamp(24px, 4vw, 56px);
  padding-bottom: clamp(8px, 2vw, 20px);
  max-width: 980px;
}

.lb-home-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin: 0 0 22px;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  font-weight: 500;
  letter-spacing: 0.04em;
  color: var(--ed-ink-soft);
  text-transform: uppercase;
}

.lb-home-title {
  margin-bottom: 22px;
  max-width: 16ch;
}

.lb-home-lede {
  max-width: 46ch;
  margin: 0 0 30px;
}

.lb-home-cta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

/* =================== 错误 =================== */
.lb-home-err {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding-top: 16px;
  padding-bottom: 16px;
}
.lb-home-err-msg {
  margin: 0;
  color: var(--ed-ink-soft);
}

/* =================== 拼贴 COLLAGE =================== */
.lb-home-collage {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  grid-auto-rows: 196px;
  gap: 16px;
}

.lb-home-collage > * {
  position: relative;
}

/* 大卡跨两行 */
.lb-home-collage-lead {
  grid-row: span 2;
}

.lb-home-collage .lb-ed-thumb {
  position: absolute;
  inset: 0;
}

.lb-home-collage-media {
  height: 100%;
}

/* CoverImage 根节点撑满绝对定位的 thumb（覆盖其默认 aspect-ratio） */
.lb-home-collage .lb-ed-thumb :deep(.lb-cover) {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  aspect-ratio: auto !important;
  border-radius: 0;
}
.lb-home-collage .lb-ed-thumb :deep(.lb-cover-img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 底部渐变 scrim + 标题 */
.lb-home-collage-scrim {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: clamp(14px, 2vw, 24px);
  background: linear-gradient(
    to top,
    color-mix(in srgb, #000 62%, transparent),
    color-mix(in srgb, #000 0%, transparent)
  );
}

.lb-home-collage-title {
  margin: 0;
  color: #fff;
  font-family: var(--ed-font-display);
  font-size: clamp(1.35rem, 1.1rem + 1.4vw, 2.1rem);
  line-height: 1.18;
  letter-spacing: -0.01em;
  text-shadow: 0 2px 18px rgba(0, 0, 0, 0.35);
}

.lb-home-collage-small-title {
  margin: 0;
  color: #fff;
  font-family: var(--ed-font-display);
  font-size: clamp(1.02rem, 0.95rem + 0.5vw, 1.24rem);
  font-weight: 500;
  line-height: 1.22;
  text-shadow: 0 2px 14px rgba(0, 0, 0, 0.35);
}

/* =================== LATEST 卡片网格 =================== */
.lb-home-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: clamp(16px, 2vw, 26px);
}

.lb-home-card-thumb {
  aspect-ratio: 16 / 11;
}

.lb-home-card-thumb :deep(.lb-cover) {
  width: 100%;
  height: 100%;
  aspect-ratio: auto !important;
  border-radius: 0;
}
.lb-home-card-thumb :deep(.lb-cover-img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.lb-home-card-title {
  margin: 12px 0 8px;
}

.lb-home-card-excerpt {
  margin: 0;
  color: var(--ed-ink-soft);
  font-size: var(--ed-step-small);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.lb-home-byline-dot {
  color: var(--ed-ink-faint);
}

/* 骨架卡 */
.lb-home-skel-card {
  background: var(--ed-card);
  border-radius: var(--ed-radius-lg);
  padding: 0 0 20px;
  overflow: hidden;
}
.lb-home-skel-thumb {
  aspect-ratio: 16 / 11;
  border-radius: 0;
}
.lb-home-skel-line {
  height: 14px;
  margin: 14px 20px 0;
}

/* =================== GALLERY 光影画廊 =================== */
.lb-home-strip {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
}

.lb-home-tile {
  display: block;
  border-radius: var(--ed-radius-md);
  overflow: hidden;
  text-decoration: none;
}

.lb-home-tile-media {
  aspect-ratio: 3 / 4;
  transition: transform 420ms cubic-bezier(0.22, 1, 0.36, 1);
}

.lb-home-tile-media :deep(.lb-cover) {
  width: 100%;
  height: 100%;
  aspect-ratio: auto !important;
  border-radius: 0;
}
.lb-home-tile-media :deep(.lb-cover-img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 500ms cubic-bezier(0.22, 1, 0.36, 1);
}

.lb-home-tile:hover .lb-home-tile-media :deep(.lb-cover-img) {
  transform: scale(1.05);
}

.lb-home-tile-scrim {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 12px;
  background: linear-gradient(
    to top,
    color-mix(in srgb, #000 60%, transparent),
    color-mix(in srgb, #000 0%, transparent)
  );
}

.lb-home-tile-title {
  display: block;
  color: #fff;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  font-weight: 500;
  line-height: 1.3;
  text-shadow: 0 1px 10px rgba(0, 0, 0, 0.4);
}

/* =================== STATS 数字带 =================== */
.lb-home-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: clamp(16px, 2vw, 32px);
  background: var(--ed-ink);
  border-radius: var(--ed-radius-xl);
  padding: clamp(28px, 4vw, 52px) clamp(24px, 4vw, 56px);
}

.lb-home-stat {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lb-home-stat-num {
  font-size: clamp(2.4rem, 1.4rem + 3.6vw, 4rem);
  color: var(--ed-paper);
}

/* 四数不同主题色（在暗底上用高亮/浅色调） */
.lb-home-accent-1 { color: color-mix(in srgb, var(--ed-accent) 55%, #fff); }
.lb-home-accent-2 { color: #e9c46a; }
.lb-home-accent-3 { color: #8ecae6; }
.lb-home-accent-4 { color: #b6c99a; }

.lb-home-stat-label {
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-small);
  letter-spacing: 0.04em;
  color: color-mix(in srgb, var(--ed-paper) 62%, transparent);
}

/* =================== 响应式 =================== */
@media (max-width: 900px) {
  .lb-home-collage {
    grid-template-columns: 1fr 1fr;
    grid-auto-rows: 170px;
  }
  .lb-home-collage-lead {
    grid-row: span 2;
    grid-column: span 2;
  }
  .lb-home-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .lb-home-strip {
    grid-template-columns: repeat(3, 1fr);
  }
  .lb-home-stats {
    grid-template-columns: repeat(2, 1fr);
    gap: 28px;
  }
}

@media (max-width: 560px) {
  .lb-home-collage {
    grid-template-columns: 1fr;
    grid-auto-rows: 180px;
  }
  .lb-home-collage-lead {
    grid-column: auto;
    grid-row: span 1;
  }
  .lb-home-grid {
    grid-template-columns: 1fr;
  }
  .lb-home-strip {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
