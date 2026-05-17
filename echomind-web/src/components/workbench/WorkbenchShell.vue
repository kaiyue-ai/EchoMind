<template>
  <div :class="['workbench-shell', { 'sidebar-collapsed': sidebarCollapsed }]">
    <aside class="workbench-sidebar">
      <div class="brand-row" @click="goHome">
        <div class="brand-mark miku-mark" aria-label="初音未来头像">
          <span class="miku-hair left"></span>
          <span class="miku-hair right"></span>
          <span class="miku-face"></span>
        </div>
        <div v-if="!sidebarCollapsed" class="brand-copy">
          <strong>EchoMind</strong>
          <span>Agent Workbench</span>
        </div>
      </div>

      <nav class="workbench-nav">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          :class="['nav-item', { active: currentRoute === item.path }]"
          :title="item.label"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span v-if="!sidebarCollapsed">{{ item.label }}</span>
        </router-link>
      </nav>

      <SessionList
        v-if="!sidebarCollapsed"
        :sessions="sessions"
        :loading="sessionsLoading"
        :deleting-id="deletingSessionId"
        :active-session-id="activeSessionId"
        @refresh="$emit('refreshSessions')"
        @create="$emit('newSession')"
        @open="$emit('openSession', $event)"
        @delete="$emit('deleteSession', $event)"
      />

      <button v-else class="collapsed-session-button" type="button" title="展开会话" @click="uiStore.setSidebarCollapsed(false)">
        <el-icon><ChatLineSquare /></el-icon>
      </button>

      <footer class="sidebar-foot">
        <div v-if="!sidebarCollapsed" class="system-status">
          <span class="live-dot"></span>
          <span>{{ authStore.user?.username || 'System Online' }}</span>
        </div>
        <el-button text title="退出登录" @click="logout">
          <el-icon><SwitchButton /></el-icon>
        </el-button>
        <el-button text :title="sidebarCollapsed ? '展开侧栏' : '折叠侧栏'" @click="uiStore.toggleSidebar()">
          <el-icon><component :is="sidebarCollapsed ? Expand : Fold" /></el-icon>
        </el-button>
      </footer>
    </aside>

    <main class="workbench-main">
      <router-view v-slot="{ Component, route }">
        <keep-alive include="ChatView">
          <component :is="Component" :key="route.meta.keepAlive ? route.name : route.fullPath" />
        </keep-alive>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import {
  ChatDotRound,
  ChatLineSquare,
  Connection,
  Cpu,
  Expand,
  Fold,
  Grid,
  SwitchButton,
  UserFilled
} from '@element-plus/icons-vue'
import { useUiStore } from '../../stores/ui'
import { useAuthStore } from '../../stores/auth'
import SessionList from './SessionList.vue'

defineProps({
  sessions: { type: Array, default: () => [] },
  sessionsLoading: { type: Boolean, default: false },
  deletingSessionId: { type: String, default: null },
  activeSessionId: { type: String, default: null }
})

defineEmits(['refreshSessions', 'newSession', 'openSession', 'deleteSession'])

const route = useRoute()
const router = useRouter()
const uiStore = useUiStore()
const authStore = useAuthStore()
const { sidebarCollapsed } = storeToRefs(uiStore)
const currentRoute = computed(() => route.path)

const navItems = [
  { path: '/chat', label: '对话', icon: ChatDotRound },
  { path: '/agents', label: 'Agents', icon: UserFilled },
  { path: '/skills', label: 'Skills', icon: Grid },
  { path: '/mcp', label: 'MCP', icon: Connection },
  { path: '/team', label: 'Team', icon: Cpu }
]

function goHome() {
  router.push('/chat')
}

async function logout() {
  await authStore.logout()
  router.replace('/login')
}
</script>
