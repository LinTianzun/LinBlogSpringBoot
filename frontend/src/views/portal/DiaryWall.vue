<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { listPortalDiaries, praiseInteraction } from '@/api/portal'
import { ApiError } from '@/api/request'
import ContentRenderer from '@/components/ContentRenderer.vue'
import { parseTiptapDoc } from '@/components/renderContent'
import PaginationBar from '@/components/PaginationBar.vue'
import WeatherBadge from '@/components/WeatherBadge.vue'
import { formatCount } from '@/utils/format'
import { isWeather } from '@/utils/weather'
import type { DiaryDTO } from '@/types/api'

/**
 * 日记墙（方案 A · 亮卡片）：GET /portal/diaries（published）。
 * body 是纯字符串或 ProseMirror JSON → parseTiptapDoc 防御渲染。
 * 页眉：Newsreader 大标题「日记」+ 一句导语 + 顶部「天气图例统计」细条（纯文字 晴天2·多云2…）。
 * 主体：按月分组，月份是 .lb-ed-section-head 风格抬头；竖向时间轴（日期节点 + 连接线 + 白色卡片
 * .lb-ed-card），每卡 WeatherBadge + 心情 pill + ContentRenderer 内文 + 点赞；空/加载/错误态；
 * 分页保留；响应式；进入 .lb-ed-reveal 错峰。颜色全走 CSS 变量（--ed-*）。
 */
interface DiaryBucket {
  key: string
  label: string
  items: DiaryDTO[]
}

const PAGE_SIZE = 10

const records = ref<DiaryDTO[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const errorMessage = ref('')

const docs = computed(() => new Map(records.value.map((d) => [d.id, parseTiptapDoc(d.body)])))

/** 按年月分组（记录已按时间倒序返回，保持顺序） */
const buckets = computed<DiaryBucket[]>(() => {
  const map = new Map<string, DiaryBucket>()
  for (const diary of records.value) {
    const date = diary.diaryDate ?? ''
    const key = date.slice(0, 7) || '未知'
    let bucket = map.get(key)
    if (!bucket) {
      bucket = { key, label: key === '未知' ? '未知时间' : `${key.replace('-', ' 年 ')} 月`, items: [] }
      map.set(key, bucket)
    }
    bucket.items.push(diary)
  }
  return Array.from(map.values())
})

/** 本页天气分布（文字统计） */
const weatherStats = computed<{ weather: string; count: number }[]>(() => {
  const counts = new Map<string, number>()
  for (const diary of records.value) {
    if (isWeather(diary.weather)) {
      counts.set(diary.weather, (counts.get(diary.weather) ?? 0) + 1)
    }
  }
  return Array.from(counts.entries())
    .map(([weather, count]) => ({ weather, count }))
    .sort((a, b) => b.count - a.count)
})

/* ---- 进入渐显编排 ---- */
const timelineRef = ref<HTMLElement | null>(null)
const reducedMotion =
  (typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches) ||
  // 无 IntersectionObserver 的环境（旧浏览器 / jsdom）直接显示，不做过渐显
  typeof IntersectionObserver === 'undefined'

let io: IntersectionObserver | null = null

function armReveal(): void {
  const host = timelineRef.value
  if (!host) return
  const nodes = Array.from(host.querySelectorAll<HTMLElement>('[data-reveal]'))
  if (reducedMotion) {
    nodes.forEach((el) => el.classList.add('lb-ed-reveal', 'is-in'))
    return
  }
  nodes.forEach((el) => el.classList.add('lb-ed-reveal', 'is-armed'))
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
    { rootMargin: '0px 0px -6% 0px', threshold: 0.05 },
  )
  nodes.forEach((el) => io?.observe(el))
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listPortalDiaries({ page: page.value, pageSize: PAGE_SIZE })
    records.value = result.records
    total.value = result.total
    await nextTick()
    armReveal()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '日记加载失败'
    records.value = []
  } finally {
    loading.value = false
  }
}

watch(page, () => void load())
onMounted(load)
onBeforeUnmount(() => io?.disconnect())

/* ---- 点赞（interactions toggle， targetType=diary）：乐观更新 + 失败回滚 ---- */
const likedIds = reactive(new Set<number>())

async function togglePraise(diary: DiaryDTO): Promise<void> {
  const liked = likedIds.has(diary.id)
  const prevCount = diary.praises ?? 0
  if (liked) likedIds.delete(diary.id)
  else likedIds.add(diary.id)
  diary.praises = prevCount + (liked ? -1 : 1)
  try {
    const result = await praiseInteraction('diary', diary.id)
    diary.praises = result.count
    if (result.active) likedIds.add(diary.id)
    else likedIds.delete(diary.id)
  } catch {
    if (liked) likedIds.add(diary.id)
    else likedIds.delete(diary.id)
    diary.praises = prevCount
  }
}
</script>

<template>
  <div class="lb-ed lb-diary">
    <header class="lb-ed-section lb-diary-head">
      <div class="lb-ed-wrap lb-diary-inner">
        <p class="lb-ed-caption lb-diary-kicker">
          <span class="lb-ed-dot" aria-hidden="true"></span>
          {{ total > 0 ? `共 ${formatCount(total)} 篇 · 把日子里细碎的光慢慢记下` : '把日子里细碎的光慢慢记下' }}
        </p>
        <h1 class="lb-ed-display lb-diary-title">日记</h1>
        <p class="lb-ed-lead lb-diary-lead">日子很轻，记下来就沉成了故事。</p>

        <div v-if="weatherStats.length > 0" class="lb-diary-legend">
          <span class="lb-diary-legend-label">天气</span>
          <template v-for="(stat, i) in weatherStats" :key="stat.weather">
            <span class="lb-diary-legend-item">{{ stat.weather }} {{ stat.count }}</span>
            <span v-if="i < weatherStats.length - 1" class="lb-diary-sep" aria-hidden="true">·</span>
          </template>
        </div>
      </div>
    </header>

    <div v-loading="loading" class="lb-ed-wrap lb-diary-body">
      <el-alert v-if="errorMessage" type="warning" class="lb-diary-alert" :closable="false">
        {{ errorMessage }}
      </el-alert>

      <el-empty
        v-if="records.length === 0 && !loading && !errorMessage"
        description="还没有日记"
        class="lb-diary-empty"
      />

      <div v-else-if="records.length > 0" ref="timelineRef" class="lb-diary-timeline">
        <section v-for="bucket in buckets" :key="bucket.key" class="lb-diary-month">
          <div class="lb-ed-section-head lb-diary-month-head">
            <h2 class="lb-ed-head lb-diary-month-label">{{ bucket.label }}</h2>
            <span class="lb-ed-caption">{{ bucket.items.length }} 篇</span>
          </div>

          <article
            v-for="diary in bucket.items"
            :key="diary.id"
            class="lb-diary-entry"
            data-reveal
          >
            <div class="lb-diary-rail" aria-hidden="true">
              <span class="lb-diary-dot"></span>
            </div>
            <div class="lb-ed-card lb-diary-card">
              <div class="lb-ed-card-body lb-diary-card-body">
                <div class="lb-diary-meta">
                  <span class="lb-diary-date">{{ diary.diaryDate }}</span>
                  <WeatherBadge :weather="diary.weather" />
                  <span v-if="diary.mood" class="lb-ed-pill lb-ed-pill--soft lb-diary-mood">心情 · {{ diary.mood }}</span>
                  <button
                    type="button"
                    class="lb-diary-praise"
                    :class="{ 'is-liked': likedIds.has(diary.id) }"
                    :aria-pressed="likedIds.has(diary.id)"
                    :aria-label="`点赞这篇日记（当前 ${diary.praises} 次）`"
                    @click="togglePraise(diary)"
                  >
                    ♥ {{ formatCount(diary.praises) }}
                  </button>
                </div>
                <div class="lb-diary-content">
                  <ContentRenderer :doc="docs.get(diary.id)" />
                </div>
              </div>
            </div>
          </article>
        </section>

        <PaginationBar
          v-if="records.length > 0 || total > 0"
          v-model:page="page"
          :page-size="PAGE_SIZE"
          :total="total"
          :disabled="loading"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.lb-diary {
  padding-bottom: clamp(32px, 5vw, 72px);
}

/* ---------- 首屏 ---------- */
.lb-diary-head {
  padding-top: clamp(28px, 5vw, 68px);
  padding-bottom: clamp(32px, 5vw, 64px);
}

.lb-diary-inner {
  display: flex;
  flex-direction: column;
  gap: clamp(14px, 2vw, 22px);
}

.lb-diary-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.lb-diary-title {
  color: var(--ed-ink);
}

.lb-diary-lead {
  margin: 0;
  max-width: 52ch;
}

/* 天气图例细条 */
.lb-diary-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
  margin-top: clamp(8px, 1.4vw, 14px);
  padding: 10px 16px;
  border-radius: var(--ed-radius-pill);
  background: var(--ed-card);
  box-shadow: var(--ed-shadow-sm);
  width: fit-content;
  max-width: 100%;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
}

.lb-diary-legend-label {
  font-weight: 600;
  color: var(--ed-ink);
  margin-right: 2px;
}

.lb-diary-legend-item {
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}

.lb-diary-sep {
  opacity: 0.45;
}

/* ---------- 主体 ---------- */
.lb-diary-body {
  padding-top: 8px;
}

.lb-diary-alert {
  margin-bottom: 28px;
}

.lb-diary-empty {
  padding: clamp(60px, 10vw, 130px) 0;
}

.lb-diary-month {
  padding-bottom: clamp(36px, 5vw, 76px);
}

.lb-diary-month-head {
  margin-bottom: clamp(20px, 3vw, 32px);
}

.lb-diary-month-label {
  color: var(--ed-ink);
}

/* 时间轴条目 */
.lb-diary-entry {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: clamp(14px, 2.2vw, 24px);
  padding-bottom: clamp(20px, 2.6vw, 30px);
}

.lb-diary-entry:last-child {
  padding-bottom: 0;
}

.lb-diary-rail {
  position: relative;
  display: flex;
  justify-content: center;
  padding-top: 14px;
}

/* 连接线 */
.lb-diary-rail::before {
  content: '';
  position: absolute;
  top: 28px;
  bottom: calc(-1 * clamp(20px, 2.6vw, 30px));
  width: 2px;
  border-radius: 2px;
  background: var(--ed-rule-soft);
}

.lb-diary-entry:last-child .lb-diary-rail::before {
  display: none;
}

.lb-diary-dot {
  position: relative;
  z-index: 1;
  width: 12px;
  height: 12px;
  margin-top: 4px;
  border-radius: 50%;
  background: var(--ed-accent);
  box-shadow: 0 0 0 4px var(--ed-accent-soft);
}

.lb-diary-card {
  min-width: 0;
}

.lb-diary-card-body {
  padding: clamp(18px, 2.4vw, 26px);
}

.lb-diary-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
  padding-bottom: 14px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--ed-rule-soft);
}

.lb-diary-date {
  font-family: var(--ed-font-ui);
  font-weight: 700;
  font-size: var(--ed-step-small);
  letter-spacing: 0.02em;
  color: var(--ed-ink);
  font-variant-numeric: tabular-nums;
}

.lb-diary-mood {
  font-size: 0.72rem;
}

.lb-diary-praise {
  margin-left: auto;
  border: 0;
  background: none;
  padding: 2px 8px;
  border-radius: var(--ed-radius-pill);
  cursor: pointer;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-caption);
  color: var(--ed-ink-soft);
  transition:
    color var(--lb-dur-fast, 160ms) var(--lb-ease-out, ease-out),
    background var(--lb-dur-fast, 160ms) var(--lb-ease-out, ease-out);
}

.lb-diary-praise:hover {
  color: var(--ed-ink);
  background: var(--ed-accent-soft);
}

.lb-diary-praise.is-liked {
  color: var(--ed-accent);
  font-weight: 600;
}

.lb-diary-content {
  font-size: var(--ed-step-body);
  line-height: 1.8;
  color: var(--ed-ink);
}

.lb-diary-content :deep(.lb-content p) {
  margin: 0 0 1.1em;
}

.lb-diary-content :deep(.lb-content p:last-child) {
  margin-bottom: 0;
}

.lb-diary-content :deep(.lb-content img) {
  max-width: 100%;
  border-radius: var(--ed-radius-md);
  margin: 1em 0;
}

@media (max-width: 640px) {
  .lb-diary-entry {
    grid-template-columns: 16px minmax(0, 1fr);
    gap: 12px;
  }

  .lb-diary-praise {
    margin-left: 0;
  }

  .lb-diary-legend {
    border-radius: var(--ed-radius-md);
  }
}
</style>
