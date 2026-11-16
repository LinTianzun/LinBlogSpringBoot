import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import BlogPet from '@/components/BlogPet.vue'

/**
 * BlogPet 组件行为：渲染、轻点回应气泡、拖拽跟手、甩出进入飞行态、久置入睡。
 * 物理正确性在 utils/petPhysics.test.ts 覆盖；这里只驱动交互状态机。
 * jsdom 的 MouseEvent.clientX 只读且无 PointerEvent，用 MouseEvent 构造器注入坐标。
 */

const PET = '.lb-pet'

/** 派发一个带坐标的指针事件并等一帧 Vue 更新 */
async function fire(el: Element, type: string, x: number, y: number): Promise<void> {
  el.dispatchEvent(
    new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y }),
  )
  await nextTick()
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('BlogPet', () => {
  it('默认渲染并在右侧地板待机', () => {
    const w = mount(BlogPet, { attachTo: document.body })
    const pet = w.find(PET)
    expect(pet.exists()).toBe(true)
    expect(pet.classes()).toContain('is-idle')
    expect(pet.attributes('role')).toBe('button')
    const transform = (pet.element as HTMLElement).style.transform
    expect(transform).toContain('translate3d')
    // 默认落位在视口右侧（0.82 倍宽）
    const x = Number(transform.match(/translate3d\(([-\d.]+)px/)?.[1])
    expect(x).toBeGreaterThan(window.innerWidth / 2)
    w.unmount()
  })

  it('轻点回应：Q 弹 + 气泡带一句台词，随后自动收起', async () => {
    vi.useFakeTimers()
    const w = mount(BlogPet, { attachTo: document.body })
    const pet = w.find(PET)
    await fire(pet.element, 'pointerdown', 500, 700)
    await fire(pet.element, 'pointerup', 500, 700)
    expect(pet.classes()).toContain('is-squash')
    const bubble = w.find('.lb-pet-bubble')
    expect(bubble.exists()).toBe(true)
    expect(bubble.text()).not.toBe('')
    await vi.advanceTimersByTimeAsync(3000)
    expect(w.find('.lb-pet-bubble').exists()).toBe(false)
    w.unmount()
  })

  it('拖拽跟手：实时更新位置，松手后进入飞行态落回', async () => {
    const w = mount(BlogPet, { attachTo: document.body })
    const pet = w.find(PET)
    await fire(pet.element, 'pointerdown', 500, 700)
    await fire(pet.element, 'pointermove', 400, 400)
    expect(w.find(PET).classes()).toContain('is-drag')
    const t1 = Number(
      (pet.element as HTMLElement).style.transform.match(/translate3d\(([-\d.]+)px/)?.[1],
    )
    await fire(pet.element, 'pointermove', 300, 300)
    const t2 = Number(
      (pet.element as HTMLElement).style.transform.match(/translate3d\(([-\d.]+)px/)?.[1],
    )
    // 指针左移 100px，宠物保持抓取偏移同步左移 100px
    expect(t2).toBeCloseTo(t1 - 100, 0)
    await fire(pet.element, 'pointerup', 300, 300)
    expect(w.find(PET).classes()).toContain('is-fly')
    w.unmount()
  })

  it('拖出视口外的坐标被钳回世界内', async () => {
    const w = mount(BlogPet, { attachTo: document.body })
    const pet = w.find(PET)
    await fire(pet.element, 'pointerdown', 500, 700)
    await fire(pet.element, 'pointermove', -9999, -9999)
    const t = (pet.element as HTMLElement).style.transform
    expect(Number(t.match(/translate3d\(([-\d.]+)px/)?.[1])).toBeGreaterThanOrEqual(0)
    expect(Number(t.match(/,\s*([-\d.]+)px/)?.[1])).toBeGreaterThanOrEqual(0)
    w.unmount()
  })

  it('久置不动会打瞌睡，交互即醒', async () => {
    vi.useFakeTimers()
    const w = mount(BlogPet, { attachTo: document.body })
    await vi.advanceTimersByTimeAsync(100_000)
    expect(w.find(PET).classes()).toContain('is-sleep')
    expect(w.find('.lb-pet-zzz').exists()).toBe(true)
    const pet = w.find(PET)
    await fire(pet.element, 'pointerdown', 500, 700)
    expect(w.find(PET).classes()).toContain('is-drag')
    expect(w.find(PET).classes()).not.toContain('is-sleep')
    w.unmount()
  })

  it('位置持久化：卸载时写入 localStorage，重挂载还原', async () => {
    const w = mount(BlogPet, { attachTo: document.body })
    const pet = w.find(PET)
    // 拖到新位置再卸载，卸载钩子应持久化当前位置
    await fire(pet.element, 'pointerdown', 200, 500)
    await fire(pet.element, 'pointermove', 150, 500)
    w.unmount()
    const saved = JSON.parse(localStorage.getItem('linblog.pet') ?? 'null') as { x: number; y: number } | null
    expect(saved).not.toBeNull()

    const w2 = mount(BlogPet, { attachTo: document.body })
    const t = (w2.find(PET).element as HTMLElement).style.transform
    // 持久化存的是中心坐标，transform 写的是左上角（差半个身位 38px）
    expect(Number(t.match(/translate3d\(([-\d.]+)px/)?.[1])).toBeCloseTo(saved!.x - 38, 0)
    w2.unmount()
  })

  it('卸载清理：不再残留定时器副作用', async () => {
    vi.useFakeTimers()
    const w = mount(BlogPet, { attachTo: document.body })
    w.unmount()
    // 卸载后再推进大量时间不抛错，即说明定时器已清空
    await vi.advanceTimersByTimeAsync(200_000)
  })
})
