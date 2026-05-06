<template>
  <el-container class="app-container">
    <el-aside :width="isCollapse ? '64px' : '220px'" class="app-aside">
      <div class="logo" @click="$router.push('/')">
        <span v-if="!isCollapse" class="logo-text">ECHOMIND</span>
        <span v-else class="logo-icon">EM</span>
      </div>

      <el-menu
        :default-active="currentRoute"
        :collapse="isCollapse"
        router
        class="nav-menu"
      >
        <el-menu-item index="/chat">
          <el-icon><ChatDotRound /></el-icon>
          <span>智能对话</span>
        </el-menu-item>
        <el-menu-item index="/skills">
          <el-icon><Grid /></el-icon>
          <span>Skill 市场</span>
        </el-menu-item>
        <el-menu-item index="/agents">
          <el-icon><UserFilled /></el-icon>
          <span>Agent 管理</span>
        </el-menu-item>
        <el-menu-item index="/memory">
          <el-icon><Notebook /></el-icon>
          <span>记忆管理</span>
        </el-menu-item>
        <el-menu-item index="/mcp">
          <el-icon><Connection /></el-icon>
          <span>MCP 工具</span>
        </el-menu-item>
        <el-menu-item index="/team">
          <el-icon><Cpu /></el-icon>
          <span>Agent Team</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-footer">
        <div v-if="!isCollapse" class="status-row">
          <span class="status-dot"></span>
          <span class="status-text">System Online</span>
        </div>
        <el-button
          :icon="isCollapse ? 'Expand' : 'Fold'"
          text
          @click="isCollapse = !isCollapse"
          class="collapse-btn"
        />
      </div>
    </el-aside>

    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  ChatDotRound, Grid, UserFilled, Notebook, Connection, Cpu,
  Fold, Expand
} from '@element-plus/icons-vue'

const route = useRoute()
const isCollapse = ref(false)
const currentRoute = computed(() => route.path)
</script>

<style scoped>
.app-container {
  height: 100vh;
  overflow: hidden;
}
.app-aside {
  background: #0a0a0a;
  display: flex;
  flex-direction: column;
  transition: width 0.3s;
  overflow: hidden;
  border-right: 1px solid #1a1a1a;
}
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-bottom: 1px solid #1a1a1a;
}
.logo-text {
  font-family: 'Courier New', 'JetBrains Mono', monospace;
  font-size: 20px;
  font-weight: 700;
  color: #ffffff;
  letter-spacing: 2px;
}
.logo-icon {
  font-family: 'Courier New', 'JetBrains Mono', monospace;
  font-size: 20px;
  font-weight: 700;
  color: #ffffff;
}
.nav-menu {
  flex: 1;
  border-right: none;
  background: transparent;
}
.nav-menu :deep(.el-menu-item) {
  color: #a1a1aa;
  background: transparent;
  transition: all 0.2s;
}
.nav-menu :deep(.el-menu-item:hover) {
  background: #111111;
  color: #e4e4e7;
}
.nav-menu :deep(.el-menu-item.is-active) {
  background: #111111;
  color: #ffffff;
  border-left: 3px solid #ffffff;
}
.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid #1a1a1a;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.status-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 6px rgba(34, 197, 94, 0.6);
  animation: pulse 2s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}
.status-text {
  font-size: 12px;
  color: #71717a;
  letter-spacing: 0.5px;
}
.collapse-btn {
  color: #71717a !important;
}
.collapse-btn:hover {
  color: #e4e4e7 !important;
}
.app-main {
  background: #030303;
  padding: 0;
  overflow-y: auto;
}
</style>
