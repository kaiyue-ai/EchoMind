import { defineStore } from 'pinia'
import api from '../api'

/**
 * Agent状态中心。
 *
 * 页面只关心展示和交互；Agent列表、创建、测试执行等异步状态统一放在这里，
 * 这样从聊天页、团队页、Agent管理页看到的是同一份运行时Agent视图。
 */
export const useAgentStore = defineStore('agents', {
  state: () => ({
    agents: [],
    knowledgeByAgent: {},
    loading: false,
    saving: false,
    testing: false,
    knowledgeLoading: false,
    knowledgeUploading: false,
    error: null,
    lastLoadedAt: null
  }),
  getters: {
    options: (state) => state.agents.map(agent => ({
      label: agent.name,
      value: agent.agentId
    }))
  },
  actions: {
    async loadAgents(force = false) {
      if (this.loading) return this.agents
      if (!force && this.agents.length > 0) return this.agents
      this.loading = true
      this.error = null
      try {
        this.agents = await api.agents.list()
        const results = await Promise.allSettled(
          this.agents.map(agent => api.agents.knowledge(agent.agentId)
            .then(docs => [agent.agentId, docs]))
        )
        const nextKnowledge = { ...this.knowledgeByAgent }
        for (const result of results) {
          if (result.status === 'fulfilled') {
            nextKnowledge[result.value[0]] = result.value[1]
          }
        }
        this.knowledgeByAgent = nextKnowledge
        this.lastLoadedAt = Date.now()
        return this.agents
      } catch (error) {
        this.error = api.parseError(error, '加载Agent失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async createAgent(config) {
      this.saving = true
      this.error = null
      try {
        const created = await api.agents.create(config)
        await this.loadAgents(true)
        return created
      } catch (error) {
        this.error = api.parseError(error, '保存Agent失败')
        throw error
      } finally {
        this.saving = false
      }
    },
    async executeAgent(agentId, message, sessionId) {
      this.testing = true
      this.error = null
      try {
        return await api.agents.execute(agentId, message, sessionId)
      } catch (error) {
        this.error = api.parseError(error, '执行Agent失败')
        throw error
      } finally {
        this.testing = false
      }
    },
    async loadKnowledge(agentId) {
      if (!agentId) return []
      this.knowledgeLoading = true
      this.error = null
      try {
        const docs = await api.agents.knowledge(agentId)
        this.knowledgeByAgent = { ...this.knowledgeByAgent, [agentId]: docs }
        return docs
      } catch (error) {
        this.error = api.parseError(error, '加载知识库失败')
        throw error
      } finally {
        this.knowledgeLoading = false
      }
    },
    async uploadKnowledge(agentId, file) {
      this.knowledgeUploading = true
      this.error = null
      try {
        const doc = await api.agents.uploadKnowledge(agentId, file)
        await this.loadKnowledge(agentId)
        return doc
      } catch (error) {
        this.error = api.parseError(error, '上传知识库失败')
        throw error
      } finally {
        this.knowledgeUploading = false
      }
    },
    async deleteKnowledge(agentId, documentId) {
      this.knowledgeLoading = true
      this.error = null
      try {
        await api.agents.deleteKnowledge(agentId, documentId)
        await this.loadKnowledge(agentId)
      } catch (error) {
        this.error = api.parseError(error, '删除知识库失败')
        throw error
      } finally {
        this.knowledgeLoading = false
      }
    }
  }
})
