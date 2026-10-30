/** 日期/时间/字节/计数格式化（门户与后台共用） */

const DATE_FMT = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
const TIME_FMT = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' })

function toDate(value: string | number | Date): Date | null {
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 2026-01-02 */
export function formatDate(value: string | number | Date | null | undefined): string {
  if (value === null || value === undefined || value === '') return '-'
  const date = toDate(value)
  return date ? DATE_FMT.format(date) : '-'
}

/** 2026-01-02 08:05 */
export function formatDateTime(value: string | number | Date | null | undefined): string {
  if (value === null || value === undefined || value === '') return '-'
  const date = toDate(value)
  return date ? `${DATE_FMT.format(date)} ${TIME_FMT.format(date)}` : '-'
}

/** 相对时间：刚刚 / n 分钟前 / n 小时前 / n 天前，超过 30 天回退绝对日期 */
export function formatRelative(value: string | number | Date | null | undefined, now = Date.now()): string {
  if (value === null || value === undefined || value === '') return '-'
  const date = toDate(value)
  if (!date) return '-'
  const diffMs = now - date.getTime()
  if (diffMs < 0) return formatDate(date)
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  if (diffMs < minute) return '刚刚'
  if (diffMs < hour) return `${Math.floor(diffMs / minute)} 分钟前`
  if (diffMs < day) return `${Math.floor(diffMs / hour)} 小时前`
  if (diffMs < 30 * day) return `${Math.floor(diffMs / day)} 天前`
  return formatDate(date)
}

/** 字节数 → 1.2 KB / 3.4 MB（文件管理用） */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '-'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 'B'
  for (const next of units) {
    if (value < 1024) break
    value /= 1024
    unit = next
  }
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${unit}`
}

/** 计数 → 1.2k / 3.4w（浏览/点赞展示） */
export function formatCount(count: number | null | undefined): string {
  if (count === null || count === undefined || Number.isNaN(count)) return '0'
  if (count < 1000) return String(count)
  if (count < 10_000) return `${(count / 1000).toFixed(1)}k`
  return `${(count / 10_000).toFixed(1)}w`
}
