<template>
  <div class="workspace-page">
    <header class="workspace-header">
      <div>
        <span class="eyebrow">Agents</span>
        <h1>Agent 管理</h1>
      </div>
      <div class="workspace-header-actions">
        <el-button :loading="loading" @click="agentStore.loadAgents(true)">刷新</el-button>
        <el-button type="primary" @click="openCreateDrawer">创建 Agent</el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert">
      <template #default>
        <el-button text size="small" @click="agentStore.loadAgents(true)">重试</el-button>
      </template>
    </el-alert>

    <div v-loading="loading" class="resource-grid">
      <ResourceCard v-for="agent in agents" :key="agent.agentId" :meta="agent.agentId">
        <template #title>
          <div class="agent-title">
            <span class="avatar-token">{{ agent.name?.charAt(0) || 'A' }}</span>
            <span>{{ agent.name }}</span>
          </div>
        </template>
        <template #actions>
          <StatusBadge tone="success">Ready</StatusBadge>
        </template>
        <p class="resource-desc">{{ truncate(agent.systemPrompt, 150) }}</p>
        <div class="detail-list">
          <span>模型: {{ agent.defaultModelId || agent.modelId || '-' }}</span>
          <span>Skills: {{ agent.skillIds?.length || 0 }}</span>
          <span>知识库: {{ knowledgeCount(agent.agentId) }} 个文档</span>
        </div>
        <template #footer>
          <el-button size="small" @click="chatWithAgent(agent)">进入对话</el-button>
          <el-button size="small" @click="testAgent(agent)">测试</el-button>
          <el-button size="small" @click="manageKnowledge(agent)">知识库</el-button>
          <el-button size="small" type="danger" text :loading="mutatingId === agent.agentId" @click="deleteAgent(agent)">
            删除
          </el-button>
        </template>
      </ResourceCard>
      <el-empty v-if="!loading && agents.length === 0" description="暂无 Agent" />
    </div>

    <DrawerForm v-model="showCreateDrawer" title="创建 Agent" size="640px">
      <el-form label-position="top" class="stack-form">
        <el-form-item label="Agent ID">
          <el-input v-model="newAgent.agentId" placeholder="my-agent" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="newAgent.name" placeholder="自定义助手" />
        </el-form-item>
        <el-form-item label="System Prompt">
          <el-input v-model="newAgent.systemPrompt" type="textarea" :rows="5" placeholder="定义 Agent 的角色、边界和输出风格..." />
        </el-form-item>

        <el-form-item label="模型">
          <el-alert v-if="modelError" :title="modelError" type="error" show-icon :closable="false">
            <template #default>
              <el-button text size="small" @click="modelStore.loadModels(true)">刷新模型</el-button>
            </template>
          </el-alert>
          <el-radio-group v-else v-model="newAgent.modelId" class="choice-list">
            <label
              v-for="model in models"
              :key="model.providerId + ':' + model.modelName"
              :class="['choice-card', { selected: newAgent.modelId === modelId(model) }]"
            >
              <el-radio :label="modelId(model)">
                <span class="choice-title">{{ model.providerId }}/{{ model.modelName }}</span>
              </el-radio>
              <span class="choice-meta">
                {{ (model.capabilities || []).join(' / ') || 'TEXT' }}
              </span>
            </label>
          </el-radio-group>
          <el-empty v-if="!modelError && !modelLoading && models.length === 0" description="暂无模型" />
        </el-form-item>

        <el-form-item label="Skills">
          <el-alert v-if="skillError" :title="skillError" type="error" show-icon :closable="false">
            <template #default>
              <el-button text size="small" @click="skillStore.loadSkills()">刷新 Skill</el-button>
            </template>
          </el-alert>
          <div v-else class="picker-panel" v-loading="skillLoading">
            <div class="picker-toolbar">
              <span>已选 {{ newAgent.skillIds.length }} 个</span>
              <div>
                <el-button size="small" text @click="selectAllEnabledSkills">全选启用</el-button>
                <el-button size="small" text @click="newAgent.skillIds = []">清空</el-button>
              </div>
            </div>
            <el-checkbox-group v-model="newAgent.skillIds" class="checkbox-list">
              <label
                v-for="skill in skills"
                :key="skill.skillId"
                :class="['check-card', { disabled: skill.state !== 'ENABLED' }]"
              >
                <el-checkbox :label="skill.skillId" :disabled="skill.state !== 'ENABLED'">
                  <span class="choice-title">{{ skill.metadata?.name || skill.skillId }}</span>
                </el-checkbox>
                <span class="choice-meta">{{ skill.metadata?.description || skill.state }}</span>
              </label>
            </el-checkbox-group>
            <el-empty v-if="!skillLoading && skills.length === 0" description="暂无 Skill" />
          </div>
        </el-form-item>

        <el-form-item label="知识库">
          <el-upload v-model:file-list="newAgentKnowledgeFiles" drag multiple :auto-upload="false" accept=".txt,.pdf">
            <div class="upload-copy">拖入或选择 txt / pdf 文件，扫描版 PDF 会自动 OCR</div>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDrawer = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="createDisabled" @click="createAgent">创建</el-button>
      </template>
    </DrawerForm>

    <DrawerForm v-model="showKnowledgeDrawer" :title="`${selectedAgent?.name || 'Agent'} 知识库`" size="680px">
      <el-upload v-model:file-list="knowledgeFiles" drag multiple :auto-upload="false" accept=".txt,.pdf">
        <div class="upload-copy">上传 txt / pdf，扫描版 PDF 会先 OCR，再切片写入该 Agent 私有向量库</div>
      </el-upload>
      <div class="drawer-toolbar">
        <el-button type="primary" :loading="knowledgeUploading" @click="uploadKnowledgeFiles">上传到知识库</el-button>
      </div>
      <el-table v-loading="knowledgeLoading" :data="selectedKnowledge" size="small" class="full-width">
        <el-table-column prop="fileName" label="文件" min-width="180" />
        <el-table-column prop="fileType" label="类型" width="80" />
        <el-table-column prop="chunkCount" label="切片" width="80" />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button text type="danger" size="small" @click="deleteKnowledge(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!knowledgeLoading && selectedKnowledge.length === 0" description="暂无知识库文档" />
    </DrawerForm>

    <el-dialog v-model="showTestDialog" title="测试 Agent" width="520px">
      <el-input v-model="testMessage" placeholder="输入测试消息..." />
      <div v-if="testResult" class="result-box">
        <div class="result-box-title">响应</div>
        <div class="plain-message">{{ testResult }}</div>
      </div>
      <template #footer>
        <el-button @click="showTestDialog = false">关闭</el-button>
        <el-button type="primary" :loading="testing" @click="runTest">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import DrawerForm from '../components/workbench/DrawerForm.vue'
import ResourceCard from '../components/workbench/ResourceCard.vue'
import StatusBadge from '../components/workbench/StatusBadge.vue'
import { useAgentStore } from '../stores/agents'
import { useChatStore } from '../stores/chat'
import { useModelStore } from '../stores/models'
import { useSkillStore } from '../stores/skills'

const router = useRouter()
const agentStore = useAgentStore()
const modelStore = useModelStore()
const skillStore = useSkillStore()
const chatStore = useChatStore()
const { agents, loading, saving, testing, error, knowledgeByAgent, knowledgeLoading, knowledgeUploading } = storeToRefs(agentStore)
const { models, loading: modelLoading, error: modelError } = storeToRefs(modelStore)
const { skills, loading: skillLoading, error: skillError } = storeToRefs(skillStore)

const mutatingId = ref(null)
const showCreateDrawer = ref(false)
const showKnowledgeDrawer = ref(false)
const showTestDialog = ref(false)
const testMessage = ref('')
const testResult = ref('')
const testingAgent = ref(null)
const selectedAgent = ref(null)
const knowledgeFiles = ref([])
const newAgentKnowledgeFiles = ref([])
const newAgent = ref(defaultAgent())

const selectedKnowledge = computed(() => selectedAgent.value
  ? knowledgeByAgent.value[selectedAgent.value.agentId] || []
  : [])
const enabledSkillIds = computed(() => skills.value.filter(skill => skill.state === 'ENABLED').map(skill => skill.skillId))
const createDisabled = computed(() => {
  return !newAgent.value.agentId.trim()
    || !newAgent.value.name.trim()
    || !newAgent.value.systemPrompt.trim()
    || !newAgent.value.modelId
    || Boolean(modelError.value)
    || Boolean(skillError.value)
    || models.value.length === 0
})

onMounted(() => {
  agentStore.loadAgents()
  modelStore.loadModels()
  skillStore.loadSkills()
})

watch(models, () => {
  if (!newAgent.value.modelId) newAgent.value.modelId = defaultModelId()
}, { immediate: true })

function defaultAgent() {
  return {
    agentId: '',
    name: '',
    systemPrompt: 'You are a helpful assistant.',
    modelId: '',
    skillIds: []
  }
}

async function openCreateDrawer() {
  showCreateDrawer.value = true
  newAgent.value = defaultAgent()
  await Promise.allSettled([modelStore.loadModels(), skillStore.loadSkills()])
  newAgent.value.modelId = defaultModelId()
}

function defaultModelId() {
  const preferred = models.value.find(model => model.isDefault === true || model.default === true) || models.value[0]
  return preferred ? modelId(preferred) : ''
}

function modelId(model) {
  return `${model.providerId}:${model.modelName}`
}

function selectAllEnabledSkills() {
  newAgent.value.skillIds = [...enabledSkillIds.value]
}

async function createAgent() {
  try {
    const created = await agentStore.createAgent({
      agentId: newAgent.value.agentId.trim(),
      name: newAgent.value.name.trim(),
      systemPrompt: newAgent.value.systemPrompt,
      modelId: newAgent.value.modelId,
      skillIds: newAgent.value.skillIds.filter(id => enabledSkillIds.value.includes(id))
    })
    const files = newAgentKnowledgeFiles.value.map(item => item.raw).filter(Boolean)
    for (const file of files) {
      await agentStore.uploadKnowledge(created.agentId, file)
    }
    ElMessage.success('Agent 创建成功')
    showCreateDrawer.value = false
    newAgentKnowledgeFiles.value = []
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.response?.data?.error || e.message))
  }
}

function chatWithAgent(agent) {
  chatStore.selectedAgent = agent.agentId
  router.push('/chat')
}

function testAgent(agent) {
  testingAgent.value = agent
  testMessage.value = ''
  testResult.value = ''
  showTestDialog.value = true
}

async function runTest() {
  try {
    const res = await agentStore.executeAgent(testingAgent.value.agentId, testMessage.value, crypto.randomUUID())
    testResult.value = res.response
  } catch (e) {
    testResult.value = '错误: ' + (e.response?.data?.error || e.message)
  }
}

async function manageKnowledge(agent) {
  selectedAgent.value = agent
  knowledgeFiles.value = []
  showKnowledgeDrawer.value = true
  try {
    await agentStore.loadKnowledge(agent.agentId)
  } catch (e) {
    ElMessage.error('加载知识库失败: ' + (e.response?.data?.error || e.message))
  }
}

async function uploadKnowledgeFiles() {
  if (!selectedAgent.value) return
  const files = knowledgeFiles.value.map(item => item.raw).filter(Boolean)
  if (files.length === 0) {
    ElMessage.warning('请选择 txt 或 pdf 文件')
    return
  }
  try {
    for (const file of files) {
      await agentStore.uploadKnowledge(selectedAgent.value.agentId, file)
    }
    knowledgeFiles.value = []
    ElMessage.success('知识库上传完成')
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.response?.data?.error || e.message))
  }
}

async function deleteKnowledge(row) {
  if (!selectedAgent.value) return
  try {
    await agentStore.deleteKnowledge(selectedAgent.value.agentId, row.id)
    ElMessage.success('知识库文档已删除')
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.response?.data?.error || e.message))
  }
}

async function deleteAgent(agent) {
  try {
    await ElMessageBox.confirm(
      `确定要删除 Agent「${agent.name}」吗？此操作不可撤销。`,
      '确认删除',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    )
    mutatingId.value = agent.agentId
    await agentStore.deleteAgent(agent.agentId)
    ElMessage.success('Agent 已删除')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error('删除失败: ' + (e.response?.data?.error || e.message))
    }
  } finally {
    mutatingId.value = null
  }
}

function knowledgeCount(agentId) {
  return knowledgeByAgent.value[agentId]?.length || 0
}

function truncate(value, size) {
  const text = value || ''
  return text.length > size ? text.slice(0, size) + '...' : text
}

function formatBytes(size) {
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>
