<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useTemplateRef } from 'vue'
import {
  clampIntoWorld,
  DEFAULT_TUNING,
  estimateThrowVelocity,
  nextWanderTarget,
  stepPet,
  worldBounds,
  type DragSample,
  type PetBody,
  type PetWorld,
} from '@/utils/petPhysics'
import {
  IDLE_LINES,
  LIFT_LINES,
  POKE_LINES,
  SLEEP_LINES,
  THROW_LINES,
  WAKE_LINES,
  pickLine,
} from '@/utils/petLines'

/**
 * BlogPet — 门户右下角的网页宠物（pet v1）。
 * 灵感来自 dsh-pet，但为 LinBlog 重写：纯 Vue3 + 内联 SVG，不依赖任何外部素材/动画文件。
 *  - 外观：一团主色小猫身，颜色跟随主题（--ed-accent 四套主色 + 暗色 --ed-ink 自动适配）；
 *  - 行为：待机呼吸/眨眼、随机漫游、碎碎念气泡、久置不动打瞌睡、交互即醒；
 *  - 物理：可拖拽拎起、甩出后受重力反弹落回地板（纯函数物理见 utils/petPhysics.ts）；
 *  - 尊重 prefers-reduced-motion（关漫游与抛飞，保留静态）；
 *  - 位置持久化 localStorage 'linblog.pet'，跨分辨率自动 clamp 回视口。
 * 挂载点：PortalLayout.vue（门户全域）。装饰性组件：舞台 pointer-events:none，
 * 不阻塞页面任何交互；z-index 低于 Element Plus 弹层（~2000）。
 */

/** 宠物碰撞盒边长（px）；小屏收缩 */
function readPetSize(): number {
  return window.innerWidth < 560 ? 60 : 76
}

/** 持久化键（对齐 linblog.theme 命名风格） */
const PET_STORAGE_KEY = 'linblog.pet'
/** 拖拽判定：位移/时长小于该值视为点击而非拖拽 */
const CLICK_MAX_MOVE = 6
const CLICK_MAX_MS = 350
/** 多久无交互开始打瞌睡（ms） */
const SLEEP_AFTER_MS = 90_000
/** 碎碎念间隔范围（ms） */
const CHATTER_MIN_MS = 25_000
const CHATTER_MAX_MS = 45_000
/** 漫游速度 px/s */
const WALK_SPEED = 56

type PetMode = 'idle' | 'walk' | 'drag' | 'fly' | 'sleep'

const mode = ref<PetMode>('idle')
const bubbleText = ref('')
const bubbleVisible = ref(false)
const squashing = ref(false)
const petSize = ref(readPetSize())
/** 拖拽/飞行时的倾斜角（deg），由水平速度驱动，往运动方向压 */
const tiltDeg = ref(0)
/** 是否镜像（朝左） */
const facingLeft = ref(false)

const petEl = useTemplateRef<HTMLElement>('petEl')

// —— 物理：body 为普通对象，热循环里就地修改，不进响应式；每帧直接写 DOM transform ——
let body: PetBody = { pos: { x: 0, y: 0 }, vel: { x: 0, y: 0 }, grounded: true }
let world: PetWorld = { width: 0, height: 0, size: petSize.value, floorPadding: 20 }
let cancelFrame: (() => void) | null = null
let chatterTimer: ReturnType<typeof setTimeout> | null = null
let sleepCheckTimer: ReturnType<typeof setTimeout> | null = null
let bubbleTimer: ReturnType<typeof setTimeout> | null = null
let squashTimer: ReturnType<typeof setTimeout> | null = null
let lastInteractionAt = 0
let dragStart: { x: number; y: number; t: number } | null = null
let dragSamples: DragSample[] = []
let grabOffset = { x: 0, y: 0 }
let walkTargetX = 0
let reducedMotion = false
let reducedMotionMql: MediaQueryList | null = null

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** 帧调度：rAF 优先；无 rAF 环境（测试/jsdom 老版本）回退 16ms 定时器 */
function scheduleFrame(tick: () => void): void {
  cancelFrameNow()
  if (typeof window.requestAnimationFrame === 'function') {
    const h = window.requestAnimationFrame(() => tick())
    cancelFrame = () => window.cancelAnimationFrame(h)
  } else {
    const h = setTimeout(() => tick(), 16)
    cancelFrame = () => clearTimeout(h)
  }
}

function cancelFrameNow(): void {
  cancelFrame?.()
  cancelFrame = null
}

function applyTransform(): void {
  const el = petEl.value
  if (!el) return
  el.style.transform = `translate3d(${body.pos.x - world.size / 2}px, ${body.pos.y - world.size / 2}px, 0)`
}

function syncWorld(): void {
  world = { width: window.innerWidth, height: window.innerHeight, size: petSize.value, floorPadding: 20 }
}

// ---------- 气泡 / 状态 ----------

function showBubble(text: string, durationMs = 2600): void {
  bubbleText.value = text
  bubbleVisible.value = true
  if (bubbleTimer) clearTimeout(bubbleTimer)
  bubbleTimer = setTimeout(() => {
    bubbleVisible.value = false
  }, durationMs)
}

function poke(): void {
  squashing.value = true
  if (squashTimer) clearTimeout(squashTimer)
  squashTimer = setTimeout(() => {
    squashing.value = false
  }, 500)
  showBubble(pickLine(POKE_LINES))
}

function clampTilt(vx: number): number {
  return Math.max(-16, Math.min(16, vx * 0.012))
}

// ---------- 漫游 / 打盹 / 碎碎念 ----------

function scheduleMicroAction(): void {
  if (mode.value !== 'idle') return
  const delay = reducedMotion ? Number.POSITIVE_INFINITY : 6000 + Math.random() * 8000
  if (!Number.isFinite(delay)) return
  setTimeout(() => {
    if (mode.value !== 'idle') return
    if (Date.now() - lastInteractionAt >= SLEEP_AFTER_MS) return // 睡眠检查交给 sleepLoop
    if (Math.random() < 0.55) {
      startWalk(nextWanderTarget(world, body.pos.x))
    } else {
      facingLeft.value = !facingLeft.value // 原地张望一下
      scheduleMicroAction()
    }
  }, delay)
}

function startWalk(targetX: number): void {
  walkTargetX = targetX
  facingLeft.value = targetX < body.pos.x
  mode.value = 'walk'
  let last = performance.now()
  const tick = (): void => {
    if (mode.value !== 'walk') return
    const now = performance.now()
    const dt = Math.min((now - last) / 1000, DEFAULT_TUNING.maxDt)
    last = now
    const dir = Math.sign(walkTargetX - body.pos.x)
    body.vel.x = dir * WALK_SPEED
    body.vel.y = 0
    stepPet(body, world, DEFAULT_TUNING, dt)
    applyTransform()
    if (dir === 0 || Math.abs(walkTargetX - body.pos.x) <= 3) {
      body.vel.x = 0
      mode.value = 'idle'
      persistPosition()
      scheduleMicroAction()
      return
    }
    scheduleFrame(tick)
  }
  scheduleFrame(tick)
}

function startFlyLoop(): void {
  let last = performance.now()
  const tick = (): void => {
    if (mode.value !== 'fly') return
    const now = performance.now()
    const dt = Math.min((now - last) / 1000, DEFAULT_TUNING.maxDt)
    last = now
    stepPet(body, world, DEFAULT_TUNING, dt)
    tiltDeg.value = clampTilt(body.vel.x)
    if (body.vel.x < -10) facingLeft.value = true
    else if (body.vel.x > 10) facingLeft.value = false
    applyTransform()
    if (body.grounded && body.vel.x === 0 && body.vel.y === 0) {
      tiltDeg.value = 0
      mode.value = 'idle'
      persistPosition()
      scheduleMicroAction()
      return
    }
    scheduleFrame(tick)
  }
  scheduleFrame(tick)
}

function chatterLoop(): void {
  chatterTimer = setTimeout(() => {
    if (!document.hidden && (mode.value === 'idle' || mode.value === 'sleep')) {
      showBubble(pickLine(mode.value === 'sleep' ? SLEEP_LINES : IDLE_LINES), 3000)
    }
    chatterLoop()
  }, CHATTER_MIN_MS + Math.random() * (CHATTER_MAX_MS - CHATTER_MIN_MS))
}

function sleepLoop(): void {
  sleepCheckTimer = setTimeout(() => {
    if (mode.value === 'idle' && Date.now() - lastInteractionAt >= SLEEP_AFTER_MS) {
      mode.value = 'sleep' // 打瞌睡：闭眼 + Zzz，交互即醒
    }
    sleepLoop()
  }, 10_000)
}

// ---------- 交互（指针 / 键盘） ----------

function onPointerDown(e: PointerEvent): void {
  lastInteractionAt = Date.now()
  cancelFrameNow()
  const wasAsleep = mode.value === 'sleep'
  mode.value = 'drag'
  if (wasAsleep) showBubble(pickLine(WAKE_LINES))
  dragStart = { x: e.clientX, y: e.clientY, t: e.timeStamp }
  dragSamples = [{ t: e.timeStamp, x: e.clientX, y: e.clientY }]
  grabOffset = { x: e.clientX - body.pos.x, y: e.clientY - body.pos.y }
  body.vel.x = 0
  body.vel.y = 0
  petEl.value?.setPointerCapture?.(e.pointerId)
}

function onPointerMove(e: PointerEvent): void {
  if (mode.value !== 'drag' || !dragStart) return
  lastInteractionAt = Date.now()
  body.pos.x = e.clientX - grabOffset.x
  body.pos.y = e.clientY - grabOffset.y
  clampIntoWorld(body, world)
  dragSamples.push({ t: e.timeStamp, x: e.clientX, y: e.clientY })
  if (dragSamples.length > 8) dragSamples.shift()
  const v = estimateThrowVelocity(dragSamples, e.timeStamp)
  tiltDeg.value = clampTilt(v.x)
  if (v.x < -8) facingLeft.value = true
  else if (v.x > 8) facingLeft.value = false
  applyTransform()
}

function onPointerUp(e: PointerEvent): void {
  if (mode.value !== 'drag' || !dragStart) return
  const moved = Math.hypot(e.clientX - dragStart.x, e.clientY - dragStart.y)
  const heldMs = e.timeStamp - dragStart.t
  dragStart = null
  lastInteractionAt = Date.now()
  tiltDeg.value = 0

  // 轻点：原地 Q 弹回应
  if (moved < CLICK_MAX_MOVE && heldMs < CLICK_MAX_MS) {
    mode.value = 'idle'
    body.vel.x = 0
    body.vel.y = 0
    poke()
    scheduleMicroAction()
    return
  }

  // 甩出：用最近采样估算初速度，进入抛体
  const v = estimateThrowVelocity(dragSamples, e.timeStamp)
  dragSamples = []
  body.vel.x = v.x
  body.vel.y = v.y
  if (Math.hypot(v.x, v.y) > 160) showBubble(pickLine(THROW_LINES))
  else if (heldMs > 600) showBubble(pickLine(LIFT_LINES))

  if (reducedMotion) {
    // 无动画偏好：不做抛物线，直接放回地板
    body.vel.x = 0
    body.vel.y = 0
    body.pos.y = worldBounds(world).maxY
    mode.value = 'idle'
    applyTransform()
    persistPosition()
    scheduleMicroAction()
    return
  }
  mode.value = 'fly'
  startFlyLoop()
}

function onPointerCancel(): void {
  if (mode.value !== 'drag') return
  dragStart = null
  dragSamples = []
  tiltDeg.value = 0
  mode.value = 'idle'
  body.vel.x = 0
  body.vel.y = 0
  body.pos.y = worldBounds(world).maxY
  applyTransform()
  persistPosition()
  scheduleMicroAction()
}

function onPokeKey(): void {
  lastInteractionAt = Date.now()
  if (mode.value === 'sleep') {
    mode.value = 'idle'
    scheduleMicroAction()
  }
  poke()
}

// ---------- 持久化 / 生命周期 ----------

interface PetPersisted {
  x: number
  y: number
}

function persistPosition(): void {
  try {
    localStorage.setItem(
      PET_STORAGE_KEY,
      JSON.stringify({ x: Math.round(body.pos.x), y: Math.round(body.pos.y) } satisfies PetPersisted),
    )
  } catch {
    /* 隐私模式等场景忽略 */
  }
}

function restorePosition(): void {
  let saved: PetPersisted | null = null
  try {
    const raw = localStorage.getItem(PET_STORAGE_KEY)
    if (raw) saved = JSON.parse(raw) as PetPersisted
  } catch {
    saved = null
  }
  syncWorld()
  body.pos.x = typeof saved?.x === 'number' ? saved.x : world.width * 0.82
  body.pos.y = typeof saved?.y === 'number' ? saved.y : worldBounds(world).maxY
  clampIntoWorld(body, world)
  applyTransform()
}

function onResize(): void {
  petSize.value = readPetSize()
  syncWorld()
  clampIntoWorld(body, world)
  if (mode.value === 'idle' || mode.value === 'sleep') applyTransform()
}

function onVisibility(): void {
  if (document.hidden) persistPosition()
}

function onReducedMotionChange(): void {
  reducedMotion = prefersReducedMotion()
}

onMounted(() => {
  reducedMotion = prefersReducedMotion()
  reducedMotionMql =
    typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null
  reducedMotionMql?.addEventListener?.('change', onReducedMotionChange)
  window.addEventListener('resize', onResize, { passive: true })
  document.addEventListener('visibilitychange', onVisibility)
  restorePosition()
  lastInteractionAt = Date.now()
  chatterLoop()
  sleepLoop()
})

onBeforeUnmount(() => {
  cancelFrameNow()
  if (chatterTimer) clearTimeout(chatterTimer)
  if (sleepCheckTimer) clearTimeout(sleepCheckTimer)
  if (bubbleTimer) clearTimeout(bubbleTimer)
  if (squashTimer) clearTimeout(squashTimer)
  reducedMotionMql?.removeEventListener?.('change', onReducedMotionChange)
  window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibility)
  persistPosition()
})
</script>

<template>
  <div class="lb-pet-stage">
    <div
      ref="petEl"
      class="lb-pet"
      :class="[`is-${mode}`, { 'is-squash': squashing, 'is-flipped': facingLeft }]"
      :style="{ '--pet-size': `${petSize}px`, '--pet-tilt': `${tiltDeg}deg` }"
      role="button"
      tabindex="0"
      aria-label="博客宠物，可拖拽把玩"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerCancel"
      @keydown.enter.prevent="onPokeKey"
      @keydown.space.prevent="onPokeKey"
    >
      <Transition name="lb-pet-pop">
        <div v-if="bubbleVisible" class="lb-pet-bubble" aria-hidden="true">{{ bubbleText }}</div>
      </Transition>
      <div v-if="mode === 'sleep'" class="lb-pet-zzz" aria-hidden="true">
        <span>z</span><span>z</span><span>z</span>
      </div>
      <svg class="lb-pet-svg" viewBox="0 0 100 84" role="presentation" focusable="false">
        <g class="lb-pet-flip">
          <g class="lb-pet-figure">
            <!-- 尾巴：行走时摆动 -->
            <path class="lb-pet-tail" d="M80 58 Q93 56 91 42" />
            <!-- 耳朵（压在身体后面） -->
            <ellipse class="lb-pet-ear" cx="30" cy="18" rx="11" ry="13" transform="rotate(-16 30 18)" />
            <ellipse class="lb-pet-ear" cx="70" cy="18" rx="11" ry="13" transform="rotate(16 70 18)" />
            <ellipse class="lb-pet-ear-inner" cx="30" cy="20" rx="5" ry="7" transform="rotate(-16 30 20)" />
            <ellipse class="lb-pet-ear-inner" cx="70" cy="20" rx="5" ry="7" transform="rotate(16 70 20)" />
            <!-- 身体：一团 -->
            <ellipse class="lb-pet-body" cx="50" cy="52" rx="34" ry="29" />
            <!-- 肚皮 -->
            <ellipse class="lb-pet-belly" cx="50" cy="61" rx="19" ry="15" />
            <!-- 爪子 -->
            <ellipse class="lb-pet-paw" cx="36" cy="77" rx="8" ry="4.5" />
            <ellipse class="lb-pet-paw" cx="64" cy="77" rx="8" ry="4.5" />
            <!-- 脸 -->
            <g class="lb-pet-face">
              <g class="lb-pet-eyes-open">
                <circle cx="38" cy="47" r="3.6" />
                <circle cx="62" cy="47" r="3.6" />
              </g>
              <g class="lb-pet-eyes-closed">
                <path d="M34 47 Q38 50.5 42 47" />
                <path d="M58 47 Q62 50.5 66 47" />
              </g>
              <ellipse class="lb-pet-blush" cx="29" cy="55" rx="4.2" ry="2.6" />
              <ellipse class="lb-pet-blush" cx="71" cy="55" rx="4.2" ry="2.6" />
              <path class="lb-pet-mouth" d="M45 55 Q47.5 58.5 50 55 Q52.5 58.5 55 55" />
            </g>
          </g>
        </g>
      </svg>
    </div>
  </div>
</template>

<style scoped>
/* ---------- 舞台：铺满视口但不挡交互 ---------- */
.lb-pet-stage {
  position: fixed;
  inset: 0;
  z-index: 1200; /* 低于 Element Plus 弹层（~2000），弹窗打开时宠物沉底 */
  pointer-events: none;
}

.lb-pet {
  position: absolute;
  left: 0;
  top: 0;
  width: var(--pet-size, 76px);
  height: calc(var(--pet-size, 76px) * 0.84);
  pointer-events: auto;
  touch-action: none; /* 触屏拖拽不滚动页面 */
  cursor: grab;
  user-select: none;
  -webkit-user-select: none;
  will-change: transform;
  outline: none;
}

.lb-pet:active,
.lb-pet.is-drag {
  cursor: grabbing;
}

.lb-pet:focus-visible {
  outline: 2px solid var(--ed-accent);
  outline-offset: 4px;
  border-radius: 50%;
}

/* ---------- SVG 分层：外层管倾斜，flip 组管镜像，figure 组管形变动画 ---------- */
.lb-pet-svg {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
  transform: rotate(var(--pet-tilt, 0deg));
  transform-origin: 50% 80%;
}

.lb-pet-flip {
  transform-box: fill-box;
  transform-origin: 50% 50%;
}

.is-flipped .lb-pet-flip {
  transform: scaleX(-1);
}

.lb-pet-figure {
  transform-box: fill-box;
  transform-origin: 50% 100%;
  animation: lb-pet-breathe 3.4s ease-in-out infinite;
}

/* ---------- 形态动画 ---------- */
@keyframes lb-pet-breathe {
  0%,
  100% {
    transform: scale(1, 1);
  }
  50% {
    transform: scale(1.02, 0.965);
  }
}

.is-walk .lb-pet-figure {
  animation: lb-pet-waddle 0.52s ease-in-out infinite;
}

@keyframes lb-pet-waddle {
  0%,
  100% {
    transform: translateY(0) rotate(-4deg);
  }
  50% {
    transform: translateY(-3px) rotate(4deg);
  }
}

.is-drag .lb-pet-figure {
  animation: none;
  transform: scale(0.96, 1.06); /* 被拎起：微微抻长 */
}

.is-squash .lb-pet-figure {
  animation: lb-pet-squash 0.48s cubic-bezier(0.3, 1.6, 0.5, 1);
}

@keyframes lb-pet-squash {
  0% {
    transform: scale(1, 1);
  }
  35% {
    transform: scale(1.18, 0.78);
  }
  70% {
    transform: scale(0.92, 1.08);
  }
  100% {
    transform: scale(1, 1);
  }
}

.is-sleep .lb-pet-figure {
  animation: none;
  transform: scale(1.04, 0.94); /* 睡着：摊平一点 */
}

/* ---------- 配色：跟随主题 ---------- */
.lb-pet-ear,
.lb-pet-body {
  fill: var(--ed-accent);
}

.lb-pet-ear-inner {
  fill: color-mix(in srgb, var(--ed-accent) 55%, var(--ed-paper));
}

.lb-pet-belly,
.lb-pet-paw {
  fill: color-mix(in srgb, var(--ed-accent) 26%, var(--ed-paper));
}

.lb-pet-tail {
  fill: none;
  stroke: var(--ed-accent);
  stroke-width: 7;
  stroke-linecap: round;
}

.lb-pet-eyes-open circle {
  fill: var(--ed-ink);
}

/* 眨眼：睁眼组周期性压扁 */
.lb-pet-eyes-open {
  transform-box: fill-box;
  transform-origin: 50% 50%;
  animation: lb-pet-blink 4.6s infinite;
}

@keyframes lb-pet-blink {
  0%,
  92%,
  100% {
    transform: scaleY(1);
  }
  95% {
    transform: scaleY(0.08);
  }
}

.lb-pet-eyes-closed {
  display: none;
}

.lb-pet-eyes-closed path {
  fill: none;
  stroke: var(--ed-ink);
  stroke-width: 2.2;
  stroke-linecap: round;
}

.is-sleep .lb-pet-eyes-closed {
  display: block;
}

.is-sleep .lb-pet-eyes-open {
  display: none;
}

.lb-pet-mouth {
  fill: none;
  stroke: var(--ed-ink);
  stroke-width: 2;
  stroke-linecap: round;
}

.lb-pet-blush {
  fill: var(--ed-accent-ink);
  opacity: 0.25;
}

/* ---------- 气泡 ---------- */
.lb-pet-bubble {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 50%;
  transform: translateX(-50%);
  max-width: 180px;
  padding: 7px 12px;
  background: var(--ed-card);
  border: 1px solid var(--ed-rule-soft);
  border-radius: 12px 12px 12px 3px;
  color: var(--ed-ink);
  font-family: var(--ed-font-ui);
  font-size: 13px;
  line-height: 1.5;
  text-align: center;
  box-shadow: var(--ed-shadow-sm);
  pointer-events: none;
  white-space: normal;
}

.lb-pet-pop-enter-active,
.lb-pet-pop-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.lb-pet-pop-enter-from,
.lb-pet-pop-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(6px) scale(0.96);
}

/* ---------- Zzz ---------- */
.lb-pet-zzz {
  position: absolute;
  top: -6px;
  right: -10px;
  font-family: var(--ed-font-display);
  font-style: italic;
  color: var(--ed-ink-soft);
  pointer-events: none;
}

.lb-pet-zzz span {
  display: inline-block;
  animation: lb-pet-zzz 2.4s ease-in-out infinite;
}

.lb-pet-zzz span:nth-child(2) {
  animation-delay: 0.3s;
  font-size: 0.9em;
}

.lb-pet-zzz span:nth-child(3) {
  animation-delay: 0.6s;
  font-size: 0.75em;
}

@keyframes lb-pet-zzz {
  0% {
    opacity: 0;
    transform: translateY(2px);
  }
  30% {
    opacity: 0.9;
  }
  100% {
    opacity: 0;
    transform: translateY(-8px);
  }
}

/* ---------- 无动画偏好：只保留静态 ---------- */
@media (prefers-reduced-motion: reduce) {
  .lb-pet-figure,
  .lb-pet-eyes-open,
  .lb-pet-zzz span {
    animation: none !important;
  }
}
</style>
