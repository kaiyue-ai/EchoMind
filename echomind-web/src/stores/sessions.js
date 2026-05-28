import { defineStore } from 'pinia'
import api from '../api'

/**
 * 会话历史状态中心。
 *
 * 左侧历史列表和聊天页发送完成后的刷新都走这里，App.vue不再直接发请求。
 */
export const useSessionStore = defineStore('sessions', {
  state: () => ({
    sessions: [],
    loading: false,
    deletingId: null,
    activeSessionId: null,
    error: null,
    lastLoadedAt: null
  }),
  actions: {
    async loadSessions(force = false) {
      if (this.loading) return this.sessions
      if (!force && this.sessions.length > 0) {
        this.refreshSessions().catch(() => {})
        return this.sessions
      }
      this.loading = true
      this.error = null
      try {
        this.sessions = await api.chat.sessions()
        this.lastLoadedAt = Date.now()
        return this.sessions
      } catch (error) {
        this.error = api.parseError(error, '加载会话历史失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async refreshSessions() {
      this.error = null
      try {
        this.sessions = await api.chat.sessions()
        this.lastLoadedAt = Date.now()
        return this.sessions
      } catch (error) {
        this.error = api.parseError(error, '刷新会话历史失败')
        throw error
      }
    },
    async deleteSession(sessionId) {
      if (!sessionId) return null
      this.deletingId = sessionId
      this.error = null
      const previousSessions = [...this.sessions]
      const previousActiveSessionId = this.activeSessionId
      this.sessions = this.sessions.filter(session => session.sessionId !== sessionId)
      if (this.activeSessionId === sessionId) {
        this.activeSessionId = null
      }
      try {
        const result = await api.chat.delete(sessionId)
        await this.refreshSessions().catch(() => {})
        return result
      } catch (error) {
        this.sessions = previousSessions
        this.activeSessionId = previousActiveSessionId
        this.error = api.parseError(error, '删除会话失败')
        throw error
      } finally {
        this.deletingId = null
      }
    },
    setActive(sessionId) {
      this.activeSessionId = sessionId || null
    },
    reset() {
      this.sessions = []
      this.loading = false
      this.deletingId = null
      this.activeSessionId = null
      this.error = null
      this.lastLoadedAt = null
    }
  }
})
