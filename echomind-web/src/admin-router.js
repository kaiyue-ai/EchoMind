import { createRouter, createWebHistory } from 'vue-router'
import { useAdminAuthStore } from './stores/adminAuth'

const CHUNK_RELOAD_KEY = 'echomind_admin_chunk_reload_attempted_at'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'AdminDashboard',
    component: () => import('./views/AdminDashboardView.vue'),
    meta: { title: '仪表盘', subtitle: '基于真实客户端调用数据的项目三概览' }
  },
  {
    path: '/login',
    name: 'AdminLogin',
    component: () => import('./views/AdminLoginView.vue'),
    meta: { title: '管理端登录', public: true }
  },
  {
    path: '/traces',
    name: 'AdminTraces',
    component: () => import('./views/TraceView.vue'),
    meta: { title: 'Trace 追踪', subtitle: '查询模型调用的 OpenTelemetry 多 Span 链路' }
  },
  {
    path: '/usage',
    name: 'AdminUsage',
    component: () => import('./views/UsageView.vue'),
    meta: { title: '使用记录', subtitle: '查看和分析客户端用户的 AI 调用历史' }
  },
  {
    path: '/user-tokens',
    name: 'AdminUserTokens',
    component: () => import('./views/UserTokenView.vue'),
    meta: { title: '用户 Token', subtitle: '按客户端用户和模型汇总 Token 消费' }
  },
  {
    path: '/client-users',
    name: 'AdminClientUsers',
    component: () => import('./views/ClientUserManagementView.vue'),
    meta: { title: '用户管理', subtitle: '查询客户端用户，并执行封禁、解封和删除操作' }
  },
  {
    path: '/sensitive',
    name: 'AdminSensitive',
    component: () => import('./views/SensitiveGovernanceView.vue'),
    meta: { title: '脱敏治理', subtitle: '管理敏感数据规则并查看脱敏/阻断事件' }
  },
  {
    path: '/alerts',
    name: 'AdminAlerts',
    component: () => import('./views/AlertCenterView.vue'),
    meta: { title: '告警中心', subtitle: '配置告警规则、飞书推送和静默期' }
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
  const authStore = useAdminAuthStore()
  if (authStore.token && !authStore.user) {
    await authStore.loadCurrentUser()
  }
  if (!to.meta.public && !authStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && authStore.isAuthenticated) {
    return '/dashboard'
  }
  return true
})

export default router
