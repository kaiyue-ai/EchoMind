import { defineStore } from 'pinia'

const SIDEBAR_KEY = 'echomind.ui.sidebarCollapsed'
const INSPECTOR_KEY = 'echomind.ui.inspectorOpen'
const THEME_KEY = 'echomind.ui.theme'
const THEMES = ['dark', 'light']

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

export function applyThemePreference(theme = readTheme()) {
  if (typeof document === 'undefined') return
  const nextTheme = THEMES.includes(theme) ? theme : 'dark'
  document.documentElement.dataset.theme = nextTheme
  document.documentElement.style.colorScheme = nextTheme
}

export const useUiStore = defineStore('ui', {
  state: () => ({
    sidebarCollapsed: readBoolean(SIDEBAR_KEY, false),
    inspectorOpen: readBoolean(INSPECTOR_KEY, true),
    mobileSidebarOpen: false,
    theme: readTheme()
  }),
  getters: {
    isLightTheme: (state) => state.theme === 'light',
    themeLabel: (state) => state.theme === 'light' ? '浅色模式' : '深色模式',
    themeToggleLabel: (state) => state.theme === 'light' ? '切换深色模式' : '切换浅色模式'
  },
  actions: {
    applyTheme() {
      applyThemePreference(this.theme)
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
