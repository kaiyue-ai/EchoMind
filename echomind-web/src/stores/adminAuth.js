import { defineStore } from 'pinia'
import api, { ADMIN_TOKEN_KEY } from '../api/admin'

export const useAdminAuthStore = defineStore('adminAuth', {
  state: () => ({
    token: localStorage.getItem(ADMIN_TOKEN_KEY),
    user: null,
    loading: false,
    error: null
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token && state.user?.authenticated)
  },
  actions: {
    async login(username, password) {
      this.loading = true
      this.error = null
      try {
        const result = await api.auth.login(username, password)
        this.token = result.token
        this.user = result.user
        localStorage.setItem(ADMIN_TOKEN_KEY, result.token)
        return result
      } catch (e) {
        this.error = api.parseError(e, '登录失败')
        throw e
      } finally {
        this.loading = false
      }
    },
    async loadCurrentUser() {
      if (!this.token) return null
      try {
        this.user = await api.auth.me()
        return this.user
      } catch (e) {
        this.clearSession()
        return null
      }
    },
    async logout() {
      try {
        await api.auth.logout()
      } finally {
        this.clearSession()
      }
    },
    clearSession() {
      this.token = null
      this.user = null
      localStorage.removeItem(ADMIN_TOKEN_KEY)
    }
  }
})
