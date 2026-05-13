import { defineStore } from 'pinia'

const SIDEBAR_KEY = 'echomind.ui.sidebarCollapsed'
const INSPECTOR_KEY = 'echomind.ui.inspectorOpen'

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

export const useUiStore = defineStore('ui', {
  state: () => ({
    sidebarCollapsed: readBoolean(SIDEBAR_KEY, false),
    inspectorOpen: readBoolean(INSPECTOR_KEY, true),
    mobileSidebarOpen: false
  }),
  actions: {
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
