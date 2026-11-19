<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useTemplateRef } from 'vue'
import {
  advanceParticles,
  isHeroSceneEligible,
  parallaxTarget,
  parseCssColor,
  particleSeeds,
  type ParticleSeed,
} from '@/utils/heroScene'

/**
 * HeroScene — 三维墨点背景层（three.js，装饰性；首页 Hero 与后台总览共用）。
 *
 * 视觉：主题色「墨点」粒子缓慢上浮，远层小而淡、近层大而实，
 *       配合鼠标视差形成轻微纵深感——呼应 editorial 的纸面质感，刻意克制。
 * 工程：three.js 动态 import（独立 chunk，不挤 first-screen 预算）；
 *       窄屏 / prefers-reduced-motion / WebGL 不可用 / 动态加载失败 → 整层不渲染；
 *       目标滚出视口或页面隐藏时暂停渲染；卸载时全量 dispose。
 * 颜色：实时读取 --main-color（MutationObserver 监听主题切换）与 --ed-paper（暗色）。
 * 不挡交互：canvas pointer-events:none，视差监听在 window 上。
 *
 * props：
 *  - particleCount  覆盖粒子数（0 = 视口自适应，桌面 ~240）
 *  - opacityScale   整体不透明度缩放（0..1；后台密一些的场景给 0.5~0.7）
 *  - interactive    是否启用鼠标视差（后台页面卡片多，可关）
 */
const props = withDefaults(
  defineProps<{
    particleCount?: number
    opacityScale?: number
    interactive?: boolean
  }>(),
  { particleCount: 0, opacityScale: 1, interactive: true },
)

const containerEl = useTemplateRef<HTMLElement>('containerEl')
const failed = ref(false)

let cleanupFns: Array<() => void> = []

function readCssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

/** 生成圆形墨点 sprite 贴图（避免方点），中心实、边缘羽化。 */
function makeDotTexture(THREE: typeof import('three')): import('three').Texture {
  const size = 64
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = canvas.getContext('2d')!
  const gradient = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
  gradient.addColorStop(0, 'rgba(255,255,255,1)')
  gradient.addColorStop(0.55, 'rgba(255,255,255,0.85)')
  gradient.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, size, size)
  const texture = new THREE.CanvasTexture(canvas)
  texture.needsUpdate = true
  return texture
}

async function boot(): Promise<void> {
  const container = containerEl.value
  if (!container) return

  const width = container.clientWidth
  const height = container.clientHeight
  const eligibility = isHeroSceneEligible(width, prefersReducedMotion())
  if (!eligibility.enabled) return
  const particleCount = props.particleCount > 0
    ? Math.min(props.particleCount, eligibility.particles)
    : eligibility.particles

  let THREE: typeof import('three')
  try {
    THREE = await import('three')
  } catch {
    failed.value = true // 动态加载失败：静默降级为无背景
    return
  }

  let renderer: import('three').WebGLRenderer
  try {
    renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true })
  } catch {
    failed.value = true // WebGL 不可用（jsdom / 旧设备）：静默降级
    return
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.setSize(width, height)
  renderer.domElement.classList.add('lb-hero-scene-canvas')
  container.appendChild(renderer.domElement)

  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(60, width / height, 0.1, 20)
  camera.position.z = 3

  // 粒子分布与几何
  const seeds: ParticleSeed[] = particleSeeds(particleCount)
  const positions = new Float32Array(seeds.length * 3)
  seeds.forEach((s, i) => {
    // 归一化平面映射到相机视野（z 越大越近、越大越实）
    positions[i * 3] = (s.x - 0.5) * 6 * (0.6 + s.z * 0.7)
    positions[i * 3 + 1] = (0.5 - s.y) * 3.6 * (0.6 + s.z * 0.7)
    positions[i * 3 + 2] = -0.6 + s.z * 1.2
  })
  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))

  // 圆点 sprite + 主题色材质
  const texture = makeDotTexture(THREE)
  const material = new THREE.PointsMaterial({
    size: 0.045,
    map: texture,
    transparent: true,
    opacity: Math.min(1, Math.max(0, props.opacityScale)),
    depthWrite: false,
    sizeAttenuation: true,
  })
  const points = new THREE.Points(geometry, material)
  scene.add(points)

  const applyThemeColor = (): void => {
    // 主色为主，混一点纸面色避免高饱和刺眼：直接由主色解析，透明度已由贴图+材质控制
    const color = parseCssColor(readCssVar('--main-color')) ?? [0.88, 0.34, 0.24]
    material.color.setRGB(color[0], color[1], color[2])
  }
  applyThemeColor()

  // 主题切换跟随：ThemeSwitcher 写 documentElement 的内联 style（--main-color）
  const themeObserver = new MutationObserver(() => applyThemeColor())
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['style'] })

  // 暗色切换：html.dark class 变化时纸面色变化，粒子透明度随之微调
  const darkObserver = new MutationObserver(() => applyThemeColor())
  darkObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })

  // 视差：指针 [-1,1] → 云层轻偏移（lerp 平滑）
  let pointerX = 0
  let pointerY = 0
  let targetX = 0
  let targetY = 0
  const onPointerMove = (e: MouseEvent): void => {
    if (!props.interactive) return
    pointerX = (e.clientX / window.innerWidth) * 2 - 1
    pointerY = (e.clientY / window.innerHeight) * 2 - 1
  }
  if (props.interactive) {
    window.addEventListener('mousemove', onPointerMove, { passive: true })
  }

  // 滚出视口 / 页面隐藏时暂停渲染
  let heroVisible = true
  let pageVisible = !document.hidden
  let visibilityIo: IntersectionObserver | null = null
  if (typeof IntersectionObserver === 'function') {
    visibilityIo = new IntersectionObserver((entries) => {
      heroVisible = entries.some((entry) => entry.isIntersecting)
    }, { threshold: 0.02 })
    visibilityIo.observe(container)
  } else {
    heroVisible = false // 无 IO（理论不可达）：保守不渲染
  }
  const onVisibilityChange = (): void => {
    pageVisible = !document.hidden
  }
  document.addEventListener('visibilitychange', onVisibilityChange)

  // 渲染循环
  let raf = 0
  let last = performance.now()
  const reducedMotionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  const tick = (): void => {
    const now = performance.now()
    const dt = Math.min((now - last) / 1000, 0.05)
    last = now
    const driftEnabled = heroVisible && pageVisible && !reducedMotionQuery?.matches
    if (driftEnabled) {
      advanceParticles(seeds, dt)
      seeds.forEach((s, i) => {
        positions[i * 3] = (s.x - 0.5) * 6 * (0.6 + s.z * 0.7)
        positions[i * 3 + 1] = (0.5 - s.y) * 3.6 * (0.6 + s.z * 0.7)
        positions[i * 3 + 2] = -0.6 + s.z * 1.2
      })
      geometry.attributes.position.needsUpdate = true
      // 视差 lerp
      if (props.interactive) {
        targetX = parallaxTarget(pointerX, 0.25)
        targetY = parallaxTarget(pointerY, 0.16)
        camera.position.x += (targetX - camera.position.x) * 0.04
        camera.position.y += (-targetY - camera.position.y) * 0.04
        camera.lookAt(scene.position)
      }
      renderer.render(scene, camera)
    }
    raf = window.requestAnimationFrame(tick)
  }
  raf = window.requestAnimationFrame(tick)

  const onResize = (): void => {
    if (!container.clientWidth || !container.clientHeight) return
    camera.aspect = container.clientWidth / container.clientHeight
    camera.updateProjectionMatrix()
    renderer.setSize(container.clientWidth, container.clientHeight)
  }
  window.addEventListener('resize', onResize, { passive: true })

  cleanupFns.push(
    () => window.cancelAnimationFrame(raf),
    () => window.removeEventListener('mousemove', onPointerMove),
    () => window.removeEventListener('resize', onResize),
    () => document.removeEventListener('visibilitychange', onVisibilityChange),
    () => themeObserver.disconnect(),
    () => darkObserver.disconnect(),
    () => visibilityIo?.disconnect(),
    () => {
      geometry.dispose()
      material.dispose()
      texture.dispose()
      renderer.dispose()
      renderer.domElement.remove()
    },
  )
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function teardown(): void {
  for (const fn of cleanupFns) fn()
  cleanupFns = []
}

onMounted(() => {
  void boot()
})

onBeforeUnmount(() => {
  teardown()
})
</script>

<template>
  <div ref="containerEl" class="lb-hero-scene" aria-hidden="true"></div>
</template>

<style scoped>
/* 背景层：铺满 Hero，不挡任何内容与交互 */
.lb-hero-scene {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}

.lb-hero-scene-canvas {
  display: block;
  width: 100%;
  height: 100%;
}

/* 主题/暗色切换时的过渡 */
.lb-hero-scene,
.lb-hero-scene-canvas {
  transition: opacity 400ms ease;
}
</style>
