<template>
  <div class="chat-workspace">
    <section class="chat-stage">
      <header class="workspace-header">
        <div>
          <span class="eyebrow">EchoMind Chat</span>
          <h1>智能对话</h1>
        </div>
        <div class="workspace-header-actions">
          <el-button v-if="sessionId" text type="danger" title="删除当前会话" @click="deleteCurrentSession">
            删除
          </el-button>
          <el-button text title="新建会话" @click="newSession">
            <el-icon><Plus /></el-icon>
            新建
          </el-button>
          <el-button text :title="inspectorOpen ? '隐藏上下文' : '显示上下文'" @click="uiStore.toggleInspector()">
            <el-icon><Setting /></el-icon>
          </el-button>
        </div>
      </header>

      <MessageList ref="messageListRef" :messages="messages" />
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
      :selected-model-supports-vision="selectedModelSupportsVision"
      :has-attachments="attachments.length > 0"
      :session-id="sessionId"
      :message-count="messages.length"
      @new-session="newSession"
    />
  </div>
</template>

<script setup>
import { computed, inject, nextTick, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { Plus, Setting } from '@element-plus/icons-vue'
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

const fallbackModelId = 'deepseek:deepseek-v4-flash'
const legacyClaudeModelPrefix = ['anthropic', 'claude'].join(':') + '-'

const selectedModelSpec = computed(() => {
  const [providerId, modelName] = (selectedModel.value || '').split(':')
  return models.value.find(model => model.providerId === providerId && model.modelName === modelName)
})

const selectedModelSupportsVision = computed(() => supportsVision(selectedModelSpec.value))

onMounted(async () => {
  await Promise.allSettled([
    modelStore.loadModels(),
    agentStore.loadAgents(),
    skillStore.loadSkills(),
    mcpStore.loadMcp()
  ])
  ensureSelectedModel()
  await loadRouteHistory(route.query.sessionId)
})

watch(() => route.query.sessionId, async (newSid) => {
  if (newSid && newSid !== sessionId.value) {
    sessionId.value = newSid
    messages.value = []
    await loadRouteHistory(newSid)
  }
})

watch(models, () => ensureSelectedModel(), { immediate: true })

onActivated(() => {
  nextTick(() => messageListRef.value?.scrollToBottom())
})

async function loadRouteHistory(sid) {
  if (!sid || sid === sessionId.value && messages.value.length > 0) return
  try {
    chatStore.setHistory(sid, await api.chat.history(sid))
  } catch (e) {
    /* 会话可能已被清理，保持空状态 */
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
  nextTick(() => messageListRef.value?.scrollToBottom())

  let firstToken = true
  api.chat.stream(
    selectedAgent.value,
    msg || '请理解这张图片。',
    sessionId.value,
    selectedModel.value,
    outgoingAttachments,
    (token) => {
      if (firstToken) {
        messages.value.splice(thinkingMsgIndex.value, 1, { role: 'assistant', content: token })
        firstToken = false
      } else {
        const idx = messages.value.length - 1
        if (idx >= 0 && messages.value[idx].role === 'assistant') {
          messages.value[idx] = { ...messages.value[idx], content: messages.value[idx].content + token }
        }
      }
      nextTick(() => messageListRef.value?.scrollToBottom())
    },
    () => finishStream(),
    async (error) => handleStreamError(error, msg, outgoingAttachments),
    (meta) => {
      if (meta.sessionId && meta.sessionId !== sessionId.value) {
        sessionId.value = meta.sessionId
        router.replace({ path: '/chat', query: { sessionId: meta.sessionId } })
      }
    }
  )
}

function finishStream() {
  loading.value = false
  thinkingMsgIndex.value = -1
  if (refreshSessions) refreshSessions()
  nextTick(() => messageListRef.value?.scrollToBottom())
}

async function handleStreamError(error, msg, outgoingAttachments) {
  console.error('Stream error, falling back to sync:', error)
  if (outgoingAttachments.length > 0) {
    messages.value.splice(thinkingMsgIndex.value, 1, {
      role: 'system',
      content: '请求失败: ' + (error.message || '当前模型无法处理图片')
    })
    finishStream()
    return
  }

  try {
    const res = await api.chat.sendSync(
      selectedAgent.value,
      msg || '请理解这张图片。',
      sessionId.value,
      selectedModel.value,
      outgoingAttachments
    )
    sessionId.value = res.sessionId
    if (res.sessionId) {
      router.replace({ path: '/chat', query: { sessionId: res.sessionId } })
    }
    messages.value.splice(thinkingMsgIndex.value, 1, {
      role: 'assistant',
      content: res.response || '(empty response)',
      skillResults: res.skillResults
    })
  } catch (e2) {
    messages.value.splice(thinkingMsgIndex.value, 1, {
      role: 'system',
      content: '请求失败: ' + (e2.response?.data?.error || e2.message)
    })
  } finally {
    finishStream()
  }
}

function supportsVision(model) {
  return Array.isArray(model?.capabilities)
    && model.capabilities.some(c => String(c).toUpperCase() === 'VISION')
}

function ensureSelectedModel() {
  if (!models.value.length) {
    if (!selectedModel.value || selectedModel.value.startsWith(legacyClaudeModelPrefix)) {
      selectedModel.value = fallbackModelId
    }
    return
  }
  const available = models.value.map(model => `${model.providerId}:${model.modelName}`)
  if (available.includes(selectedModel.value)) return

  const preferred = models.value.find(model => model.providerId === 'deepseek' && model.modelName === 'deepseek-v4-flash')
  const defaultModel = models.value.find(model => model.isDefault === true || model.default === true)
  const nextModel = preferred || defaultModel || models.value[0]
  selectedModel.value = `${nextModel.providerId}:${nextModel.modelName}`
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
  chatStore.resetSession()
  router.replace({ path: '/chat' })
}

async function deleteCurrentSession() {
  if (!sessionId.value || typeof deleteSession !== 'function') return
  await deleteSession(sessionId.value)
}
</script>
