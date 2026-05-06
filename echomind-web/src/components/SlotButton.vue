<template>
  <button
    :class="['slot-btn', variant]"
    @mouseenter="hover = true"
    @mouseleave="hover = false"
  >
    <span class="slot-label" :class="{ up: hover }">{{ label }}</span>
    <span class="slot-label slot-shadow" :class="{ up: hover }" aria-hidden="true">{{ label }}</span>
  </button>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  label: { type: String, required: true },
  variant: { type: String, default: 'outline' },
})
const hover = ref(false)
</script>

<style scoped>
.slot-btn {
  position: relative;
  height: 36px;
  padding: 0 16px;
  border: none;
  cursor: pointer;
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.3px;
  transition: all 0.2s;
}
.slot-btn.outline {
  background: transparent;
  border: 1px solid #27272a;
  color: #a1a1aa;
  border-radius: 6px;
}
.slot-btn.outline:hover {
  border-color: #52525b;
  color: #e4e4e7;
}
.slot-btn.filled {
  background: #18181b;
  color: #a1a1aa;
  border-radius: 6px;
}
.slot-btn.filled:hover {
  background: #27272a;
  color: #e4e4e7;
}
.slot-label {
  display: block;
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.slot-label.up {
  transform: translateY(-140%);
}
.slot-shadow {
  position: absolute;
  top: 0;
  left: 16px;
  right: 16px;
  line-height: 36px;
  transform: translateY(140%);
}
.slot-shadow.up {
  transform: translateY(-140%);
}
</style>
