/**
 * heroScene.ts — 首页 Hero 三维粒子层的纯逻辑（供 HeroScene.vue 使用）。
 *
 * 设计约束（方案 A 亮卡片 · editorial 调性）：
 *  - 装饰性而非炫技：低透明度主题色「墨点」缓慢上浮，像宣纸上悬浮的纸屑；
 *  - 桌面专属：窄屏 / prefers-reduced-motion / WebGL 缺失时整体不启用；
 *  - 颜色全部来自 CSS 变量（--main-color / --ed-paper），主题切换即时跟随。
 *
 * 全部为纯函数，方便单测（不 import three）。
 */

export interface HeroSceneEligibility {
  /** 是否启用三维层 */
  enabled: boolean
  /** 粒子数量（0 = 不启用） */
  particles: number
  /** 不启用的原因（测试与日志用） */
  reason: string
}

/** 粒子数量上限（桌面基准） */
export const HERO_PARTICLES_DESKTOP = 240
/** 启用三层的最小视口宽度 */
export const HERO_MIN_VIEWPORT = 768
/** 单个墨点的不透明度区间 */
export const PARTICLE_OPACITY_MIN = 0.22
export const PARTICLE_OPACITY_MAX = 0.42

/** 是否应启用 Hero 三维粒子层（宽度 / reduced-motion；WebGL 支持由调用方另行探测）。 */
export function isHeroSceneEligible(viewportWidth: number, reducedMotion: boolean): HeroSceneEligibility {
  if (reducedMotion) {
    return { enabled: false, particles: 0, reason: 'reduced-motion' }
  }
  if (viewportWidth < HERO_MIN_VIEWPORT) {
    return { enabled: false, particles: 0, reason: 'viewport-too-narrow' }
  }
  const particles = Math.min(HERO_PARTICLES_DESKTOP, Math.round(viewportWidth / 6))
  return { enabled: true, particles, reason: 'ok' }
}

/**
 * 粒子均匀但带分层的初始分布：
 *  - x/y 在 [0,1]×[0,1] 归一化平面内（渲染层映射到实际视口）；
 *  - z 为 0..1 的伪深度（影响尺寸与视差幅度）；
 *  - 随机源可注入以便测试断言分布形状。
 */
export interface ParticleSeed {
  x: number
  y: number
  z: number
  /** 上浮速度系数（0.5..1.5，越远越慢） */
  drift: number
  /** 初始不透明度 */
  opacity: number
}

export function particleSeeds(count: number, rng: () => number = Math.random): ParticleSeed[] {
  const seeds: ParticleSeed[] = []
  for (let i = 0; i < count; i++) {
    const z = rng()
    seeds.push({
      x: rng(),
      y: rng(),
      z,
      drift: 0.5 + z * 1.0,
      opacity: PARTICLE_OPACITY_MIN + (PARTICLE_OPACITY_MAX - PARTICLE_OPACITY_MIN) * (1 - z * 0.6),
    })
  }
  return seeds
}

/**
 * 每帧位置推进（归一化平面，y 向上为减小——屏幕坐标 y 向下）：
 * 粒子缓慢上浮（y -= drift * speed * dt），越界后回到底部形成循环；
 * x 加正弦横摆（幅度随 z 递减），让画面「活」但不乱。
 * 就地修改传入数组，返回是否发生了越界回卷（供测试断言）。
 */
export function advanceParticles(
  seeds: ParticleSeed[],
  dtSeconds: number,
  speed = 0.012,
  rng: () => number = Math.random,
): boolean {
  let wrapped = false
  for (const p of seeds) {
    p.y -= p.drift * speed * dtSeconds
    if (p.y < -0.05) {
      p.y = 1.05
      p.x = rng()
      wrapped = true
    }
  }
  return wrapped
}

/**
 * 从 CSS 颜色字符串解析 [r, g, b]（0..1）。支持 #rgb/#rrggbb 与 rgb()/rgba()。
 * 无法解析时返回 null（调用方回退到中性色）。
 */
export function parseCssColor(value: string | null | undefined): [number, number, number] | null {
  if (!value) return null
  const v = value.trim()
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(v)
  if (hex) {
    const body = hex[1]
    if (body.length === 3) {
      return [
        parseInt(body[0] + body[0], 16) / 255,
        parseInt(body[1] + body[1], 16) / 255,
        parseInt(body[2] + body[2], 16) / 255,
      ]
    }
    return [
      parseInt(body.slice(0, 2), 16) / 255,
      parseInt(body.slice(2, 4), 16) / 255,
      parseInt(body.slice(4, 6), 16) / 255,
    ]
  }
  const rgb = /^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})/.exec(v)
  if (rgb) {
    return [Number(rgb[1]) / 255, Number(rgb[2]) / 255, Number(rgb[3]) / 255]
  }
  return null
}

/** 鼠标视差的目标插值：把 [-1,1] 的指针位置收敛到 ±strength 的平滑目标。 */
export function parallaxTarget(pointer: number, strength: number): number {
  return Math.max(-1, Math.min(1, pointer)) * strength
}
