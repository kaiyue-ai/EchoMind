import { defineStore } from 'pinia'

const SIDEBAR_KEY = 'echomind.ui.sidebarCollapsed'
const INSPECTOR_KEY = 'echomind.ui.inspectorOpen'
const THEME_KEY = 'echomind.ui.theme'
const BACKGROUND_KEY = 'echomind.ui.background'
const THEMES = ['dark', 'light']
const BACKGROUND_MODES = ['theme', 'solid', 'gradient']
const DEFAULT_BACKGROUND = {
  mode: 'theme',
  solidColor: '#f5f7fb',
  gradientFrom: '#4f6bff',
  gradientTo: '#0891b2',
  opacity: 100
}

function readBoolean(key, fallback) {
  if (typeof window === 'undefined') return fallback
  const value = window.localStorage.getItem(key)
  if (value === null) return fallback
  return value === 'true'
}

function writeBoolean(key, value) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(key, String(value))
  }
}

function readTheme() {
  if (typeof window === 'undefined') return 'dark'
  const storedTheme = window.localStorage.getItem(THEME_KEY)
  if (THEMES.includes(storedTheme)) return storedTheme
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

function clampNumber(value, min, max) {
  const number = Number(value)
  if (Number.isNaN(number)) return min
  return Math.min(max, Math.max(min, number))
}

function sanitizeColor(value, fallback) {
  const color = String(value || '').trim()
  return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.test(color) ? color : fallback
}

function normalizeBackground(value) {
  const source = value && typeof value === 'object' ? value : {}
  return {
    mode: BACKGROUND_MODES.includes(source.mode) ? source.mode : DEFAULT_BACKGROUND.mode,
    solidColor: sanitizeColor(source.solidColor, DEFAULT_BACKGROUND.solidColor),
    gradientFrom: sanitizeColor(source.gradientFrom, DEFAULT_BACKGROUND.gradientFrom),
    gradientTo: sanitizeColor(source.gradientTo, DEFAULT_BACKGROUND.gradientTo),
    opacity: clampNumber(source.opacity ?? DEFAULT_BACKGROUND.opacity, 0, 100)
  }
}

function readBackground() {
  if (typeof window === 'undefined') return { ...DEFAULT_BACKGROUND }
  try {
    return normalizeBackground(JSON.parse(window.localStorage.getItem(BACKGROUND_KEY) || '{}'))
  } catch (e) {
    return { ...DEFAULT_BACKGROUND }
  }
}

function writeBackground(value) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(BACKGROUND_KEY, JSON.stringify(normalizeBackground(value)))
  }
}

function backgroundToCss(background) {
  const nextBackground = normalizeBackground(background)
  if (nextBackground.mode === 'solid') {
    return nextBackground.solidColor
  }
  if (nextBackground.mode === 'gradient') {
    return `linear-gradient(135deg, ${nextBackground.gradientFrom} 0%, ${nextBackground.gradientTo} 100%)`
  }
  return 'transparent'
}

export function applyThemePreference(theme = readTheme()) {
  if (typeof document === 'undefined') return
  const nextTheme = THEMES.includes(theme) ? theme : 'dark'
  document.documentElement.dataset.theme = nextTheme
  document.documentElement.style.colorScheme = nextTheme
}

export function applyBackgroundPreference(background = readBackground()) {
  if (typeof document === 'undefined') return
  const nextBackground = normalizeBackground(background)
  const root = document.documentElement
  root.dataset.backgroundMode = nextBackground.mode
  root.style.setProperty('--custom-bg', backgroundToCss(nextBackground))
  root.style.setProperty('--custom-bg-opacity', String(nextBackground.opacity / 100))
  root.style.setProperty('--custom-bg-solid', nextBackground.solidColor)
  root.style.setProperty('--custom-bg-gradient-from', nextBackground.gradientFrom)
  root.style.setProperty('--custom-bg-gradient-to', nextBackground.gradientTo)
}

export const useUiStore = defineStore('ui', {
  state: () => ({
    sidebarCollapsed: readBoolean(SIDEBAR_KEY, false),
    inspectorOpen: readBoolean(INSPECTOR_KEY, true),
    mobileSidebarOpen: false,
    theme: readTheme(),
    background: readBackground()
  }),
  getters: {
    isLightTheme: (state) => state.theme === 'light',
    themeLabel: (state) => state.theme === 'light' ? '浅色模式' : '深色模式',
    themeToggleLabel: (state) => state.theme === 'light' ? '切换深色模式' : '切换浅色模式',
    backgroundModeLabel: (state) => {
      return {
        theme: '跟随主题',
        solid: '纯色填充',
        gradient: '渐变填充'
      }[state.background.mode]
    }
  },
  actions: {
    applyTheme() {
      applyThemePreference(this.theme)
    },
    applyBackground() {
      applyBackgroundPreference(this.background)
    },
    applyAppearance() {
      this.applyTheme()
      this.applyBackground()
    },
    setTheme(theme) {
      this.theme = THEMES.includes(theme) ? theme : 'dark'
      if (typeof window !== 'undefined') {
        window.localStorage.setItem(THEME_KEY, this.theme)
      }
      this.applyTheme()
    },
    toggleTheme() {
      this.setTheme(this.theme === 'light' ? 'dark' : 'light')
    },
    setBackground(patch = {}) {
      this.background = normalizeBackground({ ...this.background, ...patch })
      writeBackground(this.background)
      this.applyBackground()
    },
    resetBackground() {
      this.background = { ...DEFAULT_BACKGROUND }
      writeBackground(this.background)
      this.applyBackground()
    },
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
      writeBoolean(SIDEBAR_KEY, this.sidebarCollapsed)
    },
    setSidebarCollapsed(value) {
      this.sidebarCollapsed = Boolean(value)
      writeBoolean(SIDEBAR_KEY, this.sidebarCollapsed)
    },
    toggleInspector() {
      this.inspectorOpen = !this.inspectorOpen
      writeBoolean(INSPECTOR_KEY, this.inspectorOpen)
    },
    setInspectorOpen(value) {
      this.inspectorOpen = Boolean(value)
      writeBoolean(INSPECTOR_KEY, this.inspectorOpen)
    },
    toggleMobileSidebar() {
      this.mobileSidebarOpen = !this.mobileSidebarOpen
    },
    closeMobileSidebar() {
      this.mobileSidebarOpen = false
    }
  }
})
