import { describe, expect, it } from 'vitest'
import {
  clampIntoWorld,
  DEFAULT_TUNING,
  estimateThrowVelocity,
  nextWanderTarget,
  stepPet,
  worldBounds,
  type PetBody,
  type PetWorld,
} from '@/utils/petPhysics'

/**
 * 宠物物理纯函数（utils/petPhysics.ts）：
 * 边界、重力、反弹、静止判定、甩出速度估算、漫游目标。
 * 组件层交互在 BlogPet.test.ts 驱动，这里只验证数学正确性。
 */

const WORLD: PetWorld = { width: 1000, height: 800, size: 76, floorPadding: 24 }
const BOUNDS = worldBounds(WORLD)
const FLOOR_Y = BOUNDS.maxY // 800 - 24 - 38 = 738

function makeBody(x: number, y: number, vx = 0, vy = 0): PetBody {
  return { pos: { x, y }, vel: { x: vx, y: vy }, grounded: false }
}

describe('worldBounds / clampIntoWorld', () => {
  it('地板 = 视口高 − floorPadding − 半边长', () => {
    expect(BOUNDS.maxY).toBe(738)
    expect(BOUNDS.minX).toBe(38)
    expect(BOUNDS.maxX).toBe(962)
  })

  it('把跑出视口的中心点钳回世界内', () => {
    const b = makeBody(-50, -50)
    clampIntoWorld(b, WORLD)
    expect(b.pos.x).toBe(BOUNDS.minX)
    expect(b.pos.y).toBe(BOUNDS.minY)
  })

  it('极小视口下不会出现负区间（min ≤ max）', () => {
    const tiny: PetWorld = { width: 40, height: 40, size: 76, floorPadding: 24 }
    const tb = worldBounds(tiny)
    expect(tb.minX).toBeLessThanOrEqual(tb.maxX)
    expect(tb.minY).toBeLessThanOrEqual(tb.maxY)
  })
})

describe('stepPet', () => {
  it('自由落体：速度按 g·dt 增长（含空气阻尼），位置下移', () => {
    const b = makeBody(500, 300)
    stepPet(b, WORLD, DEFAULT_TUNING, 0.016)
    const expected = DEFAULT_TUNING.gravity * 0.016 * Math.exp(-DEFAULT_TUNING.airDragK * 0.016)
    expect(b.vel.y).toBeCloseTo(expected, 5)
    expect(b.pos.y).toBeGreaterThan(300)
    expect(b.grounded).toBe(false)
  })

  it('落地反弹：钳回地板、vy 反向并乘恢复系数', () => {
    const b = makeBody(500, FLOOR_Y - 1, 0, 900)
    stepPet(b, WORLD, DEFAULT_TUNING, 0.016)
    expect(b.pos.y).toBe(FLOOR_Y)
    expect(b.vel.y).toBeLessThan(0)
    expect(b.vel.y).toBeGreaterThan(-900) // 反弹有能量损耗
  })

  it('在地板上反复推进：能量耗尽后静止贴地', () => {
    const b = makeBody(500, FLOOR_Y, 0, 0)
    b.grounded = true
    for (let i = 0; i < 600; i++) stepPet(b, WORLD, DEFAULT_TUNING, 0.016)
    expect(b.grounded).toBe(true)
    expect(b.pos.y).toBe(FLOOR_Y)
    expect(b.vel.x).toBe(0)
    expect(b.vel.y).toBe(0)
  })

  it('侧墙反弹：x 速度反向', () => {
    const b = makeBody(BOUNDS.maxX, 300, 500, 0)
    stepPet(b, WORLD, DEFAULT_TUNING, 0.016)
    expect(b.vel.x).toBeLessThan(0)
  })

  it('大 dt 被钳制：切回标签页后不会一步飞出世界', () => {
    const b = makeBody(500, 300, 0, 0)
    stepPet(b, WORLD, DEFAULT_TUNING, 5)
    expect(b.pos.y).toBeLessThanOrEqual(FLOOR_Y)
    expect(b.pos.x).toBeGreaterThanOrEqual(BOUNDS.minX)
    expect(b.pos.x).toBeLessThanOrEqual(BOUNDS.maxX)
  })

  it('贴地滑行受地面摩擦：最终停下来', () => {
    const b = makeBody(500, FLOOR_Y, 200, 0)
    b.grounded = true
    for (let i = 0; i < 300; i++) stepPet(b, WORLD, DEFAULT_TUNING, 0.016)
    expect(b.vel.x).toBe(0)
    expect(b.pos.x).toBeGreaterThan(400) // 摩擦前滑出过一段
    expect(b.pos.x).toBeLessThan(700)
  })
})

describe('estimateThrowVelocity', () => {
  it('按时间窗口首尾差估算 px/s', () => {
    const samples = [
      { t: 1000, x: 0, y: 0 },
      { t: 1060, x: 90, y: 30 },
    ]
    const v = estimateThrowVelocity(samples, 1060)
    expect(v.x).toBeCloseTo(1500, 0) // 90px / 0.06s
    expect(v.y).toBeCloseTo(500, 0)
  })

  it('窗口外的过期样本被忽略', () => {
    const samples = [
      { t: 0, x: 0, y: 0 },
      { t: 500, x: 500, y: 0 },
    ]
    const v = estimateThrowVelocity(samples, 500, 90)
    expect(v).toEqual({ x: 0, y: 0 })
  })

  it('样本不足或时间跨度为 0 返回 0 速度', () => {
    expect(estimateThrowVelocity([{ t: 0, x: 0, y: 0 }], 1000)).toEqual({ x: 0, y: 0 })
    expect(
      estimateThrowVelocity(
        [
          { t: 100, x: 10, y: 10 },
          { t: 100, x: 20, y: 20 },
        ],
        100,
      ),
    ).toEqual({ x: 0, y: 0 })
  })
})

describe('nextWanderTarget', () => {
  it('目标留在世界内，且与当前位置有距离', () => {
    for (let i = 0; i < 50; i++) {
      const t = nextWanderTarget(WORLD, 500)
      expect(t).toBeGreaterThanOrEqual(BOUNDS.minX)
      expect(t).toBeLessThanOrEqual(BOUNDS.maxX)
      expect(Math.abs(t - 500)).toBeGreaterThan(0)
    }
  })

  it('注入 rng 时结果确定', () => {
    let step = 0
    const seq = () => [0.9, 0.1][step++ % 2]!
    // 首个候选 = minX + 0.9·span，与 500 相距足够远，直接采用
    const span = BOUNDS.maxX - BOUNDS.minX
    expect(nextWanderTarget(WORLD, 500, seq)).toBeCloseTo(BOUNDS.minX + 0.9 * span, 5)
  })
})
