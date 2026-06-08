<template>
  <Transition name="app-shell" mode="out-in">
    <router-view v-if="route.path === '/login'" key="login" />
    <AdminShell v-else key="admin-shell" />
  </Transition>
</template>

<script setup>
import { useRoute } from 'vue-router'
import AdminShell from './components/workbench/AdminShell.vue'

const route = useRoute()
</script>
