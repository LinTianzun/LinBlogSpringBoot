/**
 * SSE 封装（§5.9 GET /api/v1/events）：EventSource 自动携带 Cookie。
 * 事件类型：message.new / report.new / heartbeat。
 * 断线自动指数退避重连（1s 起，封顶 30s），手动 close 后不再重连。
 */

export type SseEventHandler = (data: unknown) => void

const BASE = '/api/v1/events'
const MIN_BACKOFF_MS = 1000
const MAX_BACKOFF_MS = 30_000

export class SseClient {
  private source: EventSource | null = null
  private handlers = new Map<string, Set<SseEventHandler>>()
  private backoffMs = MIN_BACKOFF_MS
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private closedByUser = false

  /** 注册事件回调；返回解绑函数 */
  on(event: string, handler: SseEventHandler): () => void {
    let set = this.handlers.get(event)
    if (!set) {
      set = new Set()
      this.handlers.set(event, set)
    }
    set.add(handler)
    return () => {
      set?.delete(handler)
    }
  }

  connect(): void {
    if (typeof EventSource === 'undefined' || this.source || this.closedByUser) return
    this.closedByUser = false
    const source = new EventSource(BASE, { withCredentials: true })
    this.source = source
    source.onopen = () => {
      this.backoffMs = MIN_BACKOFF_MS
    }
    source.onerror = () => {
      // EventSource 关闭后浏览器不会自动重连（自定义退避接管）
      source.close()
      this.source = null
      if (this.closedByUser) return
      this.reconnectTimer = setTimeout(() => this.connect(), this.backoffMs)
      this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS)
    }
    for (const event of this.handlers.keys()) this.bindEvent(event)
  }

  private bindEvent(event: string): void {
    if (!this.source) return
    this.source.addEventListener(event, (raw) => {
      const messageEvent = raw as MessageEvent
      let data: unknown = messageEvent.data
      if (typeof messageEvent.data === 'string' && messageEvent.data.length > 0) {
        try {
          data = JSON.parse(messageEvent.data)
        } catch {
          data = messageEvent.data
        }
      }
      for (const handler of this.handlers.get(event) ?? []) handler(data)
    })
  }

  close(): void {
    this.closedByUser = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.source?.close()
    this.source = null
  }
}

export const sseClient = new SseClient()
