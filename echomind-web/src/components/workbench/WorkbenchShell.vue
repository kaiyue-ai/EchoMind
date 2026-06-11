<template>
  <ResponsiveShell
    variant="client"
    :sidebar-collapsed="sidebarCollapsed"
    :mobile-sidebar-open="mobileSidebarOpen"
    sidebar-width="clamp(248px, 22vw, 284px)"
    collapsed-width="72px"
  >
    <header class="mobile-workbench-bar">
      <button type="button" class="mobile-menu-button" title="打开导航" @click="toggleMobileSidebar">
        <el-icon><Menu /></el-icon>
      </button>
      <div class="mobile-brand-copy">
        <strong>EchoMind</strong>
        <span>Agent Workbench</span>
      </div>
      <AppearancePanel>
        <el-button text :title="themeToggleLabel">
          <el-icon><component :is="themeIcon" /></el-icon>
        </el-button>
      </AppearancePanel>
      <el-button text title="退出登录" @click="logout">
        <el-icon><SwitchButton /></el-icon>
      </el-button>
    </header>

    <template #scrim>
      <Transition name="scrim-fade">
        <div v-if="mobileSidebarOpen" class="mobile-sidebar-scrim" @click="uiStore.closeMobileSidebar()"></div>
      </Transition>
    </template>

    <aside class="workbench-sidebar">
      <div class="brand-row">
        <UserAvatarButton />
        <div class="brand-copy" :aria-hidden="sidebarCollapsed">
          <strong>EchoMind</strong>
          <span>Agent Workbench</span>
        </div>
      </div>

      <nav class="workbench-nav" :style="navIndicatorStyle">
        <span class="nav-active-indicator" aria-hidden="true"></span>
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          :class="['nav-item', { active: currentRoute === item.path }]"
          :title="item.label"
          @focus="preloadRoute(item.path)"
          @mouseenter="preloadRoute(item.path)"
          @click="uiStore.closeMobileSidebar()"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span class="nav-label" :aria-hidden="sidebarCollapsed">{{ item.label }}</span>
        </router-link>
      </nav>

      <SessionList
        v-if="!sidebarCollapsed"
        :sessions="sessions"
        :loading="sessionsLoading"
        :deleting-id="deletingSessionId"
        :active-session-id="activeSessionId"
        @refresh="emit('refreshSessions')"
        @create="handleNewSession"
        @open="handleOpenSession"
        @delete="emit('deleteSession', $event)"
      />

      <button v-else class="collapsed-session-button" type="button" title="展开会话" @click="uiStore.setSidebarCollapsed(false)">
        <el-icon><ChatLineSquare /></el-icon>
      </button>

      <footer class="sidebar-foot">
        <div v-if="!sidebarCollapsed" class="system-status">
          <span class="live-dot"></span>
          <span>{{ authStore.user?.username || 'System Online' }}</span>
        </div>
        <AppearancePanel>
          <el-button text :title="themeToggleLabel">
            <el-icon><component :is="themeIcon" /></el-icon>
          </el-button>
        </AppearancePanel>
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
        <Transition name="route-soft" mode="out-in">
          <keep-alive include="ChatView">
            <component :is="Component" :key="route.meta.keepAlive ? route.name : route.fullPath" />
          </keep-alive>
        </Transition>
      </router-view>
    </main>
  </ResponsiveShell>
</template>

<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import {
  Connection,
  Cpu,
  ChatDotRound,
  ChatLineSquare,
  Expand,
  Fold,
  Grid,
  Menu,
  Moon,
  Sunny,
  SwitchButton,
  UserFilled
} from '@element-plus/icons-vue'
import { useUiStore } from '../../stores/ui'
import { useAuthStore } from '../../stores/auth'
import { useRoutePreload } from '../../composables/useRoutePreload'
import AppearancePanel from '../layout/AppearancePanel.vue'
import ResponsiveShell from '../layout/ResponsiveShell.vue'
import SessionList from './SessionList.vue'
import UserAvatarButton from './UserAvatarButton.vue'

defineProps({
  sessions: { type: Array, default: () => [] },
  sessionsLoading: { type: Boolean, default: false },
  deletingSessionId: { type: String, default: null },
  activeSessionId: { type: String, default: null }
})

const route = useRoute()
const router = useRouter()
const uiStore = useUiStore()
const authStore = useAuthStore()
const { mobileSidebarOpen, sidebarCollapsed, isLightTheme, themeToggleLabel } = storeToRefs(uiStore)
const currentRoute = computed(() => route.path)
const themeIcon = computed(() => isLightTheme.value ? Moon : Sunny)
const emit = defineEmits(['refreshSessions', 'newSession', 'openSession', 'deleteSession'])
const { preloadRoute } = useRoutePreload(router)

const navItems = [
  { path: '/chat', label: '对话', icon: ChatDotRound },
  { path: '/agents', label: 'Agents', icon: UserFilled },
  { path: '/skills', label: 'Skills', icon: Grid },
  { path: '/mcp', label: 'MCP', icon: Connection },
  { path: '/team', label: 'Team', icon: Cpu }
]

const currentNavIndex = computed(() => {
  const index = navItems.findIndex(item => currentRoute.value === item.path || currentRoute.value.startsWith(`${item.path}/`))
  return index >= 0 ? index : 0
})

const navIndicatorStyle = computed(() => {
  const gap = sidebarCollapsed.value ? 14 : 8
  const top = sidebarCollapsed.value ? 18 : 14
  return {
    '--nav-indicator-y': `${top + currentNavIndex.value * (44 + gap)}px`
  }
})

async function logout() {
  await authStore.logout()
  router.replace('/login')
}

function toggleMobileSidebar() {
  if (!mobileSidebarOpen.value) {
    uiStore.setSidebarCollapsed(false)
  }
  uiStore.toggleMobileSidebar()
}

function handleNewSession() {
  emit('newSession')
  uiStore.closeMobileSidebar()
}

function handleOpenSession(sessionId) {
  emit('openSession', sessionId)
  uiStore.closeMobileSidebar()
}
</script>
