import { defineStore } from 'pinia'

export const useChatStore = defineStore('chat', {
  state: () => ({
    input: '',
    messages: [],
    attachments: [],
    loading: false,
    sessionId: null,
    selectedModel: 'deepseek:deepseek-v4-flash',
    selectedAgent: 'default',
    thinkingMsgIndex: -1
  }),
  actions: {
    setHistory(sessionId, messages) {
      this.sessionId = sessionId
      this.messages = Array.isArray(messages) ? messages : []
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
