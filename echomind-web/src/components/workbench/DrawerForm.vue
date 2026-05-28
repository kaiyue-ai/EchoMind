<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    :size="drawerSize"
    direction="rtl"
    class="workbench-drawer"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="drawer-form-body">
      <slot></slot>
    </div>
    <template v-if="$slots.footer" #footer>
      <div class="drawer-form-footer">
        <slot name="footer"></slot>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  size: { type: [String, Number], default: '520px' }
})

defineEmits(['update:modelValue'])

const drawerSize = computed(() => {
  if (typeof props.size === 'number') {
    return `min(100vw, ${props.size}px)`
  }
  const size = props.size || '520px'
  if (/^(min|max|clamp)\(/.test(size)) {
    return size
  }
  return `min(100vw, ${size})`
})
</script>
