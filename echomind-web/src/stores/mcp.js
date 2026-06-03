import { defineStore } from 'pinia'
import api from '../api'

/**
 * 外部 MCP 状态中心。
 *
 * 这里只管理“主项目接入的外部 MCP 服务”。本项目不再把自己的 Skill 暴露成 MCP Server。
 */
export const useMcpStore = defineStore('mcp', {
  state: () => ({
    servers: [],
    loading: false,
    refreshingId: null,
    error: null,
    lastLoadedAt: null
  }),
  actions: {
    async loadMcp(force = false) {
      if (this.loading) return { servers: this.servers }
      if (!force && this.servers.length > 0) {
        this.refreshMcp().catch(() => {})
        return { servers: this.servers }
      }
      this.loading = true
      this.error = null
      try {
        const servers = await api.mcp.servers()
        this.servers = servers
        this.lastLoadedAt = Date.now()
        return { servers }
      } catch (error) {
        this.error = api.parseError(error, '加载外部 MCP 失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async refreshMcp() {
      this.error = null
      try {
        const servers = await api.mcp.servers()
        this.servers = servers
        this.lastLoadedAt = Date.now()
        return { servers }
      } catch (error) {
        this.error = api.parseError(error, '刷新外部 MCP 失败')
        throw error
      }
    },
    async refreshServer(serverId) {
      this.refreshingId = serverId
      this.error = null
      try {
        const server = await api.mcp.refreshServer(serverId)
        this.servers = this.servers.map(item => item.id === server.id ? server : item)
        await this.refreshMcp().catch(() => {})
        return server
      } catch (error) {
        this.error = api.parseError(error, '刷新 MCP 服务失败')
        throw error
      } finally {
        this.refreshingId = null
      }
    }
  }
})
