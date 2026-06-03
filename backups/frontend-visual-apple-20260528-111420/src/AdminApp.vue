<template>
  <router-view v-if="route.path === '/login'" />
  <AdminShell v-else />
</template>

<script setup>
import { useRoute } from 'vue-router'
import AdminShell from './components/workbench/AdminShell.vue'

const route = useRoute()
</script>
