import { defineStore } from 'pinia'

const SIDEBAR_KEY = 'echomind.ui.sidebarCollapsed'
const INSPECTOR_KEY = 'echomind.ui.inspectorOpen'
const THEME_KEY = 'echomind.ui.theme'
const BACKGROUND_KEY = 'echomind.ui.background'
const THEMES = ['dark', 'light']
const BACKGROUND_MODES = ['theme', 'solid', 'gradient', 'image']
const BACKGROUND_IMAGE_FITS = ['cover', 'contain', 'repeat']
const BACKGROUND_GLASS_EFFECTS = ['frosted', 'clear']
export const BACKGROUND_IMAGE_MAX_BYTES = 2 * 1024 * 1024
const BACKGROUND_IMAGE_MAX_URL_LENGTH = 3 * 1024 * 1024
const DEFAULT_BACKGROUND = {
  mode: 'theme',
  solidColor: '#f5f7fb',
  gradientFrom: '#4f6bff',
  gradientTo: '#0891b2',
  imageUrl: '',
  imageFit: 'cover',
  glassEffect: 'frosted',
  glassBlur: 50,
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

function normalizeImageUrl(value) {
  const url = String(value || '').trim()
  if (!url || url.length > BACKGROUND_IMAGE_MAX_URL_LENGTH) return ''
  if (/[\u0000-\u001F\u007F"'<>\\]/.test(url)) return ''
  return url
}

export function renderableImageUrl(value) {
  const url = normalizeImageUrl(value)
  if (!url) return ''
  if (/^data:image\/[a-z0-9.+-]+[,;]/i.test(url)) return url
  if (/^https?:\/\/[^\s]+$/i.test(url)) return url
  if (/^\/[^\s]+$/.test(url)) return url
  return ''
}

function sanitizeImageFit(value) {
  return BACKGROUND_IMAGE_FITS.includes(value) ? value : DEFAULT_BACKGROUND.imageFit
}

function sanitizeGlassEffect(value) {
  return BACKGROUND_GLASS_EFFECTS.includes(value) ? value : DEFAULT_BACKGROUND.glassEffect
}

function cssImageUrl(value) {
  const url = renderableImageUrl(value)
  if (!url) return 'transparent'
  return `url("${url.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}")`
}

function normalizeBackground(value) {
  const source = value && typeof value === 'object' ? value : {}
  return {
    mode: BACKGROUND_MODES.includes(source.mode) ? source.mode : DEFAULT_BACKGROUND.mode,
    solidColor: sanitizeColor(source.solidColor, DEFAULT_BACKGROUND.solidColor),
    gradientFrom: sanitizeColor(source.gradientFrom, DEFAULT_BACKGROUND.gradientFrom),
    gradientTo: sanitizeColor(source.gradientTo, DEFAULT_BACKGROUND.gradientTo),
    imageUrl: normalizeImageUrl(source.imageUrl),
    imageFit: sanitizeImageFit(source.imageFit),
    glassEffect: sanitizeGlassEffect(source.glassEffect),
    glassBlur: clampNumber(source.glassBlur ?? DEFAULT_BACKGROUND.glassBlur, 0, 100),
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
    try {
      window.localStorage.setItem(BACKGROUND_KEY, JSON.stringify(normalizeBackground(value)))
    } catch (e) {
      // Oversized local images can exceed browser storage; keep the in-session preview working.
    }
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
  if (nextBackground.mode === 'image') {
    return cssImageUrl(nextBackground.imageUrl)
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
  const blurFactor = nextBackground.glassBlur / DEFAULT_BACKGROUND.glassBlur
  const root = document.documentElement
  root.dataset.backgroundMode = nextBackground.mode
  root.dataset.backgroundEffect = nextBackground.glassEffect
  root.style.setProperty('--custom-bg', backgroundToCss(nextBackground))
  root.style.setProperty('--custom-bg-opacity', String(nextBackground.opacity / 100))
  root.style.setProperty('--custom-bg-solid', nextBackground.solidColor)
  root.style.setProperty('--custom-bg-gradient-from', nextBackground.gradientFrom)
  root.style.setProperty('--custom-bg-gradient-to', nextBackground.gradientTo)
  root.style.setProperty('--custom-bg-image-size', nextBackground.imageFit === 'repeat' ? 'auto' : nextBackground.imageFit)
  root.style.setProperty('--custom-bg-image-repeat', nextBackground.imageFit === 'repeat' ? 'repeat' : 'no-repeat')
  root.style.setProperty('--custom-bg-image-position', 'center center')
  root.style.setProperty('--glass-blur-subtle', `${Math.round(18 * blurFactor)}px`)
  root.style.setProperty('--glass-blur', `${Math.round(22 * blurFactor)}px`)
  root.style.setProperty('--glass-blur-control', `${Math.round(24 * blurFactor)}px`)
  root.style.setProperty('--glass-blur-panel', `${Math.round(26 * blurFactor)}px`)
  root.style.setProperty('--glass-blur-shell', `${Math.round(30 * blurFactor)}px`)
  root.style.setProperty('--glass-saturate', `${Math.round(100 + 50 * blurFactor)}%`)
  root.style.setProperty('--glass-saturate-panel', `${Math.round(100 + 55 * blurFactor)}%`)
  root.style.setProperty('--glass-saturate-shell', `${Math.round(100 + 60 * blurFactor)}%`)
  root.style.setProperty('--glass-saturate-strong', `${Math.round(100 + 65 * blurFactor)}%`)
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
        gradient: '渐变填充',
        image: '图片填充'
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
