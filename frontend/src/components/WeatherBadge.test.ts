import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import WeatherBadge from '@/components/WeatherBadge.vue'
import { WEATHER_EMOJI, WEATHER_LIST } from '@/utils/weather'

/**
 * WeatherBadge：合法天气渲染对应 emoji，未知天气回退 '—' 占位。
 */

describe('WeatherBadge', () => {
  it('合法天气：渲染 .lb-weather-badge 与对应 emoji', () => {
    const weather = WEATHER_LIST[0]
    const wrapper = mount(WeatherBadge, { props: { weather } })
    expect(wrapper.find('.lb-weather-badge').exists()).toBe(true)
    expect(wrapper.find('.lb-weather-emoji').text()).toBe(WEATHER_EMOJI[weather])
  })

  it('未知天气：不渲染徽标，回退 span.lb-muted 的 —', () => {
    const wrapper = mount(WeatherBadge, { props: { weather: '极光' } })
    expect(wrapper.find('.lb-weather-badge').exists()).toBe(false)
    const muted = wrapper.find('.lb-muted')
    expect(muted.exists()).toBe(true)
    expect(muted.text()).toBe('—')
  })

  it('空字符串同样按未知处理', () => {
    const wrapper = mount(WeatherBadge, { props: { weather: '' } })
    expect(wrapper.find('.lb-weather-badge').exists()).toBe(false)
    expect(wrapper.text()).toBe('—')
  })
})
