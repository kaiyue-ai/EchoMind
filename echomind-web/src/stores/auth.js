import { defineStore } from 'pinia'
import api from '../api'
import { useChatStore } from './chat'
import { useSessionStore } from './sessions'

const TOKEN_KEY = 'echomind_auth_token'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY),
    user: null,
    loading: false,
    error: null
  }),
  getters: {
    hasSession: (state) => Boolean(state.token),
    isAuthenticated: (state) => Boolean(state.token && state.user?.authenticated)
  },
  actions: {
    async login(username, password) {
      this.loading = true
      this.error = null
      try {
        const result = await api.auth.login(username, password)
        this.setSession(result.token, result.user)
        return result
      } catch (e) {
        this.error = api.parseError(e, '登录失败')
        throw e
      } finally {
        this.loading = false
      }
    },
    async register(username, password) {
      this.loading = true
      this.error = null
      try {
        const result = await api.auth.register(username, password)
        this.setSession(result.token, result.user)
        return result
      } catch (e) {
        this.error = api.parseError(e, '注册失败')
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
    async uploadAvatar(file) {
      this.loading = true
      this.error = null
      try {
        this.user = await api.auth.uploadAvatar(file)
        return this.user
      } catch (e) {
        this.error = api.parseError(e, '头像上传失败')
        throw e
      } finally {
        this.loading = false
      }
    },
    setSession(token, user) {
      resetUserScopedStores()
      this.token = token
      this.user = user
      if (token) localStorage.setItem(TOKEN_KEY, token)
    },
    clearSession() {
      resetUserScopedStores()
      this.token = null
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})

function resetUserScopedStores() {
  useChatStore().resetSession()
  useSessionStore().reset()
}
