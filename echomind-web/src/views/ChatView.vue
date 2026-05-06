<template>
  <div class="page-container">
    <div class="page-header">
      <h2>智能对话</h2>
      <p>与 AI Agent 对话，支持多模型切换和 Skill 调用</p>
    </div>

    <div style="display: flex; gap: 16px; flex: 1; overflow: hidden;">
      <div style="flex: 1; display: flex; flex-direction: column;">
        <div class="chat-container">
          <div class="chat-messages" ref="msgContainer">
            <div v-if="messages.length === 0" style="text-align: center; color: #71717a; margin-top: 80px;">
              <div style="font-size: 48px; margin-bottom: 16px;">🧠</div>
              <div style="font-size: 18px; font-weight: 600; margin-bottom: 8px; color: #a1a1aa;">欢迎使用 EchoMind</div>
              <div style="font-size: 14px;">在下方输入消息，AI Agent 将为您服务</div>
            </div>

            <div v-for="(msg, i) in messages" :key="i"
                 :class="['message-bubble', 'message-' + msg.role]">
              <div v-if="msg.role === 'assistant'" v-html="renderMarkdown(msg.content)"></div>
              <div v-else>{{ msg.content }}</div>
              <div v-if="msg.skillResults?.length" style="margin-top: 8px; font-size: 12px; opacity: 0.8;">
                Skills: {{ msg.skillResults.join(', ') }}
              </div>
            </div>

            <div v-if="loading" class="message-bubble message-assistant">
              思考中...
            </div>
          </div>

          <div class="chat-input-area">
            <el-input
              v-model="input"
              type="textarea"
              :rows="2"
              placeholder="输入消息，Enter 发送，Shift+Enter 换行..."
              @keydown.enter.exact="sendMessage"
              :disabled="loading"
              resize="none"
            />
            <el-button type="primary" @click="sendMessage"
                       :loading="loading" :disabled="!input.trim()">
              发送
            </el-button>
          </div>
        </div>
      </div>

      <div style="width: 280px; display: flex; flex-direction: column; gap: 12px; flex-shrink: 0;">
        <div class="stat-card">
          <div style="font-weight: 600; margin-bottom: 10px; font-size: 14px;">模型选择</div>
          <el-select v-model="selectedModel" placeholder="选择模型" size="small" style="width: 100%">
            <el-option v-for="m in models" :key="m.providerId+':'+m.modelName"
                       :label="m.providerId + '/' + m.modelName"
                       :value="m.providerId + ':' + m.modelName" />
          </el-select>
        </div>

        <div class="stat-card">
          <div style="font-weight: 600; margin-bottom: 10px; font-size: 14px;">Agent</div>
          <el-select v-model="selectedAgent" placeholder="选择Agent" size="small" style="width: 100%">
            <el-option v-for="a in agents" :key="a.agentId"
                       :label="a.name" :value="a.agentId" />
          </el-select>
        </div>

        <div class="stat-card">
          <div style="font-weight: 600; margin-bottom: 10px; font-size: 14px;">会话</div>
          <div style="font-size: 12px; color: #71717a; word-break: break-all;">
            <div>ID: {{ sessionId?.substring(0, 8) }}...</div>
            <div style="margin-top: 4px;">消息数: {{ messages.length }}</div>
          </div>
          <el-button size="small" text @click="newSession" style="margin-top: 8px; color: #a1a1aa;">新建会话</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { Promotion } from '@element-plus/icons-vue'
import { marked } from 'marked'
import api from '../api'

const input = ref('')
const messages = ref([])
const loading = ref(false)
const sessionId = ref(null)
const selectedModel = ref('anthropic:claude-sonnet-4-20250514')
const selectedAgent = ref('default')
const models = ref([])
const agents = ref([])
const msgContainer = ref(null)

onMounted(async () => {
  try {
    models.value = await api.models.list()
    agents.value = await api.agents.list()
  } catch (e) { /* use defaults */ }
})

function renderMarkdown(text) {
  if (!text) return ''
  return marked.parse(text)
}

async function sendMessage() {
  const msg = input.value.trim()
  if (!msg || loading.value) return
  input.value = ''

  messages.value.push({ role: 'user', content: msg })
  loading.value = true

  try {
    const res = await api.chat.send(selectedAgent.value, msg, sessionId.value)
    sessionId.value = res.sessionId
    messages.value.push({
      role: 'assistant',
      content: res.response,
      skillResults: res.skillResults
    })
  } catch (e) {
    messages.value.push({
      role: 'system',
      content: '请求失败: ' + (e.response?.data?.error || e.message)
    })
  } finally {
    loading.value = false
    await nextTick()
    if (msgContainer.value) {
      msgContainer.value.scrollTop = msgContainer.value.scrollHeight
    }
  }
}

function newSession() {
  sessionId.value = null
  messages.value = []
}
</script>
