import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * EchoMind API 客户端
 * 封装所有后端REST API调用
 */
const api = axios.create({
  baseURL: '/api',
  timeout: 60000
})

function parseError(err, fallback = '请求失败') {
  return err.response?.data?.error || err.response?.data?.message || err.message || fallback
}

// 响应拦截器：统一处理错误
api.interceptors.response.use(
  res => res,
  err => {
    ElMessage.error(parseError(err))
    return Promise.reject(err)
  }
)

export default {
  parseError,
  // ===== 聊天接口 =====
  chat: {
    /** 异步发送消息：立即拿到 requestId，结果通过 SSE 获取 */
    send: (agentId, message, sessionId, modelId, attachments = []) =>
      api.post('/chat', { agentId: agentId || 'default', message, sessionId, modelId, attachments })
        .then(r => r.data),
    /** 同步发送消息：直接等待完整回复 */
    sendSync: (agentId, message, sessionId, modelId, attachments = []) =>
      api.post('/chat/sync', { agentId: agentId || 'default', message, sessionId, modelId, attachments })
        .then(r => r.data),
    /** 订阅异步结果 SSE 流 */
    streamResult: (requestId) =>
      new Promise((resolve, reject) => {
        const es = new EventSource(`/api/chat/stream/${requestId}`)
        es.addEventListener('result', (e) => {
          es.close()
          resolve(JSON.parse(e.data))
        })
        es.onerror = (e) => {
          es.close()
          reject(new Error('SSE connection failed'))
        }
        // 5 分钟后仍无结果则主动超时。
        setTimeout(() => {
          es.close()
          reject(new Error('Request timed out'))
        }, 300000)
      }),
    /**
     * 通过 POST SSE 接收模型流式文本片段。
     * EventSource 只支持 GET，所以这里使用 fetch + ReadableStream。
     */
    stream: (agentId, message, sessionId, modelId, attachments = [], onToken, onDone, onError, onMeta) => {
      fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ agentId: agentId || 'default', message, sessionId, modelId, attachments })
      }).then(response => {
        if (!response.ok) {
          response.text().then(text => onError(new Error(text || ('HTTP ' + response.status))))
          return
        }
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        function read() {
          reader.read().then(({ done, value }) => {
            if (done) { onDone(); return }
            buffer += decoder.decode(value, { stream: true })
            const lines = buffer.split('\n')
            buffer = lines.pop() || ''
            for (const line of lines) {
              if (line.startsWith('event:')) continue
              if (line.startsWith('data:')) {
                const data = line.substring(5).trim()
                if (data === '[DONE]') { onDone(); return }
                if (data.startsWith('[ERROR]')) { onError(new Error(data.substring(8))); return }
                try {
                  const parsed = JSON.parse(data)
                  if (parsed.token) onToken(parsed.token)
                  else if (parsed.error) { onError(new Error(parsed.error)); return }
                  else if (parsed.sessionId && onMeta) onMeta(parsed)
                } catch (e) {}
              }
            }
            read()
          }).catch(onError)
        }
        read()
      }).catch(onError)
    },
    /** 获取会话历史 */
    history: (sessionId) =>
      api.get(`/chat/${sessionId}/history`).then(r => r.data),
    /** 获取所有会话列表 */
    sessions: () =>
      api.get('/chat/sessions').then(r => r.data),
    /** 删除单条会话历史 */
    delete: (sessionId) =>
      api.delete(`/chat/${sessionId}`).then(r => r.data)
  },

  // ===== 存储接口 =====
  storage: {
    /** 上传聊天图片，返回可随消息发送的附件引用 */
    uploadImage: (file) => {
      const form = new FormData()
      form.append('file', file)
      return api.post('/storage/images', form).then(r => r.data)
    }
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
      api.post(`/agents/${agentId}/execute`, { message, sessionId }).then(r => r.data),
    /** 查询Agent私有知识库 */
    knowledge: (agentId) =>
      api.get(`/agents/${agentId}/knowledge`).then(r => r.data),
    /** 上传txt/pdf到Agent私有知识库 */
    uploadKnowledge: (agentId, file) => {
      const form = new FormData()
      form.append('file', file)
      return api.post(`/agents/${agentId}/knowledge`, form, { timeout: 180000 }).then(r => r.data)
    },
    /** 删除Agent私有知识库文档 */
    deleteKnowledge: (agentId, documentId) =>
      api.delete(`/agents/${agentId}/knowledge/${documentId}`).then(r => r.data),
    /** 删除Agent（运行时 + 持久化） */
    delete: (agentId) => api.delete(`/agents/${agentId}`)
  },

  // ===== MCP接口 =====
  mcp: {
    /** 已挂载外部MCP服务 */
    servers: () => api.get('/mcp/servers').then(r => r.data),
    /** 动态挂载外部MCP服务 */
    mountServer: (config) => api.post('/mcp/servers', config).then(r => r.data),
    /** 卸载外部MCP服务 */
    unmountServer: (serverId) => api.delete(`/mcp/servers/${serverId}`).then(r => r.data),
    /** 刷新外部MCP服务工具列表 */
    refreshServer: (serverId) => api.post(`/mcp/servers/${serverId}/refresh`).then(r => r.data),
    /** 列出工具 */
    tools: () => api.get('/mcp/tools').then(r => r.data),
    /** 调用工具 */
    callTool: (toolName, args) =>
      api.post(`/mcp/tools/${toolName}/call`, { arguments: args }).then(r => r.data)
  },

  // ===== Agent Team接口 =====
  team: {
    /** 列出团队 */
    list: () => api.get('/teams').then(r => r.data),
    /** 创建团队 */
    create: (config) => api.post('/teams', config).then(r => r.data),
    /** 硬删除团队及其黑板记录 */
    delete: (teamId) => api.delete(`/teams/${teamId}`),
    /** 创建异步团队Run */
    createRun: (teamId, task) =>
      api.post(`/teams/${teamId}/runs`, { task }).then(r => r.data),
    /** 查询团队Run */
    getRun: (teamId, runId) =>
      api.get(`/teams/${teamId}/runs/${runId}`).then(r => r.data),
    /** 列出团队Run */
    listRuns: (teamId) =>
      api.get(`/teams/${teamId}/runs`).then(r => r.data),
    /** 提交澄清信息并继续Run */
    resumeRun: (teamId, runId, clarificationAnswer) =>
      api.post(`/teams/${teamId}/runs/${runId}/resume`, { clarificationAnswer }).then(r => r.data),
    /** 消息总线状态 */
    messageBusStatus: () => api.get('/teams/message-bus/pending').then(r => r.data)
  }
}
