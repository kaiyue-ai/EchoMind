<template>
  <ResponsiveShell
    variant="admin"
    :sidebar-collapsed="sidebarCollapsed"
    :mobile-sidebar-open="mobileSidebarOpen"
    sidebar-width="clamp(252px, 22vw, 292px)"
    collapsed-width="82px"
  >
    <template #scrim>
      <Transition name="scrim-fade">
        <div v-if="mobileSidebarOpen" class="admin-mobile-scrim" @click="uiStore.closeMobileSidebar()"></div>
      </Transition>
    </template>

    <aside class="admin-sidebar">
      <div class="admin-brand-row">
        <span class="admin-logo" aria-label="EchoMind Admin">E</span>
        <div class="brand-copy" :aria-hidden="sidebarCollapsed">
          <strong>EchoMind</strong>
          <span>Admin Gateway</span>
        </div>
      </div>

      <nav class="admin-nav" :style="adminNavIndicatorStyle">
        <span class="admin-nav-active-indicator" aria-hidden="true"></span>
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          :class="['admin-nav-item', { active: isActive(item.path) }]"
          :title="item.label"
          @focus="preloadRoute(item.path)"
          @mouseenter="preloadRoute(item.path)"
          @click="uiStore.closeMobileSidebar()"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span class="admin-nav-label" :aria-hidden="sidebarCollapsed">{{ item.label }}</span>
        </router-link>
      </nav>

      <div class="admin-sidebar-spacer"></div>

      <div class="admin-sidebar-tools">
        <AppearancePanel>
          <button type="button" class="admin-tool-row" :title="sidebarCollapsed ? themeToggleLabel : ''">
            <el-icon><component :is="themeIcon" /></el-icon>
            <span class="admin-tool-label" :aria-hidden="sidebarCollapsed">{{ themeLabel }}</span>
          </button>
        </AppearancePanel>
      </div>

      <footer class="admin-sidebar-foot">
        <button
          type="button"
          class="admin-collapse-button"
          :aria-label="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
          :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="uiStore.toggleSidebar()"
        >
          <el-icon><component :is="sidebarCollapsed ? Expand : Fold" /></el-icon>
          <span class="admin-collapse-label" :aria-hidden="sidebarCollapsed">
            {{ sidebarCollapsed ? '展开' : '收起' }}
          </span>
        </button>
      </footer>
    </aside>

    <section class="admin-frame">
      <header class="admin-topbar">
        <button type="button" class="admin-mobile-menu-button" title="打开导航" @click="toggleMobileSidebar">
          <el-icon><Menu /></el-icon>
        </button>
        <div class="admin-page-title">
          <h1>{{ route.meta.title || 'EchoMind Admin' }}</h1>
          <span>{{ route.meta.subtitle || '项目三管理端' }}</span>
        </div>
        <div class="admin-topbar-actions">
          <el-button text class="admin-icon-button" title="通知">
            <el-icon><Bell /></el-icon>
          </el-button>
          <span class="admin-pill admin-lang-pill">CN ZH</span>
          <span class="admin-pill admin-trace-pill">
            <el-icon><DataLine /></el-icon>
            Trace
          </span>
          <span class="admin-balance-pill">
            <el-icon><Coin /></el-icon>
            Token
          </span>
          <AppearancePanel>
            <el-button text class="admin-icon-button admin-theme-button" :title="themeToggleLabel">
              <el-icon><component :is="themeIcon" /></el-icon>
            </el-button>
          </AppearancePanel>
          <button type="button" class="admin-user-menu" @click="logout" title="退出登录">
            <span class="admin-user-avatar">{{ userInitial }}</span>
            <span class="admin-user-copy">
              <strong>{{ authStore.user?.username || 'admin' }}</strong>
              <small>Admin</small>
            </span>
            <el-icon><ArrowDown /></el-icon>
          </button>
        </div>
      </header>

      <main class="admin-content">
        <router-view v-slot="{ Component, route: viewRoute }">
          <transition name="admin-route" mode="out-in">
            <component :is="Component" :key="viewRoute.fullPath" class="admin-route-view" />
          </transition>
        </router-view>
      </main>
    </section>
  </ResponsiveShell>
</template>

<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  Bell,
  Connection,
  DataBoard,
  Coin,
  DataLine,
  Expand,
  Fold,
  Lock,
  Menu,
  Monitor,
  Moon,
  Warning,
  Sunny,
  UserFilled
} from '@element-plus/icons-vue'
import { useAdminAuthStore } from '../../stores/adminAuth'
import { useUiStore } from '../../stores/ui'
import { useRoutePreload } from '../../composables/useRoutePreload'
import AppearancePanel from '../layout/AppearancePanel.vue'
import ResponsiveShell from '../layout/ResponsiveShell.vue'

const route = useRoute()
const router = useRouter()
const uiStore = useUiStore()
const authStore = useAdminAuthStore()
const { mobileSidebarOpen, sidebarCollapsed, isLightTheme, themeLabel, themeToggleLabel } = storeToRefs(uiStore)
const themeIcon = computed(() => isLightTheme.value ? Moon : Sunny)
const { preloadRoute } = useRoutePreload(router)

const navItems = [
  { path: '/dashboard', label: '仪表盘', icon: DataBoard },
  { path: '/usage', label: '使用记录', icon: DataLine },
  { path: '/user-tokens', label: '用户 Token', icon: Coin },
  { path: '/provider-budgets', label: 'Provider 预算', icon: Connection },
  { path: '/client-users', label: '用户管理', icon: UserFilled },
  { path: '/sensitive', label: '脱敏治理', icon: Lock },
  { path: '/alerts', label: '告警中心', icon: Warning },
  { path: '/traces', label: 'Trace 链路', icon: Monitor }
]

const userInitial = computed(() => String(authStore.user?.username || 'A').slice(0, 1).toUpperCase())
const currentNavIndex = computed(() => {
  const index = navItems.findIndex(item => isActive(item.path))
  return index >= 0 ? index : 0
})
const adminNavIndicatorStyle = computed(() => {
  return {
    '--admin-nav-indicator-y': `${16 + currentNavIndex.value * 52}px`
  }
})

function isActive(path) {
  return route.path === path || route.path.startsWith(`${path}/`)
}

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
</script>
