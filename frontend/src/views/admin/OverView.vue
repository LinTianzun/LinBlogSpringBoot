<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  ChatDotRound,
  Document,
  Files,
  FolderOpened,
  Message,
  Notebook,
  Refresh,
  User,
  Warning,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
import {
  getOverviewSummary,
  getStorageStatus,
  getVisitStats,
  listAdminContents,
} from '@/api/admin'
import { ApiError } from '@/api/request'
import { formatBytes, formatCount, formatDate } from '@/utils/format'
import type {
  ContentView,
  OverviewSummaryDTO,
  StorageStatusDTO,
  VisitStatsRange,
  VisitStatsResultDTO,
} from '@/types/api'

/**
 * 总览（task-23 二次改造）：把这一屏做成「运营导航枢纽」。
 *  - 上半：一句话数据结论 + 刷新；KPI 四卡全部可点（跳对应管理页）；
 *  - 主块：浏览趋势（近一周/近一月，单一 hue 柱 + 网格 + 峰值注释）；
 *  - 次栏：今日/本周 PV + 待处理队列（可点的未读私信/举报/评论）；
 *  - 下半新块（更饱满）：最近文章（可点进入编辑）+ 热门 Top（按浏览）+ 存储用量（跳文件管理）+ 快捷入口宫格。
 * 数据全部走现有 admin API；新块用 Promise.allSettled 容错，任一失败只让该块降级，不拖垮整屏。
 */

const RANGE_OPTIONS: Array<{ label: string; value: VisitStatsRange }> = [
  { label: '近一周', value: 'week' },
  { label: '近一月', value: 'moon' },
]

interface KpiDef {
  key: keyof OverviewSummaryDTO
  label: string
  icon: Component
  /** 点击跳转的管理页 */
  to: string
  /** 图标色阶（对应 css 里的 --aN 类） */
  tone: 1 | 2 | 3 | 4
}

const KPI_DEFS: KpiDef[] = [
  { key: 'contents', label: '内容总数', icon: Document, to: '/admin/articles', tone: 1 },
  { key: 'comments', label: '评论总数', icon: ChatDotRound, to: '/admin/comments', tone: 2 },
  { key: 'unreadMessages', label: '未读私信', icon: Message, to: '/admin/messages', tone: 3 },
  { key: 'files', label: '文件总数', icon: FolderOpened, to: '/admin/files', tone: 4 },
]

interface QuickEntry {
  label: string
  desc: string
  icon: Component
  to: string
}

/** 快捷入口宫格：覆盖后台日常动作 */
const QUICK_ENTRIES: QuickEntry[] = [
  { label: '写文章', desc: '新建内容', icon: Document, to: '/admin/articles/edit' },
  { label: '传图片', desc: '画廊作品', icon: Files, to: '/admin/gallery' },
  { label: '记日记', desc: '每日心情', icon: Notebook, to: '/admin/diaries' },
  { label: '审评论', desc: '互动维护', icon: ChatDotRound, to: '/admin/comments' },
  { label: '管文件', desc: '存储清理', icon: FolderOpened, to: '/admin/files' },
  { label: '用户权限', desc: '成员管理', icon: User, to: '/admin/users' },
]

const summary = ref<Partial<OverviewSummaryDTO> | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const updatedAt = ref<Date | null>(null)

const visitRange = ref<VisitStatsRange>('week')
const visitStats = ref<VisitStatsResultDTO | null>(null)
const visitLoading = ref(false)
const visitError = ref('')
const hoverIndex = ref<number | null>(null)

/* ---------- 最近文章 ---------- */
const recentArticles = ref<ContentView[]>([])
const recentLoading = ref(false)
const recentError = ref('')

/* ---------- 热门 Top（按浏览） ---------- */
const hotList = ref<ContentView[]>([])
const hotLoading = ref(false)
const hotError = ref('')

/* ---------- 存储用量 ---------- */
const storage = ref<StorageStatusDTO | null>(null)
const storageLoading = ref(false)
const storageError = ref('')

const kpis = computed(() =>
  KPI_DEFS.map((def) => ({ ...def, value: summary.value?.[def.key] as number | undefined })),
)

const viewsToday = computed(() => summary.value?.viewsToday)
const viewsWeek = computed(() => summary.value?.viewsWeek)

const points = computed(() => visitStats.value?.points ?? [])
const maxVisitCount = computed(() => points.value.reduce((max, p) => Math.max(max, p.count), 0))
const totalVisits = computed(() => points.value.reduce((sum, p) => sum + p.count, 0))
const avgVisits = computed(() => (points.value.length > 0 ? Math.round(totalVisits.value / points.value.length) : 0))

const peakPoint = computed(() => {
  if (!points.value.length) return null
  return points.value.reduce((a, b) => (b.count > a.count ? b : a))
})

const barPercent = (count: number): string => {
  if (maxVisitCount.value <= 0) return '2%'
  return `${Math.max(2, Math.round((count / maxVisitCount.value) * 100))}%`
}

const shortDate = (date: string): string => {
  const parts = date.split('-')
  return parts.length === 3 ? `${parts[1]}/${parts[2]}` : date
}

const axisTicks = computed(() => {
  const n = points.value.length
  if (n === 0) return []
  const idxs = n <= 4 ? points.value.map((_, i) => i) : [0, Math.round((n - 1) / 3), Math.round(((n - 1) * 2) / 3), n - 1]
  return [...new Set(idxs)].map((i) => ({ date: points.value[i].date, x: n > 1 ? (i / (n - 1)) * 100 : 50 }))
})

const finding = computed(() => {
  if (!peakPoint.value) return ''
  return `${shortDate(peakPoint.value.date)} 为高峰 ${formatCount(peakPoint.value.count)} 次，日均 ${formatCount(avgVisits.value)} 次`
})

const updatedLabel = computed(() => {
  if (!updatedAt.value) return ''
  const d = updatedAt.value
  const pad = (v: number) => String(v).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
})

/* ---------- 存储用量：主区/备份区占比条 ---------- */
const storageQuota = computed(() => storage.value?.quotaBytes ?? 0)
const primaryPercent = computed(() => {
  if (!storageQuota.value) return 0
  return Math.min(100, ((storage.value?.primaryBytes ?? 0) / storageQuota.value) * 100)
})
const backupPercent = computed(() => {
  if (!storageQuota.value) return 0
  return Math.min(100, ((storage.value?.backupBytes ?? 0) / storageQuota.value) * 100)
})
const storageTotalUsed = computed(() => (storage.value?.primaryBytes ?? 0) + (storage.value?.backupBytes ?? 0))
const storageAlert = computed(() => {
  const s = storage.value
  if (!s) return false
  return s.alert || s.backupPercent >= 80
})

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    summary.value = await getOverviewSummary()
    updatedAt.value = new Date()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '加载失败'
    summary.value = null
  } finally {
    loading.value = false
  }
}

async function loadVisits(): Promise<void> {
  visitLoading.value = true
  visitError.value = ''
  try {
    visitStats.value = await getVisitStats(visitRange.value)
  } catch (error) {
    visitError.value = error instanceof ApiError ? error.message : '加载失败'
    visitStats.value = null
  } finally {
    visitLoading.value = false
  }
}

/** 最近文章：listAdminContents 取已发布/最新 6 条（summary 已单独请求，此处容错） */
async function loadRecent(): Promise<void> {
  recentLoading.value = true
  recentError.value = ''
  try {
    const result = await listAdminContents({ page: 1, pageSize: 6, sort: 'latest' })
    recentArticles.value = result.records
  } catch (error) {
    recentError.value = error instanceof ApiError ? error.message : '加载失败'
    recentArticles.value = []
  } finally {
    recentLoading.value = false
  }
}

/** 热门 Top：取内容后按浏览数降序取前 5（后端暂未按 hot 排序时前端兜底） */
async function loadHot(): Promise<void> {
  hotLoading.value = true
  hotError.value = ''
  try {
    const result = await listAdminContents({ page: 1, pageSize: 20, sort: 'hot' })
    hotList.value = [...result.records].sort((a, b) => (b.views ?? 0) - (a.views ?? 0)).slice(0, 5)
  } catch (error) {
    hotError.value = error instanceof ApiError ? error.message : '加载失败'
    hotList.value = []
  } finally {
    hotLoading.value = false
  }
}

async function loadStorage(): Promise<void> {
  storageLoading.value = true
  storageError.value = ''
  try {
    storage.value = await getStorageStatus()
  } catch (error) {
    storageError.value = error instanceof ApiError ? error.message : '加载失败'
    storage.value = null
  } finally {
    storageLoading.value = false
  }
}

function pickRange(range: VisitStatsRange): void {
  visitRange.value = range
  hoverIndex.value = null
  void loadVisits()
}

/** 一次性并发拉全部块，互不阻塞（allSettled 语义由各 loader 自身捕获实现） */
function refreshAll(): void {
  void load()
  void loadVisits()
  void loadRecent()
  void loadHot()
  void loadStorage()
}

onMounted(() => {
  refreshAll()
})

/* 内容态中文标签 */
const CONTENT_STATE_LABEL: Record<string, string> = {
  draft: '草稿',
  published: '已发布',
  hidden: '已隐藏',
  deleted: '已删除',
}

function contentInitial(title: string): string {
  return (title || '·').slice(0, 1).toUpperCase()
}
</script>

<template>
  <div class="lb-overview">
    <!-- 标题行：名词标题 + 一句数据结论 -->
    <header class="lb-page-header lb-ov-header">
      <div class="lb-ov-heading">
        <h2>总览</h2>
        <span class="lb-muted">{{ finding || '站点运营一览' }}</span>
      </div>
      <div class="lb-ov-tools">
        <span v-if="updatedLabel" class="lb-ov-stamp lb-num">更新于 {{ updatedLabel }}</span>
        <button class="lb-btn lb-btn--secondary lb-ov-refresh" type="button" @click="refreshAll">
          <el-icon :size="14"><Refresh /></el-icon>刷新
        </button>
      </div>
    </header>

    <div v-if="errorMessage" class="lb-ov-error">
      <span>{{ errorMessage }}（服务暂未就绪，显示的是最近一次可用的数据）</span>
      <button class="lb-btn lb-btn--text" type="button" @click="refreshAll">重试</button>
    </div>

    <!-- ① KPI：4 张可点统计卡（跳对应管理页） -->
    <section v-loading="loading" class="lb-ov-kpis">
      <RouterLink
        v-for="kpi in kpis"
        :key="kpi.key"
        :to="kpi.to"
        class="lb-stat-card"
        :class="`lb-stat-card--a${kpi.tone}`"
      >
        <span class="lb-stat-icon" aria-hidden="true">
          <el-icon :size="20"><component :is="kpi.icon" /></el-icon>
        </span>
        <div class="lb-stat-body">
          <span class="lb-stat-label">{{ kpi.label }}</span>
          <strong v-if="kpi.value !== undefined && kpi.value !== null" class="lb-stat-value lb-num">
            {{ formatCount(kpi.value) }}
          </strong>
          <strong v-else class="lb-stat-value lb-stat-zero">—</strong>
        </div>
        <span class="lb-stat-go" aria-hidden="true">→</span>
      </RouterLink>
    </section>

    <!-- ② 浏览趋势柱图 + 今日/本周 PV + 待处理队列（等高双栏） -->
    <div class="lb-ov-row lb-ov-row--main">
      <!-- 主块：浏览趋势 -->
      <section class="lb-ov-card lb-visit-card" data-testid="ov-visit-chart">
        <div class="lb-block-head">
          <div>
            <h3 class="lb-section-title">浏览趋势</h3>
            <p class="lb-visit-legend lb-num">
              合计 <strong>{{ formatCount(totalVisits) }}</strong> 次 · 日均 {{ formatCount(avgVisits) }} 次
            </p>
          </div>
          <div class="lb-seg" role="tablist" aria-label="时间范围">
            <button
              v-for="option in RANGE_OPTIONS"
              :key="option.value"
              type="button"
              class="lb-seg-btn"
              :class="{ 'is-on': visitRange === option.value }"
              role="tab"
              :aria-selected="visitRange === option.value"
              @click="pickRange(option.value)"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <div v-if="visitError" class="lb-visit-state lb-empty">
          {{ visitError }} · 最后数据 {{ updatedLabel || '未知' }}
          <button class="lb-btn lb-btn--text" type="button" @click="loadVisits">重试</button>
        </div>
        <div v-else-if="visitLoading" class="lb-visit-state">
          <span class="lb-load-bar" />
        </div>
        <template v-else-if="points.length > 0">
          <div class="lb-chart" @mouseleave="hoverIndex = null">
            <div class="lb-chart-grid" aria-hidden="true">
              <span v-for="i in 3" :key="i" />
            </div>
            <div class="lb-chart-y" aria-hidden="true">
              <span>{{ formatCount(maxVisitCount) }}</span>
              <span>{{ formatCount(Math.round(maxVisitCount / 2)) }}</span>
              <span>0</span>
            </div>
            <div
              v-if="peakPoint"
              class="lb-chart-annot"
              :style="{
                left: `${points.length > 1 ? (points.indexOf(peakPoint) / (points.length - 1)) * 100 : 50}%`,
                bottom: `calc(${barPercent(peakPoint.count)} + 6px)`,
              }"
            >
              峰值 {{ formatCount(peakPoint.count) }}
            </div>
            <div
              v-for="(point, index) in points"
              :key="point.date"
              class="lb-chart-bar"
              :class="{ 'is-hover': hoverIndex === index }"
              @mouseenter="hoverIndex = index"
            >
              <div class="lb-chart-tip" :class="{ 'is-visible': hoverIndex === index }">
                <strong class="lb-num">{{ formatCount(point.count) }}</strong>
                <span class="lb-num">{{ point.date }}</span>
              </div>
              <span class="lb-chart-fill" :style="{ height: barPercent(point.count) }" />
            </div>
          </div>
          <div class="lb-chart-axis" aria-hidden="true">
            <span v-for="tick in axisTicks" :key="tick.date" class="lb-num" :style="{ left: `${tick.x}%` }">
              {{ shortDate(tick.date) }}
            </span>
          </div>
        </template>
        <div v-else class="lb-visit-state lb-empty">这段时间还没有浏览记录。</div>
      </section>

      <!-- 次栏：今日/本周 PV + 待处理队列 -->
      <aside class="lb-ov-rail">
        <section class="lb-ov-card lb-ov-pv" data-testid="ov-pv">
          <div class="lb-pv-cell">
            <span class="lb-pv-label">今日浏览</span>
            <strong class="lb-pv-value lb-num">{{ viewsToday !== undefined ? formatCount(viewsToday) : '—' }}</strong>
          </div>
          <div class="lb-pv-cell">
            <span class="lb-pv-label">本周浏览</span>
            <strong class="lb-pv-value lb-num">{{ viewsWeek !== undefined ? formatCount(viewsWeek) : '—' }}</strong>
          </div>
        </section>

        <section class="lb-ov-card lb-queue" data-testid="ov-queue">
          <div class="lb-block-head">
            <h3 class="lb-section-title">待处理</h3>
          </div>
          <ul class="lb-queue-list">
            <li>
              <RouterLink to="/admin/messages" class="lb-queue-row">
                <span class="lb-queue-icon"><el-icon :size="15"><Message /></el-icon></span>
                <span class="lb-queue-text">未读私信</span>
                <span class="lb-queue-count lb-num">{{ summary?.unreadMessages ?? 0 }}</span>
              </RouterLink>
            </li>
            <li>
              <RouterLink to="/admin/reports" class="lb-queue-row">
                <span class="lb-queue-icon"><el-icon :size="15"><Warning /></el-icon></span>
                <span class="lb-queue-text">举报处理</span>
                <span class="lb-queue-go">查看</span>
              </RouterLink>
            </li>
            <li>
              <RouterLink to="/admin/comments" class="lb-queue-row">
                <span class="lb-queue-icon"><el-icon :size="15"><ChatDotRound /></el-icon></span>
                <span class="lb-queue-text">评论管理</span>
                <span class="lb-queue-go">查看</span>
              </RouterLink>
            </li>
          </ul>
        </section>
      </aside>
    </div>

    <!-- ③ 最近文章 + 右栏（热门 Top5 / 存储用量） -->
    <div class="lb-ov-row lb-ov-row--feed">
      <!-- 最近文章 -->
      <section class="lb-ov-card lb-recent" data-testid="ov-recent">
        <div class="lb-block-head">
          <h3 class="lb-section-title">最近文章</h3>
          <RouterLink to="/admin/articles" class="lb-block-more">全部 →</RouterLink>
        </div>
        <div v-if="recentError" class="lb-block-state">
          {{ recentError }}
          <button class="lb-btn lb-btn--text" type="button" @click="loadRecent">重试</button>
        </div>
        <div v-else-if="recentLoading" class="lb-block-skel">
          <span v-for="n in 4" :key="n" class="lb-ed-skel" />
        </div>
        <ul v-else-if="recentArticles.length" class="lb-recent-list">
          <li v-for="item in recentArticles" :key="item.id">
            <RouterLink :to="`/admin/articles/edit/${item.id}`" class="lb-recent-row">
              <span class="lb-recent-avatar" aria-hidden="true">{{ contentInitial(item.title) }}</span>
              <span class="lb-recent-main">
                <span class="lb-recent-title">{{ item.title || '（无标题）' }}</span>
                <span class="lb-recent-meta lb-num">
                  <span>{{ CONTENT_STATE_LABEL[item.state] ?? item.state }}</span>
                  <span v-if="item.views !== undefined">{{ formatCount(item.views) }} 浏览</span>
                  <span v-if="item.publishedAt">{{ formatDate(item.publishedAt) }}</span>
                </span>
              </span>
              <span class="lb-recent-go" aria-hidden="true">→</span>
            </RouterLink>
          </li>
        </ul>
        <div v-else class="lb-block-state lb-empty">暂无文章，去「写文章」开始。</div>
      </section>

      <!-- 右栏：热门 Top5 + 存储用量 -->
      <aside class="lb-ov-rail">
        <!-- 热门 Top -->
        <section class="lb-ov-card lb-hot" data-testid="ov-hot">
        <div class="lb-block-head">
          <h3 class="lb-section-title">热门 Top5</h3>
          <RouterLink to="/admin/articles" class="lb-block-more">更多 →</RouterLink>
        </div>
        <div v-if="hotError" class="lb-block-state">
          {{ hotError }}
          <button class="lb-btn lb-btn--text" type="button" @click="loadHot">重试</button>
        </div>
        <div v-else-if="hotLoading" class="lb-block-skel">
          <span v-for="n in 4" :key="n" class="lb-ed-skel" />
        </div>
        <ol v-else-if="hotList.length" class="lb-hot-list">
          <li v-for="(item, i) in hotList" :key="item.id">
            <RouterLink :to="`/admin/articles/edit/${item.id}`" class="lb-hot-row">
              <span class="lb-hot-rank lb-num" :class="`is-rank-${i + 1}`">{{ i + 1 }}</span>
              <span class="lb-hot-title">{{ item.title || '（无标题）' }}</span>
              <span class="lb-hot-views lb-num">{{ formatCount(item.views ?? 0) }}</span>
            </RouterLink>
          </li>
        </ol>
        <div v-else class="lb-block-state lb-empty">暂无浏览数据。</div>
      </section>

        <!-- 存储用量 -->
        <section class="lb-ov-card lb-storage" data-testid="ov-storage">
        <div class="lb-block-head">
          <h3 class="lb-section-title">存储用量</h3>
          <RouterLink to="/admin/files" class="lb-block-more">文件管理 →</RouterLink>
        </div>
        <div v-if="storageError" class="lb-block-state">
          {{ storageError }}
          <button class="lb-btn lb-btn--text" type="button" @click="loadStorage">重试</button>
        </div>
        <div v-else-if="storageLoading" class="lb-block-skel">
          <span class="lb-ed-skel" style="height: 64px" />
        </div>
        <div v-else-if="storage" class="lb-storage-body">
          <div class="lb-storage-top">
            <span class="lb-storage-used lb-num">{{ formatBytes(storageTotalUsed) }}</span>
            <span class="lb-storage-quota lb-num">配额 {{ formatBytes(storage.quotaBytes) }}</span>
          </div>
          <div class="lb-storage-bar" :class="{ 'is-alert': storageAlert }">
            <span class="lb-storage-fill lb-storage-fill--primary" :style="{ width: `${primaryPercent}%` }" />
            <span class="lb-storage-fill lb-storage-fill--backup" :style="{ width: `${backupPercent}%` }" />
          </div>
          <div class="lb-storage-legend">
            <span class="lb-storage-key"><i class="lb-dot-primary" />主区 {{ formatBytes(storage.primaryBytes) }} · {{ storage.primaryCount }} 个</span>
            <span class="lb-storage-key"><i class="lb-dot-backup" />备份区 {{ formatBytes(storage.backupBytes) }} · {{ storage.backupCount }} 个</span>
          </div>
          <div v-if="storageAlert" class="lb-storage-alert">
            备份区占用 {{ storage.backupPercent.toFixed(1) }}%，建议清理回收站。
          </div>
        </div>
        <div v-else class="lb-block-state lb-empty">暂无存储数据。</div>
      </section>
      </aside>
    </div>

    <!-- ④ 快捷入口（整行 6 宫格） -->
    <div class="lb-ov-row lb-ov-row--single">
      <!-- 快捷入口宫格 -->
      <section class="lb-ov-card lb-shortcuts" data-testid="ov-shortcuts">
        <div class="lb-block-head">
          <h3 class="lb-section-title">快捷入口</h3>
        </div>
        <div class="lb-shortcut-grid">
          <RouterLink v-for="entry in QUICK_ENTRIES" :key="entry.to" :to="entry.to" class="lb-shortcut">
            <span class="lb-shortcut-icon" aria-hidden="true">
              <el-icon :size="18"><component :is="entry.icon" /></el-icon>
            </span>
            <span class="lb-shortcut-label">{{ entry.label }}</span>
            <span class="lb-shortcut-desc">{{ entry.desc }}</span>
          </RouterLink>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.lb-overview {
  --ov-gap: var(--app-gap);
  display: flex;
  flex-direction: column;
  gap: var(--ov-gap);
}

.lb-ov-header {
  margin-bottom: 0;
}

.lb-ov-heading {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.lb-ov-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.lb-ov-stamp {
  font-size: 12px;
  color: var(--ed-ink-soft);
}

.lb-ov-refresh :deep(.el-icon) {
  margin-right: 2px;
}

/* 亮卡片通用面 */
.lb-ov-card {
  background: var(--ed-card);
  border: 1px solid color-mix(in srgb, var(--ed-ink) 5%, transparent);
  border-radius: var(--ed-radius-lg);
  box-shadow: var(--ed-shadow-sm);
}

.lb-ov-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  font-size: 13px;
  color: var(--app-warning);
  background: var(--app-pill-warning-bg);
  border-radius: var(--ed-radius-md);
}

/* ---------- KPI 统计卡（可点，跳对应管理页） ---------- */
.lb-ov-kpis {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ov-gap);
}

@media (max-width: 1024px) {
  .lb-ov-kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .lb-ov-kpis {
    grid-template-columns: 1fr;
  }
}

.lb-stat-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px;
  background: var(--ed-card);
  border: 1px solid color-mix(in srgb, var(--ed-ink) 5%, transparent);
  border-radius: var(--ed-radius-lg);
  box-shadow: var(--ed-shadow-sm);
  text-decoration: none;
  color: inherit;
  transition: transform 260ms cubic-bezier(0.22, 1, 0.36, 1), box-shadow 260ms cubic-bezier(0.22, 1, 0.36, 1),
    border-color 260ms ease;
}

.lb-stat-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--ed-shadow-md);
  border-color: color-mix(in srgb, var(--ed-accent) 24%, transparent);
}

.lb-stat-go {
  position: absolute;
  top: 16px;
  right: 16px;
  font-size: 14px;
  color: var(--ed-ink-faint);
  opacity: 0;
  transform: translateX(-4px);
  transition: opacity 200ms ease, transform 200ms ease;
}

.lb-stat-card:hover .lb-stat-go {
  opacity: 1;
  transform: translateX(0);
  color: var(--ed-accent-ink);
}

.lb-stat-icon {
  flex: none;
  width: 46px;
  height: 46px;
  border-radius: var(--ed-radius-md);
  display: grid;
  place-items: center;
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-stat-card--a1 .lb-stat-icon {
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-stat-card--a2 .lb-stat-icon {
  color: var(--app-pill-positive-ink);
  background: var(--app-pill-positive-bg);
}

.lb-stat-card--a3 .lb-stat-icon {
  color: var(--app-pill-accent-ink);
  background: var(--app-pill-accent-bg);
}

.lb-stat-card--a4 .lb-stat-icon {
  color: var(--app-pill-warning-ink);
  background: var(--app-pill-warning-bg);
}

.lb-stat-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.lb-stat-label {
  font-size: 13px;
  color: var(--ed-ink-soft);
}

.lb-stat-value {
  font-family: var(--ed-font-text);
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: var(--ed-ink);
  font-variant-numeric: tabular-nums;
}

.lb-stat-zero {
  color: var(--ed-ink-faint);
  font-weight: 400;
}

/* ---------- 统一栅格行 ---------- */
.lb-ov-row {
  display: grid;
  gap: var(--ov-gap);
  /* 卡片按自身内容高度落位，避免某个短模块被强行拉高后留下大片空白 */
  align-items: start;
}

/* 主行：趋势图（主）+ 右栏（PV + 待处理）等高 */
.lb-ov-row--main {
  grid-template-columns: minmax(0, 2.1fr) minmax(280px, 1fr);
}

/* 成对行：两列等宽等高 */
.lb-ov-row--pair {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

/* 稿件行：最近文章（主）+ 右栏（热门 / 存储） */
.lb-ov-row--feed {
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, 1fr);
}

/* 整行单卡 */
.lb-ov-row--single {
  grid-template-columns: 1fr;
}

@media (max-width: 1024px) {
  .lb-ov-row--main,
  .lb-ov-row--feed {
    grid-template-columns: 1fr;
  }
  .lb-ov-row--main .lb-ov-rail,
  .lb-ov-row--feed .lb-ov-rail {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--ov-gap);
  }
}

@media (max-width: 720px) {
  .lb-ov-row--pair {
    grid-template-columns: 1fr;
  }
  .lb-ov-row--main .lb-ov-rail {
    grid-template-columns: 1fr;
  }
}

/* 让每张卡在行内纵向排列内容（高度随内容，不做强行拉伸） */
.lb-ov-row > .lb-ov-card,
.lb-ov-rail > .lb-ov-card {
  display: flex;
  flex-direction: column;
}

/* PV 卡保持两格横排（覆盖上面的 flex 列布局） */
.lb-ov-rail > .lb-ov-pv {
  display: grid;
  grid-template-columns: 1fr 1fr;
}

/* ---------- 浏览趋势 ---------- */
.lb-visit-card {
  padding: clamp(18px, 2vw, 24px);
}

.lb-visit-card .lb-block-head {
  align-items: flex-start;
  margin-bottom: 20px;
}

.lb-visit-legend {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--ed-ink-soft);
}

.lb-visit-legend strong {
  color: var(--ed-ink);
  font-weight: 600;
}

.lb-seg {
  display: inline-flex;
  padding: 3px;
  border-radius: var(--ed-radius-pill);
  background: color-mix(in srgb, var(--ed-ink) 6%, var(--ed-paper));
  flex: none;
}

.lb-seg-btn {
  border: 0;
  background: transparent;
  font-family: inherit;
  font-size: 12px;
  color: var(--ed-ink-soft);
  padding: 5px 14px;
  border-radius: var(--ed-radius-pill);
  cursor: pointer;
  transition: background 140ms ease, color 140ms ease;
}

.lb-seg-btn.is-on {
  background: var(--ed-card);
  color: var(--ed-ink);
  box-shadow: var(--ed-shadow-sm);
}

.lb-visit-state {
  height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.lb-load-bar {
  width: 100%;
  height: 100%;
  border-radius: var(--ed-radius-md);
  background: color-mix(in srgb, var(--ed-ink) 5%, var(--ed-paper));
}

/* ---------- 柱状图 ---------- */
.lb-chart {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 240px;
  padding: 6px 0 0 34px;
}

.lb-chart-grid {
  position: absolute;
  left: 34px;
  right: 0;
  top: 6px;
  bottom: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  pointer-events: none;
}

.lb-chart-grid span {
  height: 1px;
  background: var(--ed-rule-soft);
}

.lb-chart-y {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ed-ink-faint);
  font-variant-numeric: tabular-nums;
  text-align: right;
  width: 30px;
  padding: 2px 0 0;
}

.lb-chart-annot {
  position: absolute;
  transform: translateX(-50%);
  font-size: 11px;
  color: var(--ed-accent-ink);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  pointer-events: none;
  z-index: 2;
}

.lb-chart-annot::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 100%;
  width: 1px;
  height: 8px;
  background: color-mix(in srgb, var(--ed-accent) 40%, transparent);
}

.lb-chart-bar {
  position: relative;
  flex: 1;
  min-width: 5px;
  height: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  z-index: 1;
}

.lb-chart-fill {
  width: 100%;
  max-width: 30px;
  border-radius: 6px 6px 0 0;
  background: color-mix(in srgb, var(--ed-accent) 32%, var(--ed-card));
  transition: height 240ms ease, background 140ms ease;
}

.lb-chart-bar.is-hover .lb-chart-fill {
  background: var(--ed-accent);
}

.lb-chart-tip {
  position: absolute;
  bottom: calc(100% + 4px);
  left: 50%;
  transform: translate(-50%, 4px);
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 6px 9px;
  border-radius: var(--ed-radius-sm);
  background: var(--ed-card);
  border: 1px solid var(--ed-rule-soft);
  box-shadow: var(--ed-shadow-md);
  font-size: 11px;
  color: var(--ed-ink-soft);
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transition: opacity 140ms ease, transform 140ms ease;
  z-index: 3;
}

.lb-chart-tip strong {
  font-size: 12px;
  color: var(--ed-ink);
}

.lb-chart-tip.is-visible {
  opacity: 1;
  transform: translate(-50%, 0);
}

.lb-chart-axis {
  position: relative;
  height: 16px;
  margin-top: 8px;
  margin-left: 34px;
}

.lb-chart-axis span {
  position: absolute;
  transform: translateX(-50%);
  font-size: 11px;
  color: var(--ed-ink-faint);
  white-space: nowrap;
}

.lb-chart-axis span:first-child {
  transform: translateX(0);
}

.lb-chart-axis span:last-child {
  transform: translateX(-100%);
}

/* ---------- 右栏：PV + 待处理 ---------- */
.lb-ov-rail {
  display: flex;
  flex-direction: column;
  gap: var(--ov-gap);
  min-width: 0;
}

.lb-ov-pv {
  display: grid;
  grid-template-columns: 1fr 1fr;
  overflow: hidden;
  flex: none;
}

.lb-pv-cell {
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.lb-pv-label {
  font-size: 13px;
  color: var(--ed-ink-soft);
}

.lb-pv-cell + .lb-pv-cell {
  border-left: 1px solid var(--ed-rule-soft);
}

.lb-pv-value {
  font-family: var(--ed-font-text);
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--ed-ink);
  font-variant-numeric: tabular-nums;
}

.lb-queue {
  padding: 16px 12px;
  flex: 1;
}

.lb-queue .lb-block-head {
  margin-bottom: 8px;
  padding: 0 8px;
}

.lb-queue-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.lb-queue-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  text-decoration: none;
  color: var(--ed-ink);
  font-size: 14px;
  border-radius: var(--ed-radius-sm);
  transition: background 140ms ease;
}

.lb-queue-row:hover {
  background: color-mix(in srgb, var(--ed-accent) 7%, var(--ed-card));
}

.lb-queue-icon {
  display: inline-flex;
  color: var(--ed-ink-soft);
  flex: none;
}

.lb-queue-text {
  flex: 1;
}

.lb-queue-count {
  font-weight: 600;
  color: var(--ed-ink);
  min-width: 20px;
  text-align: right;
}

.lb-queue-go {
  font-size: 12px;
  color: var(--ed-accent-ink);
}

/* ---------- 新块通用 ---------- */
.lb-recent,
.lb-hot,
.lb-storage,
.lb-shortcuts {
  padding: clamp(18px, 2vw, 22px);
}

.lb-block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.lb-section-title {
  margin: 0;
  font-family: var(--ed-font-text);
  font-size: 15px;
  font-weight: 700;
  color: var(--ed-ink);
  letter-spacing: -0.01em;
}

.lb-block-more {
  font-size: 12px;
  color: var(--ed-accent-ink);
  text-decoration: none;
  white-space: nowrap;
  transition: opacity 160ms ease;
}

.lb-block-more:hover {
  opacity: 0.65;
}

.lb-block-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 28px 16px;
  font-size: 13px;
  color: var(--ed-ink-soft);
}

.lb-block-skel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.lb-block-skel .lb-ed-skel {
  height: 40px;
  border-radius: var(--ed-radius-sm);
}

/* 最近文章列表 */
.lb-recent-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.lb-recent-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 8px;
  margin: 0 -8px;
  border-radius: var(--ed-radius-sm);
  text-decoration: none;
  color: inherit;
  transition: background 140ms ease;
}

.lb-recent-row:hover {
  background: color-mix(in srgb, var(--ed-accent) 6%, var(--ed-card));
}

.lb-recent-avatar {
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: var(--ed-radius-sm);
  display: grid;
  place-items: center;
  font-family: var(--ed-font-display);
  font-size: 15px;
  font-weight: 600;
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-recent-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.lb-recent-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ed-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-recent-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: var(--ed-ink-soft);
}

.lb-recent-go {
  flex: none;
  font-size: 13px;
  color: var(--ed-ink-faint);
  opacity: 0;
  transition: opacity 180ms ease, transform 180ms ease;
}

.lb-recent-row:hover .lb-recent-go {
  opacity: 1;
  transform: translateX(2px);
  color: var(--ed-accent-ink);
}

/* 热门 Top 榜单 */
.lb-hot-list {
  list-style: none;
  margin: 0;
  padding: 0;
  counter-reset: none;
  display: flex;
  flex-direction: column;
}

.lb-hot-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 8px;
  margin: 0 -8px;
  border-radius: var(--ed-radius-sm);
  text-decoration: none;
  color: inherit;
  transition: background 140ms ease;
}

.lb-hot-row:hover {
  background: color-mix(in srgb, var(--ed-accent) 6%, var(--ed-card));
}

.lb-hot-rank {
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: var(--ed-radius-pill);
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 700;
  color: var(--ed-ink-soft);
  background: color-mix(in srgb, var(--ed-ink) 6%, var(--ed-paper));
}

.lb-hot-row .lb-hot-rank.is-rank-1 {
  color: #fff;
  background: var(--ed-accent);
}

.lb-hot-row .lb-hot-rank.is-rank-2 {
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
}

.lb-hot-row .lb-hot-rank.is-rank-3 {
  color: var(--ed-accent-ink);
  background: color-mix(in srgb, var(--ed-accent) 20%, var(--ed-card));
}

.lb-hot-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--ed-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lb-hot-views {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: var(--ed-ink-soft);
}

/* 存储用量 */
.lb-storage-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lb-storage-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.lb-storage-used {
  font-family: var(--ed-font-text);
  font-size: 24px;
  font-weight: 700;
  color: var(--ed-ink);
  letter-spacing: -0.01em;
}

.lb-storage-quota {
  font-size: 12px;
  color: var(--ed-ink-soft);
}

.lb-storage-bar {
  display: flex;
  height: 10px;
  border-radius: var(--ed-radius-pill);
  overflow: hidden;
  background: color-mix(in srgb, var(--ed-ink) 6%, var(--ed-paper));
}

.lb-storage-fill {
  height: 100%;
  transition: width 400ms ease;
}

.lb-storage-fill--primary {
  background: var(--ed-accent);
}

.lb-storage-fill--backup {
  background: color-mix(in srgb, var(--app-warning, #e0a13d) 85%, var(--ed-card));
}

.lb-storage-bar.is-alert .lb-storage-fill--backup {
  background: var(--app-negative, #d64545);
}

.lb-storage-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12px;
  color: var(--ed-ink-soft);
  font-variant-numeric: tabular-nums;
}

.lb-storage-key {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.lb-storage-key i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
}

.lb-dot-primary {
  background: var(--ed-accent);
}

.lb-dot-backup {
  background: color-mix(in srgb, var(--app-warning, #e0a13d) 85%, var(--ed-card));
}

.lb-storage-alert {
  padding: 8px 12px;
  border-radius: var(--ed-radius-sm);
  font-size: 12px;
  color: var(--app-negative, #d64545);
  background: color-mix(in srgb, var(--app-negative, #d64545) 10%, transparent);
}

/* 快捷入口宫格 */
.lb-shortcut-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

/* 整行铺开时一排 6 个，高度更紧凑 */
@media (min-width: 1180px) {
  .lb-ov-row--single .lb-shortcut-grid {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .lb-shortcut-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.lb-shortcut {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 14px;
  border-radius: var(--ed-radius-md);
  background: color-mix(in srgb, var(--ed-ink) 3%, var(--ed-card));
  border: 1px solid color-mix(in srgb, var(--ed-ink) 5%, transparent);
  text-decoration: none;
  color: inherit;
  transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1), border-color 200ms ease, background 200ms ease;
}

.lb-shortcut:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--ed-accent) 26%, transparent);
  background: var(--ed-accent-soft);
}

.lb-shortcut-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--ed-radius-sm);
  color: var(--ed-accent-ink);
  background: var(--ed-accent-soft);
  margin-bottom: 2px;
}

.lb-shortcut:hover .lb-shortcut-icon {
  background: var(--ed-card);
}

.lb-shortcut-label {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ed-ink);
}

.lb-shortcut-desc {
  font-size: 11.5px;
  color: var(--ed-ink-soft);
}

@media (prefers-reduced-motion: reduce) {
  .lb-chart-fill,
  .lb-chart-tip,
  .lb-seg-btn,
  .lb-queue-row,
  .lb-stat-card,
  .lb-stat-go,
  .lb-recent-row,
  .lb-recent-go,
  .lb-hot-row,
  .lb-shortcut,
  .lb-storage-fill {
    transition: none;
  }
}
</style>
