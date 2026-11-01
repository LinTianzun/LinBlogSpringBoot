import { describe, expect, it } from 'vitest'
import { mediaSrcSet, mediaUrl } from '@/utils/media'

/**
 * media.ts：媒体 URL 拼装 — /api/v1/media/ 前缀、key 编码、w 变体、srcSet 三档。
 */

describe('mediaUrl', () => {
  it('拼 /api/v1/media/ 前缀', () => {
    expect(mediaUrl('abc.png')).toBe('/api/v1/media/abc.png')
  })

  it('对 key 做 encodeURIComponent（空格与斜杠）', () => {
    expect(mediaUrl('a b/c.png')).toBe('/api/v1/media/a%20b%2Fc.png')
  })

  it('指定宽度变体追加 ?w=', () => {
    expect(mediaUrl('abc', 320)).toBe('/api/v1/media/abc?w=320')
    expect(mediaUrl('abc', 640)).toBe('/api/v1/media/abc?w=640')
    expect(mediaUrl('abc', 1280)).toBe('/api/v1/media/abc?w=1280')
  })
})

describe('mediaSrcSet', () => {
  it('320/640/1280 三档，格式 {url} {w}w，逗号分隔', () => {
    expect(mediaSrcSet('k')).toBe(
      '/api/v1/media/k?w=320 320w, /api/v1/media/k?w=640 640w, /api/v1/media/k?w=1280 1280w',
    )
  })

  it('key 中的特殊字符同样被编码', () => {
    expect(mediaSrcSet('图 片')).toContain('/api/v1/media/%E5%9B%BE%20%E7%89%87?w=320')
  })
})
