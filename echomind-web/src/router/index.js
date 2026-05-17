import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const CHUNK_RELOAD_KEY = 'echomind_chunk_reload_attempted_at'

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('../views/ChatView.vue'),
    meta: { title: '智能对话', keepAlive: true }
  },
  {
    path: '/skills',
    name: 'Skills',
    component: () => import('../views/SkillsView.vue'),
    meta: { title: 'Skill 市场' }
  },
  {
    path: '/agents',
    name: 'Agents',
    component: () => import('../views/AgentsView.vue'),
    meta: { title: 'Agent 管理' }
  },

  {
    path: '/mcp',
    name: 'MCP',
    component: () => import('../views/McpView.vue'),
    meta: { title: 'MCP 工具' }
  },
  {
    path: '/team',
    name: 'Team',
    component: () => import('../views/TeamDashboard.vue'),
    meta: { title: 'Agent Team' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.onError((error) => {
  const message = String(error?.message || error || '')
  if (message.includes('Failed to fetch dynamically imported module')
    || message.includes('Importing a module script failed')) {
    const lastAttempt = Number(sessionStorage.getItem(CHUNK_RELOAD_KEY) || 0)
    if (Date.now() - lastAttempt < 30000) {
      return
    }
    sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now()))
    window.location.reload()
  }
})

router.beforeEach(async (to) => {
  const authStore = useAuthStore()
  if (authStore.token && !authStore.user) {
    await authStore.loadCurrentUser()
  }
  if (!to.meta.public && !authStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && authStore.isAuthenticated) {
    return '/chat'
  }
  return true
})

export default router
