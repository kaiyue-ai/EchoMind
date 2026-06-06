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

api.interceptors.request.use(config => {
  const token = localStorage.getItem('echomind_auth_token')
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function parseError(err, fallback = '请求失败') {
  return err.response?.data?.error || err.response?.data?.message || err.message || fallback
}

function sendChat(agentId, message, sessionId, modelId, attachments = []) {
  return api.post('/chat', { agentId: agentId || 'default', message, sessionId, modelId, attachments })
    .then(r => r.data)
}

function chatStreamQuery() {
  const token = localStorage.getItem('echomind_auth_token')
  return token ? `?token=${encodeURIComponent(token)}` : ''
}

function streamChatResult(requestId) {
  return new Promise((resolve, reject) => {
    const es = new EventSource(`/api/chat/stream/${requestId}${chatStreamQuery()}`)
    let timer
    const finish = (callback) => {
      clearTimeout(timer)
      es.close()
      callback()
    }
    es.addEventListener('result', (e) => {
      finish(() => resolve(JSON.parse(e.data)))
    })
    es.addEventListener('failure', (e) => {
      const data = JSON.parse(e.data)
      finish(() => reject(new Error(data.error || 'SSE stream failed')))
    })
    es.onerror = () => {
      finish(() => reject(new Error('SSE connection failed')))
    }
    // 5 分钟后仍无结果则主动超时。
    timer = setTimeout(() => {
      finish(() => reject(new Error('Request timed out')))
    }, 300000)
  })
}

function streamChat(agentId, message, sessionId, modelId, attachments = [], onToken, onDone, onError, onMeta, onTool) {
  let es = null
  let timer
  let completed = false
  let cancelled = false
  const finish = (callback = () => {}) => {
    if (completed) return
    completed = true
    clearTimeout(timer)
    if (es) {
      es.close()
      es = null
    }
    callback()
  }
  const fail = (error) => {
    if (cancelled) return
    if (onError) onError(error)
  }
  const cancel = () => {
    cancelled = true
    finish()
  }

  let streamPromise
  try {
    streamPromise = sendChat(agentId, message, sessionId, modelId, attachments)
      .then(({ requestId, sessionId: submittedSessionId, traceId }) => {
        if (cancelled) return
        if (onMeta) onMeta({ sessionId: submittedSessionId, traceId, requestId })
        es = new EventSource(`/api/chat/stream/${requestId}${chatStreamQuery()}`)
        es.addEventListener('meta', (e) => {
          if (cancelled) return
          if (onMeta) onMeta(JSON.parse(e.data))
        })
        es.addEventListener('token', (e) => {
          if (cancelled) return
          const data = JSON.parse(e.data)
          if (data.token && onToken) onToken(data.token)
        })
        es.addEventListener('tool_start', (e) => {
          if (cancelled) return
          if (onTool) onTool({ type: 'start', ...JSON.parse(e.data) })
        })
        es.addEventListener('tool_end', (e) => {
          if (cancelled) return
          if (onTool) onTool({ type: 'end', ...JSON.parse(e.data) })
        })
        es.addEventListener('result', (e) => finish(() => {
          if (cancelled) return
          const data = JSON.parse(e.data)
          if (onDone) onDone(data)
        }))
        es.addEventListener('failure', (e) => {
          if (cancelled) return
          const data = JSON.parse(e.data)
          finish(() => fail(new Error(data.error || 'SSE stream failed')))
        })
        es.onerror = () => {
          finish(() => fail(new Error('SSE connection failed')))
        }
        timer = setTimeout(() => {
          finish(() => fail(new Error('Request timed out')))
        }, 300000)
      })
      .catch(fail)
  } catch (error) {
    fail(error)
    streamPromise = Promise.resolve()
  }
  streamPromise.cancel = cancel
  return streamPromise
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
  auth: {
    login: (username, password) =>
      api.post('/auth/login', { username, password }).then(r => r.data),
    register: (username, password) =>
      api.post('/auth/register', { username, password }).then(r => r.data),
    me: () => api.get('/auth/me').then(r => r.data),
    logout: () => api.post('/auth/logout').then(r => r.data),
    uploadAvatar: (file) => {
      const form = new FormData()
      form.append('file', file)
      return api.post('/auth/avatar', form).then(r => r.data)
    }
  },
  // ===== 聊天接口 =====
  chat: {
    /** 异步发送消息：立即拿到 requestId，结果通过 SSE 获取 */
    send: sendChat,
    /** 订阅异步结果 SSE 流 */
    streamResult: streamChatResult,
    /** 提交异步聊天任务，再通过 SSE 接收模型流式文本片段和最终结果。 */
    stream: streamChat,
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
    /** 下载Agent私有知识库原文件 */
    downloadKnowledge: (agentId, documentId) =>
      api.get(`/agents/${agentId}/knowledge/${documentId}/download`, { responseType: 'blob' }),
    /** 删除Agent（运行时 + 持久化） */
    delete: (agentId) => api.delete(`/agents/${agentId}`)
  },

  // ===== MCP 接口 =====
  mcp: {
    /** 已挂载外部 MCP 服务 */
    servers: () => api.get('/mcp/servers').then(r => r.data),
    /** 动态挂载外部 MCP 服务 */
    mountServer: (config) => api.post('/mcp/servers', config).then(r => r.data),
    /** 卸载外部 MCP 服务 */
    unmountServer: (serverId) => api.delete(`/mcp/servers/${serverId}`).then(r => r.data),
    /** 刷新外部 MCP 服务工具列表 */
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
    /** 当前用户的Team Run历史，和普通会话历史分开 */
    listUserRuns: () => api.get('/team-runs').then(r => r.data),
    /** 提交澄清信息并继续Run */
    resumeRun: (teamId, runId, payload) =>
      api.post(`/teams/${teamId}/runs/${runId}/resume`, payload).then(r => r.data)
  }
}
