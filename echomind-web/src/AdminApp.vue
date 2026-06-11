<template>
  <el-config-provider :locale="zhCn">
    <Transition name="app-shell" mode="out-in">
      <router-view v-if="route.path === '/login'" key="login" />
      <AdminShell v-else key="admin-shell" />
    </Transition>
  </el-config-provider>
</template>

<script setup>
import { useRoute } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import AdminShell from './components/workbench/AdminShell.vue'

const route = useRoute()
</script>
