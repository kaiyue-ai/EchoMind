import axios from 'axios'
import { ElMessage } from 'element-plus'

export const ADMIN_TOKEN_KEY = 'echomind_admin_auth_token'

const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

http.interceptors.request.use(config => {
  const token = localStorage.getItem(ADMIN_TOKEN_KEY)
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function parseError(err, fallback = '请求失败') {
  return err.response?.data?.error || err.response?.data?.message || err.message || fallback
}

http.interceptors.response.use(
  res => res,
  err => {
    ElMessage.error(parseError(err))
    return Promise.reject(err)
  }
)

export default {
  parseError,
  auth: {
    login: (username, password) =>
      http.post('/admin/auth/login', { username, password }).then(r => r.data),
    me: () => http.get('/admin/auth/me').then(r => r.data),
    logout: () => http.post('/admin/auth/logout').then(r => r.data)
  },
  observability: {
    traceConfig: () => http.get('/observability/traces/config').then(r => r.data),
    traces: (params = {}) => http.get('/observability/traces', { params }).then(r => r.data),
    trace: (traceId) => http.get(`/observability/traces/${traceId}`).then(r => r.data)
  },
  dashboard: {
    overview: (params = {}) => http.get('/admin/dashboard', { params }).then(r => r.data)
  },
  usage: {
    summary: () => http.get('/admin/usage/summary').then(r => r.data),
    calls: (params = {}) => http.get('/admin/usage/calls', { params }).then(r => r.data),
    users: () => http.get('/admin/usage/users').then(r => r.data),
    userModelTokens: () => http.get('/admin/usage/user-model-tokens').then(r => r.data),
    userCalls: (userId, params = {}) => http.get(`/admin/usage/users/${userId}/calls`, { params }).then(r => r.data)
  },
  quotas: {
    list: () => http.get('/admin/quotas').then(r => r.data),
    update: (userId, payload) => http.put(`/admin/quotas/users/${userId}`, payload).then(r => r.data)
  },
  providerBudgets: {
    list: () => http.get('/admin/provider-token-budgets').then(r => r.data),
    update: (payload) => http.put('/admin/provider-token-budgets', payload).then(r => r.data)
  },
  sensitive: {
    rules: () => http.get('/admin/sensitive/rules').then(r => r.data),
    updateRules: (payload) => http.put('/admin/sensitive/rules', payload).then(r => r.data),
    events: (params = {}) => http.get('/admin/sensitive/events', { params }).then(r => r.data)
  },
  alerts: {
    rules: () => http.get('/admin/alerts/rules').then(r => r.data),
    updateRules: (payload) => http.put('/admin/alerts/rules', payload).then(r => r.data),
    events: (params = {}) => http.get('/admin/alerts/events', { params }).then(r => r.data)
  },
  clientUsers: {
    list: () => http.get('/admin/client-users').then(r => r.data),
    updateStatus: (userId, status) =>
      http.put(`/admin/client-users/${userId}/status`, { status }).then(r => r.data),
    delete: (userId) => http.delete(`/admin/client-users/${userId}`).then(r => r.data)
  }
}
