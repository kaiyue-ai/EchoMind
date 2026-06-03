<template>
  <span class="scramble-text" :style="{ fontFamily: mono ? 'JetBrains Mono, monospace' : undefined }">
    {{ display }}
    <span class="scramble-cursor" v-if="!done">_</span>
  </span>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  text: { type: String, required: true },
  mono: { type: Boolean, default: false },
  speed: { type: Number, default: 40 },
})

const CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*'
const done = ref(false)
const display = ref('')
let timer = null

function scramble() {
  let i = 0
  const target = props.text
  timer = setInterval(() => {
    if (i >= target.length) {
      clearInterval(timer)
      display.value = target
      done.value = true
      return
    }
    let s = target.substring(0, i)
    for (let j = i; j < Math.min(i + 3, target.length); j++) {
      s += CHARS[Math.floor(Math.random() * CHARS.length)]
    }
    display.value = s
    i++
  }, props.speed)
}

onMounted(scramble)
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.scramble-cursor {
  animation: blink 0.6s steps(1) infinite;
}
@keyframes blink {
  50% { opacity: 0; }
}
</style>
