<template>
  <div
    :class="[
      'responsive-shell',
      variantClass,
      legacyShellClass,
      {
        'sidebar-collapsed': sidebarCollapsed,
        'mobile-sidebar-open': mobileSidebarOpen
      }
    ]"
    :style="shellStyle"
  >
    <AppBackground />
    <slot name="scrim"></slot>
    <slot></slot>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import AppBackground from './AppBackground.vue'

const props = defineProps({
  variant: { type: String, default: 'client' },
  sidebarCollapsed: { type: Boolean, default: false },
  mobileSidebarOpen: { type: Boolean, default: false },
  sidebarWidth: { type: String, default: '284px' },
  collapsedWidth: { type: String, default: '72px' }
})

const variantClass = computed(() => `responsive-shell-${props.variant}`)
const legacyShellClass = computed(() => props.variant === 'admin' ? 'admin-shell' : 'workbench-shell')
const shellStyle = computed(() => ({
  '--shell-sidebar-width': props.sidebarCollapsed ? props.collapsedWidth : props.sidebarWidth,
  '--shell-sidebar-expanded-width': props.sidebarWidth,
  '--shell-sidebar-collapsed-width': props.collapsedWidth
}))
</script>
