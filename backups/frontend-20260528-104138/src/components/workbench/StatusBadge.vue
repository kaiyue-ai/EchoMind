<template>
  <span :class="['status-badge', toneClass]">
    <span class="status-badge-dot"></span>
    <slot>{{ label }}</slot>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  tone: { type: String, default: 'neutral' },
  label: { type: String, default: '' }
})

const toneClass = computed(() => `status-badge-${props.tone}`)
</script>
