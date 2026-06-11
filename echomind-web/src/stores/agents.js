import { defineStore } from 'pinia'
import api from '../api'

/**
 * Agent状态中心。
 *
 * 页面只关心展示和交互；Agent列表、创建、知识库等异步状态统一放在这里，
 * 这样从聊天页、团队页、Agent管理页看到的是同一份运行时Agent视图。
 */
export const useAgentStore = defineStore('agents', {
  state: () => ({
    agents: [],
    knowledgeByAgent: {},
    loading: false,
    saving: false,
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
      if (!force && this.agents.length > 0) {
        this.refreshAgents().catch(() => {})
        return this.agents
      }
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
        upsertAgent(this.agents, created)
        this.refreshAgents().catch(() => {})
        return created
      } catch (error) {
        this.error = api.parseError(error, '保存Agent失败')
        throw error
      } finally {
        this.saving = false
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
    },
    async downloadKnowledge(agentId, document) {
      if (!agentId || !document?.id) return
      this.error = null
      try {
        const response = await api.agents.downloadKnowledge(agentId, document.id)
        const disposition = response.headers?.['content-disposition'] || ''
        const filename = filenameFromDisposition(disposition) || document.fileName || 'knowledge-file'
        const url = URL.createObjectURL(response.data)
        const link = window.document.createElement('a')
        link.href = url
        link.download = filename
        window.document.body.appendChild(link)
        link.click()
        link.remove()
        URL.revokeObjectURL(url)
      } catch (error) {
        this.error = api.parseError(error, '下载知识库原文件失败')
        throw error
      }
    },
    async deleteAgent(agentId) {
      this.error = null
      const previousAgents = [...this.agents]
      const previousKnowledge = { ...this.knowledgeByAgent }
      this.agents = this.agents.filter(agent => agent.agentId !== agentId)
      const { [agentId]: _removed, ...nextKnowledge } = this.knowledgeByAgent
      this.knowledgeByAgent = nextKnowledge
      try {
        await api.agents.delete(agentId)
        await this.refreshAgents().catch(() => {})
      } catch (error) {
        this.agents = previousAgents
        this.knowledgeByAgent = previousKnowledge
        this.error = api.parseError(error, '删除Agent失败')
        throw error
      }
    },
    async refreshAgents() {
      this.error = null
      try {
        const agents = await api.agents.list()
        const results = await Promise.allSettled(
          agents.map(agent => api.agents.knowledge(agent.agentId)
            .then(docs => [agent.agentId, docs]))
        )
        const nextKnowledge = { ...this.knowledgeByAgent }
        for (const result of results) {
          if (result.status === 'fulfilled') {
            nextKnowledge[result.value[0]] = result.value[1]
          }
        }
        this.agents = agents
        this.knowledgeByAgent = nextKnowledge
        this.lastLoadedAt = Date.now()
        return this.agents
      } catch (error) {
        this.error = api.parseError(error, '刷新Agent失败')
        throw error
      }
    }
  }
})

function upsertAgent(agents, agent) {
  if (!agent?.agentId) return
  const index = agents.findIndex(item => item.agentId === agent.agentId)
  if (index >= 0) {
    agents.splice(index, 1, agent)
  } else {
    agents.unshift(agent)
  }
}

function filenameFromDisposition(disposition) {
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (encoded?.[1]) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      return encoded[1]
    }
  }
  const plain = disposition.match(/filename="?([^";]+)"?/i)
  return plain?.[1]
}
