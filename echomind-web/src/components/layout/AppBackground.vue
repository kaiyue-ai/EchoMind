<template>
  <div class="app-background" :style="ambientStyle" aria-hidden="true">
    <div
      v-if="hasImageBackground"
      class="app-background-image"
      :style="imageStyle"
    ></div>
    <div v-else class="app-background-fill"></div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { renderableImageUrl, useUiStore } from '../../stores/ui'

const uiStore = useUiStore()
const { background } = storeToRefs(uiStore)
const ambientX = ref(0)
const ambientY = ref(0)
let ambientFrame = 0
let reduceMotionQuery = null
let finePointerQuery = null

const imageUrl = computed(() => renderableImageUrl(background.value.imageUrl))
const hasImageBackground = computed(() => background.value.mode === 'image' && Boolean(imageUrl.value))
const ambientStyle = computed(() => ({
  '--ambient-shift-x': `${ambientX.value}px`,
  '--ambient-shift-y': `${ambientY.value}px`
}))
const imageStyle = computed(() => ({
  opacity: background.value.opacity / 100,
  backgroundImage: `url("${imageUrl.value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}")`,
  backgroundRepeat: background.value.imageFit === 'repeat' ? 'repeat' : 'no-repeat',
  backgroundSize: background.value.imageFit === 'repeat' ? 'auto' : background.value.imageFit,
  backgroundPosition: 'center center'
}))

function updateAmbient(event) {
  if (reduceMotionQuery?.matches || finePointerQuery?.matches === false || ambientFrame) return
  const { innerWidth = 1, innerHeight = 1 } = window
  const nextX = ((event.clientX / innerWidth) - 0.5) * 28
  const nextY = ((event.clientY / innerHeight) - 0.5) * 22
  ambientFrame = requestAnimationFrame(() => {
    ambientFrame = 0
    ambientX.value = Math.round(nextX)
    ambientY.value = Math.round(nextY)
  })
}

onMounted(() => {
  if (typeof window === 'undefined') return
  reduceMotionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  finePointerQuery = window.matchMedia?.('(hover: hover) and (pointer: fine)')
  if (!reduceMotionQuery?.matches && finePointerQuery?.matches) {
    window.addEventListener('pointermove', updateAmbient, { passive: true })
  }
})

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('pointermove', updateAmbient)
  }
  if (ambientFrame) {
    cancelAnimationFrame(ambientFrame)
  }
})
</script>
