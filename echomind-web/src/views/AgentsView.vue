<template>
  <div class="page-container">
    <div class="page-header" style="display: flex; justify-content: space-between; align-items: flex-start;">
      <div>
        <h2>Agent 管理</h2>
        <p>创建和配置 AI Agent 实例</p>
      </div>
      <el-button type="primary" @click="showCreateDialog = true">创建 Agent</el-button>
    </div>

    <div class="card-grid">
      <div v-for="agent in agents" :key="agent.agentId" class="stat-card">
        <div style="display: flex; align-items: center; gap: 12px; margin-bottom: 12px;">
          <el-avatar :size="44" style="background: #27272a; color: #e4e4e7;">
            {{ agent.name?.charAt(0) || 'A' }}
          </el-avatar>
          <div>
            <div style="font-weight: 600;">{{ agent.name }}</div>
            <div style="font-size: 12px; color: #71717a;">ID: {{ agent.agentId }}</div>
          </div>
        </div>
        <div style="font-size: 13px; color: #a1a1aa; margin-bottom: 8px; line-height: 1.5;">
          {{ agent.systemPrompt?.substring(0, 100) }}{{ agent.systemPrompt?.length > 100 ? '...' : '' }}
        </div>
        <div style="font-size: 12px; color: #71717a;">
          <div>模型: {{ agent.defaultModelId }}</div>
          <div v-if="agent.skillIds?.length">Skills: {{ agent.skillIds.join(', ') }}</div>
        </div>
        <div style="margin-top: 12px;">
          <el-button size="small" @click="testAgent(agent)">测试执行</el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="showCreateDialog" title="创建 Agent" width="500px">
      <el-form :model="newAgent" label-width="100px">
        <el-form-item label="Agent ID">
          <el-input v-model="newAgent.agentId" placeholder="例: my-agent" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="newAgent.name" placeholder="例: 通用助手" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input v-model="newAgent.systemPrompt" type="textarea" :rows="3"
                    placeholder="定义Agent的角色和行为..." />
        </el-form-item>
        <el-form-item label="模型">
          <el-input v-model="newAgent.modelId" placeholder="anthropic:claude-sonnet-4-20250514" />
        </el-form-item>
        <el-form-item label="Skill IDs">
          <el-input v-model="newAgent.skillIdsStr" placeholder="逗号分隔，例: weather-query,calculator" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="createAgent">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showTestDialog" title="测试 Agent" width="500px">
      <el-input v-model="testMessage" placeholder="输入测试消息..." />
      <div v-if="testResult" style="margin-top: 16px; padding: 12px; background: #0a0a0a; border-radius: 8px; border: 1px solid #27272a;">
        <div style="font-weight: 600; margin-bottom: 8px;">响应:</div>
        <div style="white-space: pre-wrap;">{{ testResult }}</div>
      </div>
      <template #footer>
        <el-button @click="showTestDialog = false">关闭</el-button>
        <el-button type="primary" @click="runTest" :loading="testLoading">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const agents = ref([])
const showCreateDialog = ref(false)
const showTestDialog = ref(false)
const testMessage = ref('')
const testResult = ref('')
const testLoading = ref(false)
const testingAgent = ref(null)

const newAgent = ref({
  agentId: '', name: '', systemPrompt: 'You are a helpful assistant.',
  modelId: 'anthropic:claude-sonnet-4-20250514', skillIdsStr: ''
})

onMounted(() => loadAgents())

async function loadAgents() {
  try { agents.value = await api.agents.list() } catch (e) { /* ignore */ }
}

async function createAgent() {
  try {
    const config = {
      agentId: newAgent.value.agentId,
      name: newAgent.value.name,
      systemPrompt: newAgent.value.systemPrompt,
      modelId: newAgent.value.modelId,
      skillIds: newAgent.value.skillIdsStr.split(',').map(s => s.trim()).filter(Boolean)
    }
    await api.agents.create(config)
    ElMessage.success('Agent 创建成功')
    showCreateDialog.value = false
    await loadAgents()
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.response?.data?.error || e.message))
  }
}

function testAgent(agent) {
  testingAgent.value = agent
  testMessage.value = ''
  testResult.value = ''
  showTestDialog.value = true
}

async function runTest() {
  testLoading.value = true
  try {
    const res = await api.agents.execute(testingAgent.value.agentId, testMessage.value, crypto.randomUUID())
    testResult.value = res.response
  } catch (e) {
    testResult.value = '错误: ' + (e.response?.data?.error || e.message)
  } finally {
    testLoading.value = false
  }
}
</script>
