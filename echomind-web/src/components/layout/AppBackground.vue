<template>
  <div class="app-background" aria-hidden="true">
    <div
      v-if="hasImageBackground"
      class="app-background-image"
      :style="imageStyle"
    ></div>
    <div v-else class="app-background-fill"></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { renderableImageUrl, useUiStore } from '../../stores/ui'

const uiStore = useUiStore()
const { background } = storeToRefs(uiStore)

const imageUrl = computed(() => renderableImageUrl(background.value.imageUrl))
const hasImageBackground = computed(() => background.value.mode === 'image' && Boolean(imageUrl.value))
const imageStyle = computed(() => ({
  opacity: background.value.opacity / 100,
  backgroundImage: `url("${imageUrl.value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}")`,
  backgroundRepeat: background.value.imageFit === 'repeat' ? 'repeat' : 'no-repeat',
  backgroundSize: background.value.imageFit === 'repeat' ? 'auto' : background.value.imageFit,
  backgroundPosition: 'center center'
}))
</script>
