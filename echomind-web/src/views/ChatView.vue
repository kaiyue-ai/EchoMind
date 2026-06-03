<template>
  <div :class="['chat-workspace', { 'inspector-open': inspectorOpen }]">
    <section class="chat-stage">
      <header class="workspace-header">
        <div>
          <span class="eyebrow">EchoMind Chat</span>
          <h1>智能对话</h1>
        </div>
        <div class="workspace-header-actions">
          <el-button v-if="sessionId" text type="danger" title="删除当前会话" @click="deleteCurrentSession">
            <el-icon><Delete /></el-icon>
          </el-button>
          <el-button text title="新建会话" aria-label="新建会话" @click="newSession">
            <el-icon><Plus /></el-icon>
            <span class="desktop-action-label">新建</span>
          </el-button>
          <el-button text :title="inspectorOpen ? '隐藏上下文' : '显示上下文'" @click="uiStore.toggleInspector()">
            <el-icon><Setting /></el-icon>
          </el-button>
        </div>
      </header>

      <MessageList ref="messageListRef" :messages="messages" :loading-history="historyLoading" />
      <ChatComposer
        v-model="input"
        :attachments="attachments"
        :loading="loading"
        :uploading="uploadingImage"
        @send="sendMessage"
        @select-image="uploadImage"
        @remove-attachment="removeAttachment"
      />
    </section>

    <InspectorPanel
      v-if="inspectorOpen"
      v-model:selected-model="selectedModel"
      v-model:selected-agent="selectedAgent"
      :models="models"
      :agents="agents"
      :skills="skills"
      :servers="servers"
      :agent-default-model-id="currentAgentDefaultModelId"
      :selected-model-supports-vision="selectedModelSupportsVision"
      :has-attachments="attachments.length > 0"
      :session-id="sessionId"
      :message-count="messages.length"
      @new-session="newSession"
      @close="uiStore.setInspectorOpen(false)"
    />
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onActivated, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { Delete, Plus, Setting } from '@element-plus/icons-vue'
import api from '../api'
import ChatComposer from '../components/chat/ChatComposer.vue'
import InspectorPanel from '../components/chat/InspectorPanel.vue'
import MessageList from '../components/chat/MessageList.vue'
import { useAgentStore } from '../stores/agents'
import { useChatStore } from '../stores/chat'
import { useMcpStore } from '../stores/mcp'
import { useModelStore } from '../stores/models'
import { useSkillStore } from '../stores/skills'
import { useUiStore } from '../stores/ui'

defineOptions({ name: 'ChatView' })

const chatStore = useChatStore()
const agentStore = useAgentStore()
const modelStore = useModelStore()
const skillStore = useSkillStore()
const mcpStore = useMcpStore()
const uiStore = useUiStore()
const route = useRoute()
const router = useRouter()
const refreshSessions = inject('refreshSessions', null)
const deleteSession = inject('deleteSession', null)
const messageListRef = ref(null)
const uploadingImage = ref(false)
const historyLoading = ref(false)
const activeStream = ref(null)
let streamSerial = 0
let historySerial = 0

const {
  input,
  messages,
  attachments,
  loading,
  sessionId,
  selectedModel,
  selectedAgent,
  thinkingMsgIndex
} = storeToRefs(chatStore)
const { agents } = storeToRefs(agentStore)
const { models } = storeToRefs(modelStore)
const { skills } = storeToRefs(skillStore)
const { servers } = storeToRefs(mcpStore)
const { inspectorOpen } = storeToRefs(uiStore)

const AGENT_DEFAULT_MODEL = '__agent_default__'
const fallbackModelId = 'deepseek:deepseek-v4-flash'

const currentAgent = computed(() => {
  return agents.value.find(agent => agent.agentId === selectedAgent.value)
})

const currentAgentDefaultModelId = computed(() => {
  return currentAgent.value?.defaultModelId || currentAgent.value?.modelId || fallbackModelId
})

const followsAgentDefaultModel = computed(() => !selectedModel.value || selectedModel.value === AGENT_DEFAULT_MODEL)

const effectiveModelId = computed(() => {
  return followsAgentDefaultModel.value ? currentAgentDefaultModelId.value : selectedModel.value
})

const selectedModelSpec = computed(() => {
  const [providerId, modelName] = (effectiveModelId.value || '').split(':')
  return models.value.find(model => model.providerId === providerId && model.modelName === modelName)
})

const selectedModelSupportsVision = computed(() => supportsVision(selectedModelSpec.value))

onMounted(() => {
  collapseInspectorOnCompactViewport()
  Promise.allSettled([
    modelStore.loadModels(),
    agentStore.loadAgents(),
    skillStore.loadSkills(),
    mcpStore.loadMcp()
  ]).then(() => ensureSelectedModel())
  loadRouteHistory(route.query.sessionId)
})

watch(() => route.query.sessionId, async (newSid) => {
  if (route.path !== '/chat') return
  if (newSid && newSid !== sessionId.value) {
    cancelActiveStream()
    sessionId.value = newSid
    messages.value = []
    loadRouteHistory(newSid)
  } else if (!newSid && sessionId.value) {
    router.replace({ path: '/chat', query: { sessionId: sessionId.value } })
  }
})

watch(models, () => ensureSelectedModel(), { immediate: true })

onActivated(() => {
  if (route.path === '/chat' && !route.query.sessionId && sessionId.value) {
    router.replace({ path: '/chat', query: { sessionId: sessionId.value } })
  }
  nextTick(() => messageListRef.value?.scrollToBottom('auto'))
})

onBeforeUnmount(() => cancelActiveStream())

async function loadRouteHistory(sid) {
  if (!sid || sid === sessionId.value && messages.value.length > 0) return
  const currentHistorySerial = ++historySerial
  historyLoading.value = true
  try {
    const history = await api.chat.history(sid)
    if (currentHistorySerial === historySerial && route.query.sessionId === sid) {
      chatStore.setHistory(sid, history)
    }
  } catch (e) {
    /* 会话可能已被清理，保持空状态 */
  } finally {
    if (currentHistorySerial === historySerial) {
      historyLoading.value = false
      await nextTick()
      messageListRef.value?.scrollToBottom('auto')
    }
  }
}

function sendMessage() {
  ensureSelectedModel()
  const msg = input.value.trim()
  if ((!msg && attachments.value.length === 0) || loading.value) return
  if (attachments.value.length > 0 && !selectedModelSupportsVision.value) {
    ElMessage.warning('当前模型不支持多模态图片输入，请先切换到带 VISION 能力的模型')
    return
  }

  const outgoingAttachments = attachments.value.map(att => ({ ...att }))
  input.value = ''
  attachments.value = []
  messages.value.push({ role: 'user', content: msg, attachments: outgoingAttachments })
  loading.value = true
  thinkingMsgIndex.value = messages.value.length
  messages.value.push({ role: 'assistant', content: '思考中...' })
  nextTick(() => messageListRef.value?.scrollToBottom('auto'))

  const streamId = ++streamSerial
  let firstToken = true
  let pendingLeadingToken = ''
  let activeToolName = ''
  try {
    activeStream.value = api.chat.stream(
      selectedAgent.value,
      msg || '请理解这张图片。',
      sessionId.value,
      followsAgentDefaultModel.value ? null : selectedModel.value,
      outgoingAttachments,
      (token) => {
        if (!isActiveStream(streamId)) return
        if (firstToken && !token.trim()) {
          pendingLeadingToken += token
          return
        }
        const visibleToken = firstToken ? pendingLeadingToken + token : token
        pendingLeadingToken = ''
        if (firstToken) {
          messages.value.splice(thinkingMsgIndex.value, 1, { role: 'assistant', content: visibleToken })
          firstToken = false
        } else {
          const idx = messages.value.length - 1
          if (idx >= 0 && messages.value[idx].role === 'assistant') {
            messages.value[idx] = { ...messages.value[idx], content: messages.value[idx].content + visibleToken }
          }
        }
        nextTick(() => messageListRef.value?.followIfNearBottom('auto'))
      },
      (result) => {
        if (!isActiveStream(streamId)) return
        finishStream(result, streamId)
      },
      (error) => {
        if (!isActiveStream(streamId)) return
        handleStreamError(error, streamId)
      },
      (meta) => {
        if (!isActiveStream(streamId)) return
        if (meta.sessionId && meta.sessionId !== sessionId.value) {
          sessionId.value = meta.sessionId
          router.replace({ path: '/chat', query: { sessionId: meta.sessionId } })
        }
      },
      (toolEvent) => {
        if (!isActiveStream(streamId)) return
        if (toolEvent.type === 'start') {
          activeToolName = toolEvent.toolName || '工具'
        } else if (toolEvent.type === 'end' && (!toolEvent.toolName || toolEvent.toolName === activeToolName)) {
          activeToolName = ''
        }
        updateToolStatus(activeToolName)
      }
    )
  } catch (error) {
    handleStreamError(error, streamId)
  }
}

function updateToolStatus(toolName) {
  const idx = thinkingMsgIndex.value
  if (idx < 0 || idx >= messages.value.length || messages.value[idx]?.role !== 'assistant') return
  const status = toolName ? `正在调用 ${toolName}...` : ''
  messages.value[idx] = {
    ...messages.value[idx],
    toolStatus: status
  }
  nextTick(() => messageListRef.value?.followIfNearBottom('auto'))
}

function finishStream(result, streamId = streamSerial) {
  if (!isActiveStream(streamId)) return
  const idx = thinkingMsgIndex.value
  const finalResponse = result?.response
  if (idx >= 0 && messages.value[idx]?.role === 'assistant' && finalResponse) {
    messages.value.splice(idx, 1, {
      role: 'assistant',
      content: finalResponse
    })
  }
  if (idx >= 0 && messages.value[idx]?.role === 'assistant' && messages.value[idx]?.content === '思考中...') {
    messages.value.splice(idx, 1, {
      role: 'system',
      content: '本次请求没有收到模型输出，请稍后重试或切换模型。'
    })
  }
  loading.value = false
  thinkingMsgIndex.value = -1
  if (activeStream.value && isActiveStream(streamId)) {
    activeStream.value = null
  }
  if (refreshSessions) refreshSessions()
  nextTick(() => messageListRef.value?.followIfNearBottom('auto'))
}

function handleStreamError(error, streamId = streamSerial) {
  if (!isActiveStream(streamId)) return
  console.error('Stream error:', error)
  const idx = thinkingMsgIndex.value
  const replaceIndex = idx >= 0 && idx < messages.value.length ? idx : messages.value.length
  messages.value.splice(replaceIndex, idx >= 0 && idx < messages.value.length ? 1 : 0, {
    role: 'system',
    content: '请求失败: ' + (error.message || '请稍后重试')
  })
  finishStream(null, streamId)
}

function cancelActiveStream() {
  streamSerial += 1
  if (activeStream.value?.cancel) {
    activeStream.value.cancel()
  }
  const idx = thinkingMsgIndex.value
  if (idx >= 0 && messages.value[idx]?.role === 'assistant' && messages.value[idx]?.content === '思考中...') {
    messages.value.splice(idx, 1)
  }
  activeStream.value = null
  if (loading.value) {
    loading.value = false
  }
  thinkingMsgIndex.value = -1
}

function isActiveStream(streamId) {
  return streamId === streamSerial
}

function supportsVision(model) {
  return Array.isArray(model?.capabilities)
    && model.capabilities.some(c => String(c).toUpperCase() === 'VISION')
}

function ensureSelectedModel() {
  if (followsAgentDefaultModel.value || !models.value.length) {
    return
  }
  const available = models.value.map(model => `${model.providerId}:${model.modelName}`)
  if (available.includes(selectedModel.value)) return

  selectedModel.value = AGENT_DEFAULT_MODEL
}

function collapseInspectorOnCompactViewport() {
  if (typeof window !== 'undefined'
    && window.matchMedia('(max-width: 1180px)').matches
    && inspectorOpen.value) {
    uiStore.setInspectorOpen(false)
  }
}

async function uploadImage(file) {
  uploadingImage.value = true
  try {
    const res = await api.storage.uploadImage(file)
    if (res?.attachment) attachments.value.push(res.attachment)
  } catch (error) {
    ElMessage.error(api.parseError(error, '图片上传失败'))
  } finally {
    uploadingImage.value = false
  }
}

function removeAttachment(att) {
  attachments.value = attachments.value.filter(item => item !== att)
}

function newSession() {
  historySerial += 1
  cancelActiveStream()
  chatStore.resetSession()
  router.replace({ path: '/chat' })
}

async function deleteCurrentSession() {
  if (!sessionId.value || typeof deleteSession !== 'function') return
  await deleteSession(sessionId.value)
}
</script>
