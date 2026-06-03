<template>
  <div class="page-skeleton" aria-hidden="true">
    <div class="page-skeleton-header">
      <el-skeleton animated :rows="2" />
      <div class="page-skeleton-actions">
        <span></span>
        <span></span>
      </div>
    </div>
    <div class="page-skeleton-grid">
      <article v-for="item in 6" :key="item" class="page-skeleton-card">
        <el-skeleton animated :rows="5" />
      </article>
    </div>
  </div>
</template>
