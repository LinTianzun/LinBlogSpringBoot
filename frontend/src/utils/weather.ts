import type { Weather } from '@/types/api'

/** §3.2 天气 8 值白名单 → emoji 徽标映射 */
export const WEATHER_EMOJI: Record<Weather, string> = {
  晴天: '☀️',
  多云: '⛅',
  大雪: '❄️',
  大雨: '🌧️',
  阵风: '🌬️',
  雷雨: '⛈️',
  雾霾: '🌫️',
  夜晚: '🌙',
}

export const WEATHER_LIST: readonly Weather[] = Object.keys(WEATHER_EMOJI) as Weather[]

export function isWeather(value: unknown): value is Weather {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(WEATHER_EMOJI, value)
}

/** 非法/缺失天气回退为 null（展示层显示占位） */
export function weatherEmoji(value: unknown): string | null {
  return isWeather(value) ? WEATHER_EMOJI[value] : null
}
