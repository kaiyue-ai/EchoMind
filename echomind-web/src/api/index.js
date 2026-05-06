import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * EchoMind API 客户端
 * 封装所有后端REST API调用
 */
const api = axios.create({
  baseURL: '/api',
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

// 响应拦截器：统一处理错误
api.interceptors.response.use(
  res => res,
  err => {
    const msg = err.response?.data?.error || err.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(err)
  }
)

export default {
  // ===== 聊天接口 =====
  chat: {
    /** 发送消息到Agent */
    send: (agentId, message, sessionId) =>
      api.post('/chat', { agentId: agentId || 'default', message, sessionId })
        .then(r => r.data),
    /** 获取会话历史 */
    history: (sessionId) =>
      api.get(`/chat/${sessionId}/history`).then(r => r.data)
  },

  // ===== 模型接口 =====
  models: {
    /** 列出所有模型 */
    list: () => api.get('/models').then(r => r.data),
    /** 切换模型 */
    switch: (providerId, modelName) =>
      api.put('/models/switch', { providerId, modelName }).then(r => r.data)
  },

  // ===== Skill接口 =====
  skills: {
    /** 列出所有Skill */
    list: () => api.get('/skills').then(r => r.data),
    /** 启用Skill */
    enable: (skillId) => api.post(`/skills/${skillId}/enable`).then(r => r.data),
    /** 禁用Skill */
    disable: (skillId) => api.post(`/skills/${skillId}/disable`).then(r => r.data),
    /** 上传Skill JAR */
    upload: (file) => {
      const form = new FormData()
      form.append('file', file)
      return api.post('/skills/upload', form).then(r => r.data)
    },
    /** 删除Skill */
    delete: (skillId) => api.delete(`/skills/${skillId}`)
  },

  // ===== Agent接口 =====
  agents: {
    /** 列出所有Agent */
    list: () => api.get('/agents').then(r => r.data),
    /** 创建Agent */
    create: (config) => api.post('/agents', config).then(r => r.data),
    /** 执行Agent */
    execute: (agentId, message, sessionId) =>
      api.post(`/agents/${agentId}/execute`, { message, sessionId }).then(r => r.data)
  },

  // ===== 记忆接口 =====
  memory: {
    /** 获取会话记忆 */
    get: (sessionId) => api.get(`/memory/${sessionId}`).then(r => r.data),
    /** 清除会话记忆 */
    clear: (sessionId) => api.delete(`/memory/${sessionId}`).then(r => r.data)
  },

  // ===== MCP接口 =====
  mcp: {
    /** 服务器信息 */
    serverInfo: () => api.get('/mcp/server').then(r => r.data),
    /** 列出工具 */
    tools: () => api.get('/mcp/tools').then(r => r.data),
    /** 调用工具 */
    callTool: (toolName, args) =>
      api.post(`/mcp/tools/${toolName}/call`, { arguments: args }).then(r => r.data),
    /** 注册Skill为MCP工具 */
    registerSkill: (skillId) =>
      api.post(`/mcp/register-skill/${skillId}`).then(r => r.data)
  },

  // ===== Agent Team接口 =====
  team: {
    /** 列出团队 */
    list: () => api.get('/teams').then(r => r.data),
    /** 创建团队 */
    create: (config) => api.post('/teams', config).then(r => r.data),
    /** 执行团队任务 */
    execute: (teamId, task) =>
      api.post(`/teams/${teamId}/execute`, { task }).then(r => r.data),
    /** 消息总线状态 */
    messageBusStatus: () => api.get('/teams/message-bus/pending').then(r => r.data)
  }
}
