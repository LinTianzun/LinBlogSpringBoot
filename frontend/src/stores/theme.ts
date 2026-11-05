import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 主题 store（art-design-pro 设计系统，精简版 + 多套主色主题）：
 * 管亮/暗与多套主题色，不持登录态。
 * 切换亮暗即翻转 html.dark；切换主题即把主色写到 --main-color，
 * art-tokens.less 里 --el-color-primary 等 9 档色阶用 color-mix 纯 CSS 推导，
 * 因此只改 --main-color 全站主色即变（含 element-plus 组件）。
 *
 * 兼容性：isDark / toggle() 语义保持不变（admin/AdminLayout.vue:77/132 依赖）。
 */

/** 旧的亮暗持久化键 */
const THEME_KEY = 'linblog.theme'
/** 新增：主题色 id 持久化键 */
const THEME_ID_KEY = 'linblog.themeId'
/** 默认主题色 id */
const DEFAULT_THEME_ID = 'green'

export interface ThemeOption {
  /** 唯一 id，用于持久化与 setTheme */
  id: string
  /** 展示名（中文） */
  label: string
  /** 主色（写入 --main-color） */
  color: string
}

/** 4 套可切换主题色 */
export const themes: ThemeOption[] = [
  { id: 'green', label: '森野绿', color: '#18a058' },
  { id: 'blue', label: '雅致蓝', color: '#3b6ce0' },
  { id: 'violet', label: '雾紫', color: '#7c5cff' },
  { id: 'amber', label: '暖阳', color: '#d98324' },
]

function readInitialDark(): boolean {
  const saved = localStorage.getItem(THEME_KEY)
  if (saved !== null) return saved === 'dark'
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

function normalizeId(id: string | null): string {
  if (id && themes.some((t) => t.id === id)) return id
  return DEFAULT_THEME_ID
}

function readInitialThemeId(): string {
  return normalizeId(localStorage.getItem(THEME_ID_KEY))
}

function findTheme(id: string): ThemeOption {
  return themes.find((t) => t.id === id) ?? themes[0]
}

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(readInitialDark())
  const themeId = ref(readInitialThemeId())

  /** 当前主题对象 */
  const currentTheme = ref<ThemeOption>(findTheme(themeId.value))

  /** 把亮暗 + 主色应用到 DOM */
  function apply(): void {
    const root = document.documentElement
    root.classList.toggle('dark', isDark.value)
    root.style.setProperty('--main-color', currentTheme.value.color)
  }

  function toggle(): void {
    isDark.value = !isDark.value
    localStorage.setItem(THEME_KEY, isDark.value ? 'dark' : 'light')
    apply()
  }

  function setTheme(id: string): void {
    themeId.value = normalizeId(id)
    currentTheme.value = findTheme(themeId.value)
    localStorage.setItem(THEME_ID_KEY, themeId.value)
    apply()
  }

  /** 在 4 套主题间循环切换（供快捷按钮使用） */
  function cycleTheme(): void {
    const idx = themes.findIndex((t) => t.id === themeId.value)
    const next = themes[(idx + 1) % themes.length]
    setTheme(next.id)
  }

  apply()

  return { isDark, themeId, currentTheme, themes, toggle, setTheme, cycleTheme }
})
