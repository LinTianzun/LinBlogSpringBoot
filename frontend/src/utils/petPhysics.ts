/**
 * petPhysics.ts — 网页宠物（BlogPet）的纯物理步进。
 * 只做数学，不碰 DOM/Vue，方便单测与调参：
 *  - stepPet：重力 + 反弹 + 摩擦 + 视口边界，一帧推进一次；
 *  - estimateThrowVelocity：用拖拽采样序列估算甩出初速度；
 *  - clampIntoWorld / nextWanderTarget：落位与漫游目标。
 * 坐标系：宠物包围盒中心 (x, y)，px；y 向下为正（与屏幕一致）。
 */

export interface Vec2 {
  x: number
  y: number
}

/** 宠物活动的世界（视口约束） */
export interface PetWorld {
  width: number
  height: number
  /** 宠物碰撞盒边长（px，正方形近似） */
  size: number
  /** 地板离视口底部的距离（给页脚留出可读空间） */
  floorPadding: number
}

export interface PetBody {
  pos: Vec2
  vel: Vec2
  /** 本步结束时是否贴地 */
  grounded: boolean
}

export interface PhysicsTuning {
  /** 重力加速度 px/s² */
  gravity: number
  /** 反弹恢复系数 0~1 */
  restitution: number
  /** 空气阻力系数（指数衰减 k，越大衰减越快） */
  airDragK: number
  /** 地面摩擦系数（指数衰减 k） */
  groundFrictionK: number
  /** 贴地且合成速度低于该值 → 静止，px/s */
  restSpeed: number
  /** 单帧最大时长钳制，防标签页切回后大 dt 一步飞出 */
  maxDt: number
  /** 速度上限 px/s */
  maxSpeed: number
}

export const DEFAULT_TUNING: PhysicsTuning = {
  gravity: 2400,
  restitution: 0.52,
  airDragK: 0.12,
  groundFrictionK: 7.5,
  restSpeed: 36,
  maxDt: 0.05,
  maxSpeed: 3600,
}

/** 指数衰减因子 e^(-k·dt)：帧长无关的阻尼写法 */
function damp(k: number, dt: number): number {
  return Math.exp(-k * dt)
}

function clamp(v: number, min: number, max: number): number {
  return v < min ? min : v > max ? max : v
}

/** 宠物中心点在世界内允许的范围 */
export function worldBounds(world: PetWorld): {
  minX: number
  maxX: number
  minY: number
  maxY: number
} {
  const half = world.size / 2
  return {
    minX: half,
    maxX: Math.max(half, world.width - half),
    minY: half,
    maxY: Math.max(half, world.height - world.floorPadding - half),
  }
}

/** 把宠物钳回世界内（初始落位 / 窗口 resize 后调用） */
export function clampIntoWorld(body: PetBody, world: PetWorld): void {
  const b = worldBounds(world)
  body.pos.x = clamp(body.pos.x, b.minX, b.maxX)
  body.pos.y = clamp(body.pos.y, b.minY, b.maxY)
}

/**
 * 推进一帧物理，就地修改并返回 body（热循环复用，零分配）。
 * 边界碰撞反射乘恢复系数；贴地且速度低于 restSpeed 时清零速度。
 */
export function stepPet(body: PetBody, world: PetWorld, tuning: PhysicsTuning, dtRaw: number): PetBody {
  const dt = clamp(dtRaw, 0, tuning.maxDt)
  const b = worldBounds(world)

  // 1) 力与阻尼
  body.vel.y += tuning.gravity * dt
  const air = damp(tuning.airDragK, dt)
  body.vel.x *= air
  body.vel.y *= air
  if (body.grounded) body.vel.x *= damp(tuning.groundFrictionK, dt)

  // 2) 限速 + 积分
  const speed = Math.hypot(body.vel.x, body.vel.y)
  if (speed > tuning.maxSpeed) {
    const k = tuning.maxSpeed / speed
    body.vel.x *= k
    body.vel.y *= k
  }
  body.pos.x += body.vel.x * dt
  body.pos.y += body.vel.y * dt

  // 3) 边界碰撞：位置钳制 + 速度反射
  body.grounded = false
  if (body.pos.y >= b.maxY) {
    body.pos.y = b.maxY
    if (body.vel.y > 0) body.vel.y = -body.vel.y * tuning.restitution
    if (Math.abs(body.vel.y) < tuning.restSpeed) body.vel.y = 0
    body.grounded = body.vel.y === 0
  } else if (body.pos.y <= b.minY) {
    body.pos.y = b.minY
    if (body.vel.y < 0) body.vel.y = -body.vel.y * tuning.restitution
  }
  if (body.pos.x <= b.minX) {
    body.pos.x = b.minX
    if (body.vel.x < 0) body.vel.x = -body.vel.x * tuning.restitution
  } else if (body.pos.x >= b.maxX) {
    body.pos.x = b.maxX
    if (body.vel.x > 0) body.vel.x = -body.vel.x * tuning.restitution
  }

  // 4) 静止判定：贴地且速度很小 → 停
  if (body.grounded && Math.hypot(body.vel.x, body.vel.y) < tuning.restSpeed) {
    body.vel.x = 0
    body.vel.y = 0
  }
  return body
}

/** 拖拽采样点：DOM timeStamp 与 performance.now() 同钟 */
export interface DragSample {
  t: number
  x: number
  y: number
}

/** 用最近 windowMs 内的首尾采样估算甩出速度（px/s）；样本不足或时间跨度为 0 返回 0 速度 */
export function estimateThrowVelocity(samples: DragSample[], now: number, windowMs = 90): Vec2 {
  const recent = samples.filter((s) => now - s.t <= windowMs)
  const first = recent[0]
  const last = recent[recent.length - 1]
  if (!first || last === first || now - first.t <= 0) return { x: 0, y: 0 }
  const dt = (last.t - first.t) / 1000
  if (dt <= 0) return { x: 0, y: 0 }
  return { x: (last.x - first.x) / dt, y: (last.y - first.y) / dt }
}

/** 选一个漫游目标 x：与当前位置至少拉开一段距离，且留在边界内 */
export function nextWanderTarget(world: PetWorld, currentX: number, rng: () => number = Math.random): number {
  const b = worldBounds(world)
  const span = b.maxX - b.minX
  if (span <= 8) return (b.minX + b.maxX) / 2
  const minGap = Math.min(span * 0.2, 120)
  for (let i = 0; i < 8; i++) {
    const candidate = b.minX + rng() * span
    if (Math.abs(candidate - currentX) >= minGap) return candidate
  }
  // 兜底：往较远一侧走
  return currentX > (b.minX + b.maxX) / 2 ? b.minX + span * 0.15 : b.maxX - span * 0.15
}
