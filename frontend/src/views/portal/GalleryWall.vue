<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { listPortalContents } from '@/api/portal'
import { ApiError } from '@/api/request'
import CoverImage from '@/components/CoverImage.vue'
import PaginationBar from '@/components/PaginationBar.vue'
import { formatCount } from '@/utils/format'
import type { ContentView } from '@/types/api'

/**
 * 画廊墙（方案 A · 亮卡片）：GET /portal/contents?contentType=image（published）。
 * 页眉：Newsreader 大标题「画廊」+ 导语 + 共 N 件（.lb-ed-display / .lb-ed-lead）。
 * 瀑布流：CSS columns 多列不等高，每格是白色圆角卡 .lb-ed-card：真实图（CoverImage, ratio=auto
 * 保原始比例）+ 底部 scrim 浮层（标题 / 点赞），hover 卡片微抬升 + 图片轻放大（由 .lb-ed-card 提供）。
 * 颜色全走 CSS 变量（--ed-*）；进入 .lb-ed-reveal 错峰；空/加载/错误态；分页 + query 同步保留。
 */
const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 24

const records = ref<ContentView[]>([])
const total = ref(0)
const page = ref(Number(route.query.page ?? 1) || 1)
const loading = ref(false)
const errorMessage = ref('')

const items = computed(() => records.value)
const hasData = computed(() => items.value.length > 0)

/* ---- 进入渐显编排：每张卡按序号错峰 ---- */
const gridRef = ref<HTMLElement | null>(null)
const reducedMotion =
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

let io: IntersectionObserver | null = null

function armReveal(): void {
  const host = gridRef.value
  if (!host) return
  const cards = Array.from(host.querySelectorAll<HTMLElement>('.lb-wall-item'))
  if (reducedMotion) {
    cards.forEach((el) => el.classList.add('lb-ed-reveal', 'is-in'))
    return
  }
  cards.forEach((el) => el.classList.add('lb-ed-reveal', 'is-armed'))
  io?.disconnect()
  io = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          const el = entry.target as HTMLElement
          el.dataset.delay = String((Number(el.dataset.idx ?? 0) % 5) + 1)
          el.classList.add('is-in')
          io?.unobserve(el)
        }
      }
    },
    { rootMargin: '0px 0px -8% 0px', threshold: 0.06 },
  )
  cards.forEach((el, idx) => {
    el.dataset.idx = String(idx)
    io?.observe(el)
  })
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listPortalContents({
      contentType: 'image',
      page: page.value,
      pageSize: PAGE_SIZE,
      sort: 'latest',
    })
    records.value = result.records
    total.value = result.total
    await Promise.resolve()
    armReveal()
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : '画廊加载失败'
    records.value = []
  } finally {
    loading.value = false
  }
}

watch(page, () => {
  void router.replace({ query: page.value > 1 ? { page: String(page.value) } : {} })
  void load()
})

onMounted(load)
onBeforeUnmount(() => io?.disconnect())
</script>

<template>
  <div class="lb-ed lb-wall">
    <header class="lb-ed-section lb-wall-head">
      <div class="lb-ed-wrap lb-wall-inner">
        <p class="lb-ed-caption lb-wall-kicker">
          <span class="lb-ed-dot" aria-hidden="true"></span>
          {{ total > 0 ? `共 ${formatCount(total)} 件作品 · 用镜头收藏光与影` : '用镜头收藏光与影' }}
        </p>
        <h1 class="lb-ed-display lb-wall-title">画廊</h1>
        <p class="lb-ed-lead lb-wall-lead">
          光、影，与被时光温柔留住的瞬间。每一帧都是一次安静的注视。
        </p>
      </div>
    </header>

    <div v-loading="loading" class="lb-ed-wrap lb-wall-body">
      <el-alert v-if="errorMessage" type="error" class="lb-wall-alert" :title="errorMessage" :closable="false" />

      <el-empty
        v-if="items.length === 0 && !loading && !errorMessage"
        description="暂无作品"
        class="lb-wall-empty"
      />

      <div v-else ref="gridRef" class="lb-wall-grid">
        <RouterLink
          v-for="(item, index) in items"
          :key="item.id"
          :to="`/gallery/${item.id}`"
          class="lb-ed-card lb-wall-item"
          :data-delay="String((index % 5) + 1)"
        >
          <div class="lb-ed-thumb lb-wall-media">
            <CoverImage
              :storage-key="item.coverStorageKey"
              ratio="auto"
              sizes="(max-width: 720px) 50vw, (max-width: 1100px) 40vw, 33vw"
              :alt="item.title || '作品'"
            />
            <div class="lb-wall-scrim">
              <h3 class="lb-wall-name">{{ item.title || '未命名作品' }}</h3>
              <span class="lb-wall-like">♥ {{ formatCount(item.praises) }}</span>
            </div>
          </div>
        </RouterLink>
      </div>

      <PaginationBar
        v-if="hasData || total > 0"
        v-model:page="page"
        :page-size="PAGE_SIZE"
        :total="total"
        :disabled="loading"
      />
    </div>
  </div>
</template>

<style scoped>
.lb-wall {
  padding-bottom: clamp(32px, 5vw, 72px);
}

/* ---------- 首屏 ---------- */
.lb-wall-head {
  padding-top: clamp(28px, 5vw, 68px);
  padding-bottom: clamp(32px, 5vw, 64px);
}

.lb-wall-inner {
  display: flex;
  flex-direction: column;
  gap: clamp(14px, 2vw, 22px);
}

.lb-wall-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.lb-wall-title {
  color: var(--ed-ink);
}

.lb-wall-lead {
  margin: 0;
  max-width: 56ch;
}

/* ---------- 主体 ---------- */
.lb-wall-body {
  padding-top: 8px;
}

.lb-wall-alert {
  margin-bottom: 28px;
}

.lb-wall-empty {
  padding: clamp(60px, 10vw, 130px) 0;
}

/* 瀑布流：多列不等高，白色圆角卡 */
.lb-wall-grid {
  /* 桌面 3 列大图块：6/9/12 件作品恰好满排，右侧不留空洞 */
  columns: 3 280px;
  column-gap: clamp(18px, 2vw, 28px);
}

.lb-wall-item {
  display: block;
  break-inside: avoid;
  margin-bottom: clamp(16px, 2vw, 26px);
}

.lb-wall-media {
  position: relative;
  width: 100%;
  border-radius: var(--ed-radius-lg);
  background: var(--ed-accent-soft);
}

/* CoverImage 内部 img 已 object-fit:cover；ratio=auto 时按原始高度自适应，
   让瀑布流错落自然。避免其默认 10px 圆角与卡面双重圆角，内层顶角随卡面。 */
.lb-wall-media :deep(.lb-cover) {
  border-radius: inherit;
}

/* 底部 scrim 浮层：hover 才浮现，标题 + 点赞 */
.lb-wall-scrim {
  position: absolute;
  inset: auto 0 0 0;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
  padding: 40px 16px 14px;
  color: #fff;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.62));
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 260ms cubic-bezier(0.22, 1, 0.36, 1),
    transform 260ms cubic-bezier(0.22, 1, 0.36, 1);
}

.lb-wall-item:hover .lb-wall-scrim,
.lb-wall-item:focus-visible .lb-wall-scrim {
  opacity: 1;
  transform: none;
}

.lb-wall-name {
  margin: 0;
  font-family: var(--ed-font-display);
  font-weight: 500;
  font-size: var(--ed-step-small);
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.lb-wall-like {
  flex-shrink: 0;
  font-family: var(--ed-font-ui);
  font-size: var(--ed-step-caption);
  font-weight: 600;
  white-space: nowrap;
}

@media (max-width: 720px) {
  .lb-wall-grid {
    columns: 2 130px;
    column-gap: 12px;
  }

  .lb-wall-item {
    margin-bottom: 12px;
  }

  /* 触屏无 hover：常显标题浮层 */
  .lb-wall-scrim {
    opacity: 1;
    transform: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .lb-wall-scrim {
    transition: none;
    opacity: 1;
    transform: none;
  }
}
</style>
