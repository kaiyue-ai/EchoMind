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
    activeSessionId: null,
    error: null
  }),
  actions: {
    async loadSessions() {
      this.loading = true
      this.error = null
      try {
        this.sessions = await api.chat.sessions()
        return this.sessions
      } catch (error) {
        this.sessions = []
        this.error = api.parseError(error, '加载会话历史失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    setActive(sessionId) {
      this.activeSessionId = sessionId || null
    }
  }
})
