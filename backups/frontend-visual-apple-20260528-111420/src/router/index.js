import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { lazyView } from './lazyView'

const CHUNK_RELOAD_KEY = 'echomind_chunk_reload_attempted_at'
let authCheckPromise = null

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/login',
    name: 'Login',
    component: lazyView(() => import('../views/LoginView.vue'), 'LoginView'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/chat',
    name: 'Chat',
    component: lazyView(() => import('../views/ChatView.vue'), 'ChatView'),
    meta: { title: '智能对话', keepAlive: true }
  },
  {
    path: '/skills',
    name: 'Skills',
    component: lazyView(() => import('../views/SkillsView.vue'), 'SkillsView'),
    meta: { title: 'Skill 市场' }
  },
  {
    path: '/agents',
    name: 'Agents',
    component: lazyView(() => import('../views/AgentsView.vue'), 'AgentsView'),
    meta: { title: 'Agent 管理' }
  },
  {
    path: '/mcp',
    name: 'MCP',
    component: lazyView(() => import('../views/McpView.vue'), 'McpView'),
    meta: { title: 'MCP 工具' }
  },
  {
    path: '/team',
    name: 'Team',
    component: lazyView(() => import('../views/TeamDashboard.vue'), 'TeamDashboard'),
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
  if (!to.meta.public && !authStore.hasSession) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (authStore.hasSession && !authStore.user) {
    verifySessionInBackground(authStore, to.fullPath)
  }
  if (to.path === '/login' && authStore.hasSession) {
    return '/chat'
  }
  return true
})

function verifySessionInBackground(authStore, redirectPath) {
  if (authCheckPromise) return
  authCheckPromise = authStore.loadCurrentUser()
    .then((user) => {
      if (!user && router.currentRoute.value.path !== '/login') {
        router.replace({ path: '/login', query: { redirect: redirectPath } })
      }
    })
    .finally(() => {
      authCheckPromise = null
    })
}

export default router
