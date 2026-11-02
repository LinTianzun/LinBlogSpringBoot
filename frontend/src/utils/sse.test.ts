import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SseClient } from '@/utils/sse'

/**
 * sse.ts：SseClient 行为 — 连接 /events（backend EventController 根路径）+
 * withCredentials、on/unbind、JSON data 解析、断线指数退避重连、close 停连。
 */

class FakeEventSource {
  static instances: FakeEventSource[] = []
  url: string
  options?: EventSourceInit
  readyState = 0
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  listeners = new Map<string, Array<(event: MessageEvent) => void>>()

  constructor(url: string, options?: EventSourceInit) {
    this.url = url
    this.options = options
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void): void {
    const list = this.listeners.get(type) ?? []
    list.push(listener)
    this.listeners.set(type, list)
  }

  close(): void {
    this.readyState = 2
  }

  emit(type: string, data: unknown): void {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data } as MessageEvent)
    }
  }
}

describe('SseClient', () => {
  let client: SseClient

  beforeEach(() => {
    vi.useFakeTimers()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource as unknown as typeof EventSource)
    client = new SseClient()
  })

  afterEach(() => {
    client.close()
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('connect() 创建 EventSource，URL 为根路径 /events 且带 withCredentials', () => {
    client.connect()
    expect(FakeEventSource.instances).toHaveLength(1)
    const source = FakeEventSource.instances[0]
    expect(source.url).toBe('/events')
    expect(source.options).toEqual({ withCredentials: true })
  })

  it('on 注册的回调在收到事件时触发，JSON data 自动解析', () => {
    const handler = vi.fn()
    client.on('message.new', handler)
    client.connect()
    FakeEventSource.instances[0].emit('message.new', '{"id":7}')
    expect(handler).toHaveBeenCalledWith({ id: 7 })
  })

  it('非 JSON 的 data 原样透传；解绑函数生效', () => {
    const handler = vi.fn()
    const unbind = client.on('heartbeat', handler)
    client.connect()
    const source = FakeEventSource.instances[0]

    source.emit('heartbeat', 'ping')
    expect(handler).toHaveBeenCalledWith('ping')

    unbind()
    source.emit('heartbeat', 'pong')
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('error 后指数退避重连（1s→2s），onopen 重置退避', () => {
    client.connect()
    let source = FakeEventSource.instances[0]

    source.onerror?.()
    expect(FakeEventSource.instances).toHaveLength(1) // 尚未到 1s
    vi.advanceTimersByTime(1000)
    expect(FakeEventSource.instances).toHaveLength(2)

    source = FakeEventSource.instances[1]
    source.onopen?.() // 重置退避
    source.onerror?.()
    vi.advanceTimersByTime(1000)
    expect(FakeEventSource.instances).toHaveLength(3) // 重置后仍为 1s
  })

  it('close() 后不再重连', () => {
    client.connect()
    FakeEventSource.instances[0].onerror?.()
    client.close()
    vi.advanceTimersByTime(60_000)
    expect(FakeEventSource.instances).toHaveLength(1)
  })
})
