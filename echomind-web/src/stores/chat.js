import { defineStore } from 'pinia'

const DISPLAY_ROLES = new Set(['user', 'assistant', 'system'])

export const useChatStore = defineStore('chat', {
  state: () => ({
    input: '',
    messages: [],
    attachments: [],
    loading: false,
    sessionId: null,
    // 该值表示聊天页跟随当前 Agent 的默认模型；用户手动选择后才覆盖。
    selectedModel: '__agent_default__',
    selectedAgent: 'default',
    thinkingMsgIndex: -1
  }),
  actions: {
    setHistory(sessionId, messages) {
      this.sessionId = sessionId
      this.messages = Array.isArray(messages)
        ? messages.filter(message => DISPLAY_ROLES.has(message?.role))
        : []
      this.attachments = []
      this.input = ''
      this.loading = false
      this.thinkingMsgIndex = -1
    },
    resetSession() {
      this.input = ''
      this.messages = []
      this.attachments = []
      this.loading = false
      this.sessionId = null
      this.thinkingMsgIndex = -1
    }
  }
})
