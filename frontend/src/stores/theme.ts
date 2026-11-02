import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 主题 store（art-design-pro 设计系统，精简版）：
 * 只管亮/暗与持久化，不持登录态。切换即翻转 html.dark，
 * element-plus 暗色变量与 art token（styles/art-tokens.less）随之生效。
 */
const THEME_KEY = 'linblog.theme'

function readInitial(): boolean {
  const saved = localStorage.getItem(THEME_KEY)
  if (saved !== null) return saved === 'dark'
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(readInitial())

  function apply(): void {
    document.documentElement.classList.toggle('dark', isDark.value)
  }

  function toggle(): void {
    isDark.value = !isDark.value
    localStorage.setItem(THEME_KEY, isDark.value ? 'dark' : 'light')
    apply()
  }

  apply()

  return { isDark, toggle }
})
