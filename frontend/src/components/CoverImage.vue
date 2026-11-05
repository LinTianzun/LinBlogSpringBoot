<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 通用封面图（门户首页 / 文章列表 / 画廊 / 详情共用，无业务耦合）。
 *
 * props:
 * - storageKey?: string | null  媒体 storageKey（backend ContentView.coverStorageKey）。为空 → 直接渲染占位块。
 * - ratio?: string              CSS aspect-ratio，如 '16/9' | '4/3' | '1/1'；传 'auto' 则按图片原始比例自适应。
 * - sizes?: string              原生 sizes 属性（默认 '100vw'）。
 * - alt?: string                无障碍替代文本。
 * - widths?: number[]           srcset 候选宽度（默认 [320, 640, 1280]）。
 *
 * 图片 URL 规则：`/api/v1/media/${storageKey}?width=${w}`（后端 MediaController 参数名是 width）。
 * <img loading="lazy" decoding="async"> + srcset；@error 或空 key 时回退到主色淡底平涂占位块。
 * 颜色全部走 CSS 变量（--lb-primary / --main-color），自动适配多主题与暗色。
 */
const props = withDefaults(
  defineProps<{
    storageKey?: string | null
    ratio?: string
    sizes?: string
    alt?: string
    widths?: number[]
  }>(),
  {
    storageKey: null,
    ratio: '16/9',
    sizes: '100vw',
    alt: '',
    widths: () => [320, 640, 1280],
  },
)

const failed = ref(false)

// storageKey 变化时重置失败标记，允许新图重试加载。
watch(
  () => props.storageKey,
  () => {
    failed.value = false
  },
)

const mediaUrl = (w: number): string => `/api/v1/media/${props.storageKey}?width=${w}`

const hasImage = computed(() => !!props.storageKey && !failed.value)

/** 默认按 widths 最大值作 src，浏览器用 srcset 择优。 */
const srcSet = computed(() => props.widths.map((w) => `${mediaUrl(w)} ${w}w`).join(', '))
const fallbackSrc = computed(() => (props.storageKey ? mediaUrl(props.widths[props.widths.length - 1] ?? 1280) : ''))

/** ratio=auto 时不设固定宽高比，交给图片自身；否则用 aspect-ratio 占位避免 CLS。 */
const boxStyle = computed(() => (props.ratio === 'auto' ? {} : { aspectRatio: props.ratio }))

function onError(): void {
  failed.value = true
}
</script>

<template>
  <div class="lb-cover" :class="{ 'lb-cover--auto': ratio === 'auto' }" :style="boxStyle">
    <img
      v-if="hasImage"
      class="lb-cover-img"
      :src="fallbackSrc"
      :srcset="srcSet"
      :sizes="sizes"
      :alt="alt"
      loading="lazy"
      decoding="async"
      @error="onError"
    />
    <div v-else class="lb-cover-placeholder" role="img" :aria-label="alt || '封面占位'">
      <svg class="lb-cover-glyph" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5v13A1.5 1.5 0 0 1 18.5 20h-13A1.5 1.5 0 0 1 4 18.5v-13Z"
          stroke="currentColor"
          stroke-width="1.4"
        />
        <circle cx="8.5" cy="9" r="1.6" fill="currentColor" />
        <path d="M4.5 17.5 9 13l3 2.6 3-3.1 4.5 5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>
  </div>
</template>

<style scoped>
.lb-cover {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-radius: 10px;
  background: var(--lb-card-bg, #fff);
}

.lb-cover-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.lb-cover--auto .lb-cover-img {
  height: auto;
}

.lb-cover-placeholder {
  width: 100%;
  height: 100%;
  min-height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: color-mix(in srgb, var(--lb-primary, var(--main-color, #18a058)) 62%, currentColor);
  background: color-mix(in srgb, var(--lb-primary, var(--main-color, #18a058)) 10%, var(--lb-card-bg, #fff));
}

.lb-cover-glyph {
  width: 34px;
  height: 34px;
  opacity: 0.55;
}
</style>
