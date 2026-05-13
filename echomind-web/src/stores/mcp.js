import { defineStore } from 'pinia'
import api from '../api'

/**
 * 外部MCP状态中心。
 *
 * 这里只管理“主项目接入的外部MCP服务”。本项目不再把自己的Skill暴露成MCP Server。
 */
export const useMcpStore = defineStore('mcp', {
  state: () => ({
    servers: [],
    tools: [],
    loading: false,
    calling: false,
    mounting: false,
    error: null
  }),
  actions: {
    async loadMcp() {
      this.loading = true
      this.error = null
      try {
        const [servers, tools] = await Promise.all([
          api.mcp.servers(),
          api.mcp.tools()
        ])
        this.servers = servers
        this.tools = tools
      } catch (error) {
        this.error = api.parseError(error, '加载外部MCP失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async mountServer(config) {
      this.mounting = true
      this.error = null
      try {
        const server = await api.mcp.mountServer(config)
        await this.loadMcp()
        return server
      } catch (error) {
        this.error = api.parseError(error, '挂载MCP服务失败')
        throw error
      } finally {
        this.mounting = false
      }
    },
    async unmountServer(serverId) {
      this.loading = true
      this.error = null
      try {
        const server = await api.mcp.unmountServer(serverId)
        await this.loadMcp()
        return server
      } catch (error) {
        this.error = api.parseError(error, '卸载MCP服务失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async refreshServer(serverId) {
      this.loading = true
      this.error = null
      try {
        const server = await api.mcp.refreshServer(serverId)
        await this.loadMcp()
        return server
      } catch (error) {
        this.error = api.parseError(error, '刷新MCP服务失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async callTool(toolName, args) {
      this.calling = true
      this.error = null
      try {
        return await api.mcp.callTool(toolName, args)
      } catch (error) {
        this.error = api.parseError(error, '调用MCP工具失败')
        throw error
      } finally {
        this.calling = false
      }
    }
  }
})
