/**
 * §5.7 /media/{storageKey}：immutable 1y。
 * 前端只负责按展示尺寸挑选宽度并生成 srcset；缩放由后端完成。
 * ⚠️ 开发文档写 ?w=，backend MediaController 实际参数名是 ?width=，此处以后端为准。
 */

export type MediaWidth = 320 | 640 | 1280

export function mediaUrl(storageKey: string, w?: MediaWidth): string {
  const path = `/api/v1/media/${encodeURIComponent(storageKey)}`
  return w === undefined ? path : `${path}?width=${w}`
}

/** 瀑布流/详情封面 srcset：320/640/1280 三档 */
export function mediaSrcSet(storageKey: string): string {
  return ([320, 640, 1280] as MediaWidth[]).map((w) => `${mediaUrl(storageKey, w)} ${w}w`).join(', ')
}
