<template>
  <div class="workspace-page team-workspace">
    <header class="workspace-header">
      <div>
        <span class="eyebrow">Agent Team</span>
        <h1>团队协作</h1>
      </div>
      <div class="workspace-header-actions">
        <el-button @click="fillDemoTask">示例任务</el-button>
        <el-button type="primary" @click="openCreateTeamDrawer">创建团队</el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert">
      <template #default>
        <el-button text size="small" @click="teamStore.loadTeams()">重试</el-button>
      </template>
    </el-alert>

    <div class="team-grid">
      <aside class="panel-block team-sidebar">
        <div class="section-head">
          <div>
            <span class="eyebrow">Teams</span>
            <h2>团队</h2>
          </div>
          <span class="count-pill">{{ teams.length }}</span>
        </div>
        <div v-loading="loading" class="team-list">
          <button
            v-for="team in teams"
            :key="team.teamId"
            :class="['team-select-card', { active: selectedTeam?.teamId === team.teamId }]"
            type="button"
            @click="selectTeam(team)"
          >
            <span class="team-card-title">
              <strong>{{ team.name }}</strong>
              <el-button text type="danger" size="small" :loading="deleting && selectedTeam?.teamId === team.teamId" @click.stop="confirmDeleteTeam(team)">
                删除
              </el-button>
            </span>
            <span class="team-id">{{ team.teamId?.slice(0, 8) }}</span>
            <span class="tag-row">
              <el-tag v-for="role in team.roles" :key="role" size="small">{{ role }}</el-tag>
            </span>
          </button>
          <div v-if="!loading && teams.length === 0" class="empty-note">暂未创建团队</div>
        </div>
      </aside>

      <main class="team-main">
        <section v-if="selectedTeam" class="panel-block">
          <div class="section-head">
            <div>
              <span class="eyebrow">Selected Team</span>
              <h2>{{ selectedTeam.name }}</h2>
            </div>
          </div>
          <div class="member-grid">
            <ResourceCard v-for="member in selectedTeam.members" :key="member.role + member.agentId" :meta="member.agentId">
              <template #title>{{ member.role }}</template>
              <div class="detail-list">
                <span>{{ member.agentName || member.agentId }}</span>
              </div>
              <div class="tag-row">
                <el-tag v-for="tag in member.capabilityTags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
              </div>
            </ResourceCard>
          </div>
          <div class="task-composer">
            <el-input v-model="taskInput" type="textarea" :rows="3" placeholder="输入团队任务..." />
            <el-button type="primary" :loading="executing" :disabled="!taskInput.trim()" @click="executeTask">
              启动 Run
            </el-button>
          </div>
        </section>

        <section v-if="currentRun" class="run-board">
          <ResourceCard>
            <template #title>Run 状态</template>
            <template #actions>
              <StatusBadge :tone="statusTone(currentRun.status)">{{ currentRun.status }}</StatusBadge>
            </template>
            <h2 class="run-title">{{ currentRun.task }}</h2>
            <div class="detail-list">
              <span>ID: {{ currentRun.runId }}</span>
            </div>
            <template #footer>
              <el-button v-if="currentRun.finalOutput" size="small" @click="scrollToFinalReport">
                {{ currentRun.status === 'FAILED' ? '查看失败原因' : '查看最终报告' }}
              </el-button>
            </template>
          </ResourceCard>

          <el-alert
            v-if="currentRun.status === 'NEEDS_CLARIFICATION'"
            type="warning"
            show-icon
            :title="currentRun.clarificationQuestion || 'Reviewer 需要用户补充信息'"
          >
            <div class="clarify-box">
              <el-input v-model="clarificationAnswer" placeholder="补充说明..." />
              <el-button type="primary" :loading="resuming" @click="resumeRun">继续 Run</el-button>
            </div>
          </el-alert>

          <ResourceCard v-if="currentRun.finalOutput" ref="finalReportRef" :class="currentRun.status === 'FAILED' ? 'failed-panel' : 'final-panel'">
            <template #title>{{ currentRun.status === 'FAILED' ? 'Reviewer 拦截原因' : '最终报告' }}</template>
            <MarkdownRenderer :content="currentRun.finalOutput" />
          </ResourceCard>

          <div class="board-grid">
            <ResourceCard title="Step 进度">
              <el-table :data="currentRun.steps || []" size="small" class="full-width">
                <el-table-column prop="stepIndex" label="#" width="50" />
                <el-table-column label="任务" min-width="180">
                  <template #default="{ row }">
                    <div class="step-title">{{ row.title }}</div>
                    <div class="step-desc">{{ row.description }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="Executor" width="140">
                  <template #default="{ row }">{{ row.assignedAgentId || '-' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="130">
                  <template #default="{ row }">
                    <el-tag :type="stepStatusType(row.status)" size="small">{{ row.status }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="retryCount" label="重试" width="70" />
              </el-table>
            </ResourceCard>

            <ResourceCard title="Reviewer 审查">
              <div v-if="planReview" class="review-block">
                <strong>规划审查</strong>
                <p>{{ planReview.reason }}</p>
                <el-tag size="small">{{ planReview.action }}</el-tag>
              </div>
              <div v-if="resultReview" class="review-block">
                <strong>结果审查</strong>
                <p>{{ resultReview.reason }}</p>
                <el-tag size="small">{{ resultReview.action }}</el-tag>
              </div>
              <div v-if="!planReview && !resultReview" class="empty-note">等待 Reviewer 审查</div>
            </ResourceCard>
          </div>

          <div class="board-grid">
            <ResourceCard title="事件时间线">
              <el-timeline>
                <el-timeline-item v-for="event in currentRun.events || []" :key="event.id" :timestamp="formatTime(event.createdAt)">
                  <div class="event-title">{{ event.type }}</div>
                  <div class="event-msg">{{ event.message }}</div>
                </el-timeline-item>
              </el-timeline>
            </ResourceCard>

            <ResourceCard title="协作流程">
              <div v-if="currentRun.mermaidDiagram" ref="mermaidRef" class="mermaid-container"></div>
              <div v-else class="empty-note">Run 完成后生成 Mermaid</div>
            </ResourceCard>
          </div>
        </section>

        <div v-if="!selectedTeam" class="empty-workspace">
          <strong>选择或创建一个团队</strong>
          <span>Team Run 会显示 Planner、Executor 和 Reviewer 的黑板状态。</span>
        </div>
      </main>
    </div>

    <DrawerForm v-model="showCreateTeamDrawer" title="创建 Agent 团队" size="760px">
      <el-form label-position="top" class="stack-form">
        <el-form-item label="团队名称">
          <el-input v-model="newTeam.name" placeholder="活动策划团队" />
        </el-form-item>
        <el-alert v-if="availableAgents.length === 0" title="暂无可选 Agent，请先创建 Agent。" type="warning" show-icon :closable="false" />
        <div class="team-member-editor">
          <div v-for="(member, index) in newTeam.members" :key="index" class="member-editor-row">
            <el-select v-model="member.role" placeholder="角色">
              <el-option label="PLANNER" value="PLANNER" />
              <el-option label="EXECUTOR" value="EXECUTOR" />
              <el-option label="REVIEWER" value="REVIEWER" />
            </el-select>
            <el-select v-model="member.agentId" placeholder="Agent" filterable>
              <el-option v-for="agent in availableAgents" :key="agent.agentId" :label="agent.name" :value="agent.agentId" />
            </el-select>
            <el-select v-model="member.capabilityTags" multiple filterable allow-create default-first-option placeholder="能力标签">
              <el-option v-for="tag in capabilityOptions" :key="tag" :label="tag" :value="tag" />
            </el-select>
            <el-button text type="danger" @click="removeMember(index)">删除</el-button>
          </div>
        </div>
        <el-button @click="addExecutor">添加 Executor</el-button>
      </el-form>
      <template #footer>
        <el-button @click="showCreateTeamDrawer = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="createTeamDisabled" @click="createTeam">创建</el-button>
      </template>
    </DrawerForm>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import DrawerForm from '../components/workbench/DrawerForm.vue'
import ResourceCard from '../components/workbench/ResourceCard.vue'
import StatusBadge from '../components/workbench/StatusBadge.vue'
import MarkdownRenderer from '../components/chat/MarkdownRenderer.vue'
import { useAgentStore } from '../stores/agents'
import { useTeamStore } from '../stores/team'

const teamStore = useTeamStore()
const agentStore = useAgentStore()
const {
  teams,
  selectedTeam,
  taskInput,
  currentRun,
  loading,
  creating,
  deleting,
  executing,
  resuming,
  error
} = storeToRefs(teamStore)
const { agents: availableAgents } = storeToRefs(agentStore)

const showCreateTeamDrawer = ref(false)
const mermaidRef = ref(null)
const finalReportRef = ref(null)
const clarificationAnswer = ref('')
const capabilityOptions = ['planning', 'search', 'weather', 'venue', 'budget', 'coordination', 'review', 'report', 'general']
const newTeam = ref(defaultTeam())
const planReview = computed(() => parseJson(currentRun.value?.planReviewJson))
const resultReview = computed(() => parseJson(currentRun.value?.resultReviewJson))
const createTeamDisabled = computed(() => {
  return !newTeam.value.name.trim()
    || availableAgents.value.length === 0
    || !newTeam.value.members.some(member => member.role === 'REVIEWER')
    || newTeam.value.members.some(member => !member.agentId || !member.role)
})

onMounted(async () => {
  await Promise.allSettled([teamStore.loadTeams(), agentStore.loadAgents(true)])
  normalizeTeamAgents()
})

onBeforeUnmount(() => {
  teamStore.stopPolling()
})

watch(availableAgents, normalizeTeamAgents)
watch(() => currentRun.value?.mermaidDiagram, async () => {
  await nextTick()
  renderMermaid()
})

function defaultTeam() {
  return {
    name: '活动策划团队',
    members: [
      { role: 'PLANNER', agentId: 'default', capabilityTags: ['planning'], sortOrder: 10 },
      { role: 'EXECUTOR', agentId: 'default', capabilityTags: ['search', 'venue'], sortOrder: 20 },
      { role: 'EXECUTOR', agentId: 'default', capabilityTags: ['weather'], sortOrder: 30 },
      { role: 'REVIEWER', agentId: 'default', capabilityTags: ['review', 'report'], sortOrder: 40 }
    ]
  }
}

function normalizeTeamAgents() {
  const firstAgent = availableAgents.value[0]?.agentId || 'default'
  for (const member of newTeam.value.members) {
    if (!availableAgents.value.some(agent => agent.agentId === member.agentId)) {
      member.agentId = firstAgent
    }
  }
}

function openCreateTeamDrawer() {
  newTeam.value = defaultTeam()
  normalizeTeamAgents()
  showCreateTeamDrawer.value = true
}

function selectTeam(team) {
  teamStore.selectTeam(team)
}

function fillDemoTask() {
  taskInput.value = '策划一场60人户外团建活动，要求考虑场地、天气风险、预算和人员协调，最终给出可执行方案。'
  if (!selectedTeam.value && teams.value.length > 0) {
    teamStore.selectTeam(teams.value[0])
  }
}

async function createTeam() {
  try {
    await teamStore.createTeam(newTeam.value)
    ElMessage.success('团队创建成功')
    showCreateTeamDrawer.value = false
    newTeam.value = defaultTeam()
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.response?.data?.error || e.message))
  }
}

async function confirmDeleteTeam(team) {
  try {
    await ElMessageBox.confirm(
      `确定要永久删除团队「${team.name}」吗？该团队的 Run、Step 和 Event 黑板记录会一并删除。`,
      '删除团队',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
    await teamStore.deleteTeam(team.teamId)
    ElMessage.success('团队已删除')
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error('删除失败: ' + (e.response?.data?.error || e.message))
  }
}

async function executeTask() {
  try {
    await teamStore.executeTask(taskInput.value)
    ElMessage.success('Run 已启动')
  } catch (e) {
    ElMessage.error('执行失败: ' + (e.response?.data?.error || e.message))
  }
}

async function resumeRun() {
  try {
    await teamStore.resumeRun(clarificationAnswer.value)
    clarificationAnswer.value = ''
    ElMessage.success('已继续 Run')
  } catch (e) {
    ElMessage.error('继续失败: ' + (e.response?.data?.error || e.message))
  }
}

function addExecutor() {
  newTeam.value.members.push({
    role: 'EXECUTOR',
    agentId: availableAgents.value[0]?.agentId || 'default',
    capabilityTags: ['general'],
    sortOrder: (newTeam.value.members.length + 1) * 10
  })
}

function removeMember(index) {
  newTeam.value.members.splice(index, 1)
}

function statusTone(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'NEEDS_CLARIFICATION') return 'warning'
  return 'primary'
}

function stepStatusType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RETRYING') return 'warning'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}

function parseJson(value) {
  if (!value) return null
  try {
    return JSON.parse(value)
  } catch (e) {
    return null
  }
}

function formatTime(value) {
  if (!value) return ''
  return new Date(value).toLocaleTimeString()
}

function scrollToFinalReport() {
  finalReportRef.value?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function renderMermaid() {
  if (!mermaidRef.value || !currentRun.value?.mermaidDiagram) return
  try {
    const mermaid = await import('mermaid')
    mermaid.default.initialize({ startOnLoad: false, theme: 'dark' })
    const id = 'team-flow-' + currentRun.value.runId.replaceAll('-', '')
    const { svg } = await mermaid.default.render(id, currentRun.value.mermaidDiagram)
    mermaidRef.value.innerHTML = svg
  } catch (e) {
    mermaidRef.value.innerHTML = '<pre>' + currentRun.value.mermaidDiagram + '</pre>'
  }
}
</script>
