import { describe, expect, it } from 'vitest'
import { WEATHER_EMOJI, WEATHER_LIST, isWeather, weatherEmoji } from '@/utils/weather'

/** 8 值天气枚举与 emoji 映射（§2.4）：晴天|多云|大雪|大雨|阵风|雷雨|雾霾|夜晚 */

describe('天气映射', () => {
  it('恰好 8 个合法值，每个都有非空 emoji', () => {
    expect(WEATHER_LIST).toHaveLength(8)
    expect(WEATHER_LIST).toEqual(expect.arrayContaining(['晴天', '多云', '大雪', '大雨', '阵风', '雷雨', '雾霾', '夜晚']))
    for (const weather of WEATHER_LIST) {
      expect(WEATHER_EMOJI[weather]).toBeTruthy()
    }
  })

  it('isWeather 区分合法与非法值', () => {
    expect(isWeather('雷雨')).toBe(true)
    expect(isWeather('台风')).toBe(false)
    expect(isWeather(null)).toBe(false)
    expect(isWeather(42)).toBe(false)
  })

  it('weatherEmoji：合法值返回 emoji，非法值返回 null', () => {
    expect(weatherEmoji('大雪')).toBe(WEATHER_EMOJI.大雪)
    expect(weatherEmoji('不存在的天气')).toBeNull()
    expect(weatherEmoji(undefined)).toBeNull()
  })
})
