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
    tools: [],
    loading: false,
    calling: false,
    mounting: false,
    refreshingId: null,
    mutatingId: null,
    error: null,
    lastLoadedAt: null
  }),
  actions: {
    async loadMcp(force = false) {
      if (this.loading) return { servers: this.servers, tools: this.tools }
      if (!force && (this.servers.length > 0 || this.tools.length > 0)) {
        this.refreshMcp().catch(() => {})
        return { servers: this.servers, tools: this.tools }
      }
      this.loading = true
      this.error = null
      try {
        const [servers, tools] = await Promise.all([
          api.mcp.servers(),
          api.mcp.tools()
        ])
        this.servers = servers
        this.tools = tools
        this.lastLoadedAt = Date.now()
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
        const [servers, tools] = await Promise.all([
          api.mcp.servers(),
          api.mcp.tools()
        ])
        this.servers = servers
        this.tools = tools
        this.lastLoadedAt = Date.now()
        return { servers, tools }
      } catch (error) {
        this.error = api.parseError(error, '刷新外部 MCP 失败')
        throw error
      }
    },
    async mountServer(config) {
      this.mounting = true
      this.error = null
      const previousServers = [...this.servers]
      const optimisticServer = {
        ...config,
        running: false,
        toolCount: 0,
        tools: [],
        mountedAt: new Date().toISOString(),
        error: '正在挂载...'
      }
      this.servers = [optimisticServer, ...this.servers.filter(server => server.id !== config.id)]
      try {
        const server = await api.mcp.mountServer(config)
        this.servers = [server, ...this.servers.filter(item => item.id !== server.id)]
        await this.refreshMcp().catch(() => {})
        return server
      } catch (error) {
        this.servers = previousServers
        this.error = api.parseError(error, '挂载 MCP 服务失败')
        throw error
      } finally {
        this.mounting = false
      }
    },
    async unmountServer(serverId) {
      this.mutatingId = serverId
      this.error = null
      const previousServers = [...this.servers]
      const previousTools = [...this.tools]
      const server = this.servers.find(item => item.id === serverId)
      this.servers = this.servers.filter(item => item.id !== serverId)
      if (server?.tools?.length) {
        const names = new Set(server.tools.map(tool => tool.name))
        this.tools = this.tools.filter(tool => !names.has(tool.name))
      }
      try {
        const removed = await api.mcp.unmountServer(serverId)
        await this.refreshMcp().catch(() => {})
        return removed
      } catch (error) {
        this.servers = previousServers
        this.tools = previousTools
        this.error = api.parseError(error, '卸载 MCP 服务失败')
        throw error
      } finally {
        this.mutatingId = null
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
    },
    async callTool(toolName, args) {
      this.calling = true
      this.error = null
      try {
        return await api.mcp.callTool(toolName, args)
      } catch (error) {
        this.error = api.parseError(error, '调用 MCP 工具失败')
        throw error
      } finally {
        this.calling = false
      }
    }
  }
})
