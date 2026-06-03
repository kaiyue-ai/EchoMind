<template>
  <div class="resource-grid" aria-hidden="true">
    <article v-for="item in count" :key="item" class="resource-card resource-card-skeleton">
      <el-skeleton animated :rows="rows" />
    </article>
  </div>
</template>

<script setup>
defineProps({
  count: { type: Number, default: 6 },
  rows: { type: Number, default: 5 }
})
</script>
