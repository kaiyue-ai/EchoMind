<template>
  <article class="resource-card">
    <header v-if="$slots.title || title || meta || $slots.actions" class="resource-card-head">
      <div class="resource-card-title-block">
        <div v-if="$slots.title || title" class="resource-card-title">
          <slot name="title">{{ title }}</slot>
        </div>
        <div v-if="meta" class="resource-card-meta">{{ meta }}</div>
      </div>
      <div v-if="$slots.actions" class="resource-card-actions">
        <slot name="actions"></slot>
      </div>
    </header>
    <div v-if="$slots.default" class="resource-card-body">
      <slot></slot>
    </div>
    <footer v-if="$slots.footer" class="resource-card-footer">
      <slot name="footer"></slot>
    </footer>
  </article>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  meta: { type: String, default: '' }
})
</script>
