import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import HeroScene from '@/components/HeroScene.vue'
import {
  advanceParticles,
  isHeroSceneEligible,
  parseCssColor,
  particleSeeds,
  parallaxTarget,
} from '@/utils/heroScene'

/**
 * HeroScene（three.js 首页墨点层）：
 *  - 纯函数：eligibility / 粒子分布 / 推进循环 / 颜色解析 / 视差目标；
 *  - 组件：reduced-motion 或 jsdom（无 WebGL）下静默降级——不渲染 canvas、不抛未处理异常。
 */

describe('isHeroSceneEligible', () => {
  it('桌面视口 + 允许动画 → 启用且粒子数封顶 240', () => {
    const result = isHeroSceneEligible(1440, false)
    expect(result.enabled).toBe(true)
    expect(result.particles).toBe(240)
  })

  it('窄视口不启用', () => {
    const result = isHeroSceneEligible(600, false)
    expect(result.enabled).toBe(false)
    expect(result.reason).toBe('viewport-too-narrow')
  })

  it('reduced-motion 不启用', () => {
    const result = isHeroSceneEligible(1440, true)
    expect(result.enabled).toBe(false)
    expect(result.reason).toBe('reduced-motion')
  })

  it('粒子数随视口宽度收敛（不超过 240）', () => {
    expect(isHeroSceneEligible(768, false).particles).toBeLessThanOrEqual(240)
    expect(isHeroSceneEligible(3000, false).particles).toBe(240)
  })
})

describe('particleSeeds / advanceParticles', () => {
  it('种子分布在 [0,1] 且不透明度随深度递减', () => {
    const seeds = particleSeeds(50, () => 0.5)
    for (const s of seeds) {
      expect(s.x).toBeGreaterThanOrEqual(0)
      expect(s.x).toBeLessThanOrEqual(1)
      expect(s.z).toBe(0.5)
      // z=0.5 → opacity = min + (max-min)*(1-0.3)
      expect(s.opacity).toBeGreaterThan(0.2)
    }
  })

  it('推进后 y 上浮、越界回卷顶部', () => {
    const seeds = particleSeeds(4, () => 0.5)
    const before = seeds[0].y
    advanceParticles(seeds, 1, 0.012, () => 0.5)
    // drift(0.5+0.5)=1.0 * speed 0.012 * 1s = 0.012
    expect(seeds[0].y).toBeCloseTo(before - 0.012, 6)
  })

  it('越界回卷时重置 x（注入 rng 验证）', () => {
    const seeds = [{ x: 0.3, y: -0.1, z: 0.5, drift: 1, opacity: 0.3 }]
    const calls = 0
    const wrapped = advanceParticles(seeds, 1, 0.5, () => 0.77)
    expect(wrapped).toBe(true)
    expect(seeds[0].x).toBe(0.77)
    expect(seeds[0].y).toBe(1.05)
    expect(calls).toBe(0)
    void calls
  })
})

describe('parseCssColor', () => {
  it('解析 #rgb 与 #rrggbb', () => {
    expect(parseCssColor('#f00')).toEqual([1, 0, 0])
    expect(parseCssColor('#18a058')![0]).toBeCloseTo(0.094, 2)
    expect(parseCssColor('#18a058')![1]).toBeCloseTo(0.627, 2)
  })

  it('解析 rgb()/rgba()', () => {
    expect(parseCssColor('rgb(24, 160, 88)')![0]).toBeCloseTo(0.094, 2)
    expect(parseCssColor('rgba(0,0,0,0.5)')![2]).toBe(0)
  })

  it('无法解析返回 null', () => {
    expect(parseCssColor('oklch(0.5 0.1 20)')).toBeNull()
    expect(parseCssColor('')).toBeNull()
    expect(parseCssColor(null)).toBeNull()
  })
})

describe('parallaxTarget', () => {
  it('指针位置收敛到 ±strength', () => {
    expect(parallaxTarget(1, 0.25)).toBe(0.25)
    expect(parallaxTarget(-1, 0.25)).toBe(-0.25)
    expect(parallaxTarget(2, 0.25)).toBe(0.25)
  })
})

describe('HeroScene 组件降级', () => {
  function mockMatchMedia(matches: boolean): void {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: vi.fn().mockImplementation((query: string) => ({
        matches,
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    })
  }

  beforeEach(() => {
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => window.setTimeout(cb, 16))
    vi.stubGlobal('cancelAnimationFrame', (h: number) => window.clearTimeout(h))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('reduced-motion 下不渲染 canvas', async () => {
    mockMatchMedia(true)
    const wrapper = mount(HeroScene)
    await flushPromises()
    expect(wrapper.find('.lb-hero-scene').exists()).toBe(true)
    expect(wrapper.find('canvas').exists()).toBe(false)
    wrapper.unmount()
  })

  it('jsdom 无 WebGL → 动态加载/WebGL 失败静默降级，不渲染 canvas', async () => {
    mockMatchMedia(false)
    const wrapper = mount(HeroScene)
    await flushPromises()
    await new Promise((r) => setTimeout(r, 50))
    await flushPromises()
    expect(wrapper.find('.lb-hero-scene').exists()).toBe(true)
    // WebGLRenderer 在 jsdom 必然抛错 → 组件捕获 → 不插 canvas
    expect(wrapper.find('canvas').exists()).toBe(false)
    wrapper.unmount()
  })
})
