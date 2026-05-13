<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    :size="size"
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
defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  size: { type: String, default: '520px' }
})

defineEmits(['update:modelValue'])
</script>
