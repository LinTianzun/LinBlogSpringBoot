import { describe, expect, it } from 'vitest'
import { formatBytes, formatCount, formatDate, formatRelative } from '@/utils/format'

/** 日期/字节/计数格式化 util */

describe('formatDate/formatRelative', () => {
  it('非法与空值返回 -', () => {
    expect(formatDate(null)).toBe('-')
    expect(formatDate(undefined)).toBe('-')
    expect(formatDate('not-a-date')).toBe('-')
    expect(formatRelative('')).toBe('-')
  })

  it('formatRelative 分档：刚刚/分钟/小时/天', () => {
    const now = new Date('2026-01-02T12:00:00').getTime()
    expect(formatRelative(new Date(now - 10_000), now)).toBe('刚刚')
    expect(formatRelative(new Date(now - 5 * 60_000), now)).toBe('5 分钟前')
    expect(formatRelative(new Date(now - 3 * 3_600_000), now)).toBe('3 小时前')
    expect(formatRelative(new Date(now - 2 * 86_400_000), now)).toBe('2 天前')
  })
})

describe('formatBytes', () => {
  it('字节与单位换算', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1024)).toBe('1.0 KB')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(1024 * 1024)).toBe('1.0 MB')
    expect(formatBytes(null)).toBe('-')
  })
})

describe('formatCount', () => {
  it('计数缩写 k/w', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(999)).toBe('999')
    expect(formatCount(1200)).toBe('1.2k')
    expect(formatCount(34_500)).toBe('3.5w')
    expect(formatCount(undefined)).toBe('0')
  })
})
