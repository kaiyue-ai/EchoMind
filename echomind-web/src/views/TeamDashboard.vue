<template>
  <div class="page-container">
    <div class="page-header" style="display: flex; justify-content: space-between; align-items: flex-start;">
      <div>
        <h2>Agent Team 协作</h2>
        <p>多 Agent 角色协作：Planner → Executor → Reviewer</p>
      </div>
      <el-button type="primary" @click="showCreateTeamDialog = true">创建团队</el-button>
    </div>

    <div v-if="teams.length > 0" style="display: flex; gap: 12px; margin-bottom: 20px; flex-wrap: wrap;">
      <div v-for="team in teams" :key="team.teamId"
           :class="['stat-card', { 'team-selected': selectedTeam?.teamId === team.teamId }]"
           style="cursor: pointer; min-width: 200px;"
           @click="selectTeam(team)">
        <div style="font-weight: 600; margin-bottom: 4px;">{{ team.name }}</div>
        <div style="font-size: 12px; color: #71717a;">ID: {{ team.teamId?.substring(0, 8) }}...</div>
        <div style="margin-top: 8px;">
          <el-tag v-for="role in team.roles" :key="role" size="small" style="margin-right: 4px;">
            {{ role }}
          </el-tag>
        </div>
      </div>
    </div>

    <div v-if="selectedTeam" style="display: flex; gap: 12px; margin-bottom: 20px;">
      <el-input v-model="taskInput" placeholder="输入团队任务，例：策划一场60人户外团建活动..."
                clearable style="flex: 1;" @keydown.enter="executeTask">
        <template #prefix>
          <el-icon><Aim /></el-icon>
        </template>
      </el-input>
      <el-button type="primary" @click="executeTask" :loading="executing" :disabled="!taskInput.trim()">
        执行任务
      </el-button>
    </div>

    <div v-if="teamResult" style="flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 20px;">
      <div style="display: flex; gap: 12px;">
        <el-tag :type="teamResult.status === 'COMPLETED' ? 'success' : 'danger'" size="large">
          {{ teamResult.status === 'COMPLETED' ? '执行完成' : '执行失败' }}
        </el-tag>
      </div>

      <div v-if="teamResult.stepResults?.length">
        <h3 style="margin-bottom: 12px;">子任务执行步骤</h3>
        <el-steps :active="teamResult.stepResults.length" finish-status="success" align-center>
          <el-step v-for="(step, i) in teamResult.stepResults" :key="i"
                   :title="'步骤 ' + (i + 1)"
                   :description="step?.substring(0, 40) + '...'" />
        </el-steps>
      </div>

      <div class="stat-card">
        <h3 style="margin-bottom: 12px;">最终输出</h3>
        <div style="white-space: pre-wrap; line-height: 1.8;" v-html="renderMarkdown(teamResult.finalOutput)"></div>
      </div>

      <div v-if="teamResult.mermaidDiagram" class="mermaid-container">
        <h3 style="margin-bottom: 16px;">协作流程可视化</h3>
        <div ref="mermaidRef"></div>
      </div>
    </div>

    <div v-if="!selectedTeam && teams.length === 0" style="text-align: center; color: #71717a; margin-top: 80px;">
      <div style="font-size: 64px; margin-bottom: 16px;">👥</div>
      <div style="font-size: 18px; font-weight: 600; margin-bottom: 8px;">暂未创建团队</div>
      <div>点击"创建团队"开始 Agent Team 协作</div>
    </div>

    <el-dialog v-model="showCreateTeamDialog" title="创建 Agent 团队" width="500px">
      <el-form label-width="100px">
        <el-form-item label="团队名称">
          <el-input v-model="newTeam.name" placeholder="例: 活动策划团队" />
        </el-form-item>
        <el-form-item label="Planner Agent">
          <el-select v-model="newTeam.plannerId" placeholder="选择Planner" style="width: 100%">
            <el-option v-for="a in availableAgents" :key="a.agentId"
                       :label="a.name" :value="a.agentId" />
          </el-select>
        </el-form-item>
        <el-form-item label="Executor Agent">
          <el-select v-model="newTeam.executorId" placeholder="选择Executor" style="width: 100%">
            <el-option v-for="a in availableAgents" :key="a.agentId"
                       :label="a.name" :value="a.agentId" />
          </el-select>
        </el-form-item>
        <el-form-item label="Reviewer Agent">
          <el-select v-model="newTeam.reviewerId" placeholder="选择Reviewer（可选）" style="width: 100%" clearable>
            <el-option v-for="a in availableAgents" :key="a.agentId"
                       :label="a.name" :value="a.agentId" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateTeamDialog = false">取消</el-button>
        <el-button type="primary" @click="createTeam">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { Plus, Aim } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import api from '../api'

const teams = ref([])
const selectedTeam = ref(null)
const taskInput = ref('')
const teamResult = ref(null)
const executing = ref(false)
const showCreateTeamDialog = ref(false)
const availableAgents = ref([])
const mermaidRef = ref(null)

const newTeam = ref({ name: '', plannerId: 'default', executorId: 'default', reviewerId: '' })

onMounted(async () => {
  try {
    teams.value = await api.team.list()
    availableAgents.value = await api.agents.list()
  } catch (e) { /* ignore */ }
})

function selectTeam(team) {
  selectedTeam.value = team
  teamResult.value = null
}

async function createTeam() {
  try {
    const result = await api.team.create(newTeam.value)
    ElMessage.success('团队创建成功')
    showCreateTeamDialog.value = false
    teams.value = await api.team.list()
    selectedTeam.value = teams.value.find(t => t.teamId === result.teamId)
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.response?.data?.error || e.message))
  }
}

async function executeTask() {
  if (!selectedTeam.value || !taskInput.value.trim()) return
  executing.value = true
  teamResult.value = null
  try {
    teamResult.value = await api.team.execute(selectedTeam.value.teamId, taskInput.value)
    if (teamResult.value.mermaidDiagram) {
      await nextTick()
      renderMermaid()
    }
  } catch (e) {
    ElMessage.error('执行失败: ' + (e.response?.data?.error || e.message))
  } finally {
    executing.value = false
  }
}

async function renderMermaid() {
  if (!mermaidRef.value || !teamResult.value?.mermaidDiagram) return
  try {
    const mermaid = await import('mermaid')
    mermaid.default.initialize({ startOnLoad: false, theme: 'dark' })
    const { svg } = await mermaid.default.render('team-flow', teamResult.value.mermaidDiagram)
    mermaidRef.value.innerHTML = svg
  } catch (e) {
    console.error('Mermaid render error:', e)
  }
}

function renderMarkdown(text) {
  if (!text) return ''
  return marked.parse(text)
}
</script>

<style scoped>
.team-selected {
  border: 2px solid #52525b;
  background: #18181b;
}
</style>
