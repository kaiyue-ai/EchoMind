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
          <span class="count-pill">{{ teamList.length }}</span>
        </div>
        <div v-loading="loading" class="team-list">
          <button
            v-for="team in teamList"
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
              <el-tag v-for="role in team.roles" :key="role" size="small">{{ roleLabel(role) }}</el-tag>
            </span>
          </button>
          <div v-if="!loading && teamList.length === 0" class="empty-note">暂未创建团队</div>
        </div>
      </aside>

      <main class="team-main">
        <section v-if="selectedTeam" class="panel-block team-overview-panel">
          <div class="section-head team-overview-head">
            <div>
              <span class="eyebrow">Selected Team</span>
              <h2>{{ selectedTeam.name }}</h2>
              <span class="section-subtitle">
                {{ selectedMembers.length }} 名成员 · {{ teamRunList.length }} 个 Run
              </span>
            </div>
            <StatusBadge tone="primary">{{ selectedMembers.length }} Members</StatusBadge>
          </div>
          <div class="team-overview-layout">
            <div class="team-members-panel">
              <div class="section-head compact">
                <div>
                  <span class="eyebrow">Members</span>
                  <h3>成员与能力</h3>
                </div>
              </div>
              <div class="member-grid">
                <ResourceCard v-for="member in selectedMembers" :key="member.role + member.agentId" :meta="member.agentId">
                  <template #title>{{ roleLabel(member.role) }}</template>
                  <div class="detail-list">
                    <span>{{ member.agentName || member.agentId }}</span>
                  </div>
                  <div class="tag-row">
                    <el-tag v-for="tag in member.capabilityTags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
                  </div>
                </ResourceCard>
              </div>
            </div>

            <div class="team-task-panel">
              <div class="section-head compact">
                <div>
                  <span class="eyebrow">Task</span>
                  <h3>启动任务</h3>
                </div>
              </div>
              <div class="task-composer">
                <el-input v-model="taskInput" type="textarea" :rows="3" placeholder="输入团队任务..." />
                <el-button type="primary" :loading="executing" :disabled="!taskInput.trim()" @click="executeTask">
                  启动 Run
                </el-button>
              </div>
              <div class="review-options-panel">
                <el-segmented v-model="reviewPreset" :options="reviewPresetOptions" @change="applyReviewPreset" />
                <div class="review-toggle-grid">
                  <el-checkbox v-model="reviewOptions.planReviewEnabled">PlanReview</el-checkbox>
                  <el-checkbox v-model="reviewOptions.subReviewEnabled">每步 Review</el-checkbox>
                  <el-checkbox v-model="reviewOptions.globalReviewEnabled">GlobalReview</el-checkbox>
                  <el-checkbox v-model="reviewOptions.simpleFastPathEnabled">SIMPLE 直返</el-checkbox>
                </div>
              </div>
              <div class="team-run-history" v-loading="loadingRuns">
                <div class="section-head compact">
                  <div>
                    <span class="eyebrow">Team Runs</span>
                    <h3>运行历史</h3>
                  </div>
                  <span class="count-pill">{{ teamRunList.length }}</span>
                </div>
                <TransitionGroup v-if="teamRunList.length" name="list-soft" tag="div" class="team-run-list">
                  <button
                    v-for="run in teamRunList"
                    :key="run.runId"
                    :class="['team-run-item', { active: currentRun?.runId === run.runId }]"
                    type="button"
                    @click="openRun(run)"
                  >
                    <span class="run-history-title">{{ run.task }}</span>
                    <span class="run-history-meta">
                      {{ runStatusLabel(run.status) }} · {{ taskLevelLabel(run.taskLevel) }} · {{ formatTime(run.updatedAt || run.createdAt) }}
                    </span>
                  </button>
                </TransitionGroup>
                <div v-else class="empty-note">当前用户在该团队下还没有 Run</div>
              </div>
            </div>
          </div>
        </section>

        <section v-if="currentRun" :class="['run-board', { 'is-live': runIsLive }]">
          <div class="run-board-head">
            <div>
              <span class="eyebrow">Current Run</span>
              <h2>运行详情</h2>
            </div>
            <StatusBadge :tone="statusTone(currentRun.status)">{{ runStatusLabel(currentRun.status) }}</StatusBadge>
          </div>

          <ResourceCard class="run-status-card">
            <template #title>Run 状态</template>
            <template #actions>
              <StatusBadge :tone="statusTone(currentRun.status)">{{ runStatusLabel(currentRun.status) }}</StatusBadge>
            </template>
            <h2 class="run-title">{{ currentRun.task }}</h2>
            <div class="detail-list">
              <span>ID: {{ currentRun.runId }}</span>
              <span>任务等级: {{ taskLevelLabel(currentRun.taskLevel) }}</span>
              <span>审查策略: {{ reviewOptionsLabel(currentRun.reviewOptions) }}</span>
              <span>当前状态: {{ runStatusLabel(currentRun.status) }}</span>
              <span>历史整体重规划: {{ currentRun.fullReplanCount || 0 }} 次</span>
              <span>历史局部重规划: {{ currentRun.partialReplanCount || 0 }} 次</span>
            </div>
            <template #footer>
              <el-button v-if="currentRun.finalOutput" size="small" @click="scrollToFinalReport">
                {{ currentRun.status === 'FAILED' ? '查看失败原因' : '查看最终报告' }}
              </el-button>
            </template>
          </ResourceCard>

          <ResourceCard v-if="currentRun.finalOutput" ref="finalReportRef" :class="currentRun.status === 'FAILED' ? 'failed-panel' : 'final-panel'">
            <template #title>{{ currentRun.status === 'FAILED' ? 'Reviewer 拦截原因' : '最终报告' }}</template>
            <template #actions>
              <el-button size="small" @click="downloadFinalReport">下载 Markdown</el-button>
            </template>
            <MarkdownRenderer :content="currentRun.finalOutput" />
          </ResourceCard>

          <div class="board-grid run-insight-grid">
            <ResourceCard title="Step 进度">
              <el-table :data="currentRun.steps || []" size="small" class="full-width">
                <el-table-column prop="stepIndex" label="#" width="50" />
                <el-table-column label="任务" min-width="180">
                  <template #default="{ row }">
                    <div class="step-title">{{ row.title }}</div>
                    <div class="step-desc">{{ row.description }}</div>
                    <div v-if="row.dependsOnStepIds?.length" class="step-meta">
                      依赖: {{ dependencyLabels(row.dependsOnStepIds).join('、') }}
                    </div>
                    <div class="step-meta">
                      风险: {{ riskLabel(row.riskLevel) }} · 质量: {{ qualityLabel(row.qualityStatus) }}
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="Executor" width="140">
                  <template #default="{ row }">
                    <div>{{ selectionName(stepSelection(row), row.assignedAgentId || '-') }}</div>
                    <div v-if="stepSelection(row)?.decisionSource" class="step-meta">
                      {{ selectionSourceLabel(stepSelection(row)?.decisionSource) }}
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="130">
                  <template #default="{ row }">
                    <el-tag :type="stepStatusType(row.status)" size="small">{{ stepStatusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="retryCount" label="重试" width="70" />
              </el-table>
            </ResourceCard>

            <ResourceCard title="Reviewer 审查">
              <div v-if="planReview" class="review-block">
                <strong>规划审查</strong>
                <p>{{ planReview.reason }}</p>
                <el-tag size="small">{{ actionLabel(planReview.action) }}</el-tag>
              </div>
              <div v-if="visibleResultReview" class="review-block">
                <strong>全局终审</strong>
                <p>{{ visibleResultReview.reason }}</p>
                <el-tag size="small">{{ actionLabel(visibleResultReview.action) }}</el-tag>
              </div>
              <div v-if="hasReflections" class="review-block">
                <strong>Reflexion 重试上下文</strong>
                <div v-for="step in reflectedSteps" :key="step.stepId" class="reflection-item">
                  <span>{{ step.stepIndex }}. {{ step.title }}</span>
                  <small>{{ parseJson(step.reflectionJson)?.reviewReason || step.lastReviewReason || step.revisionInstructions }}</small>
                </div>
              </div>
              <div v-if="!planReview && !visibleResultReview" class="empty-note">等待 Reviewer 审查</div>
            </ResourceCard>
          </div>

          <div class="board-grid run-artifact-grid">
            <ResourceCard title="协作流程" class="mermaid-card">
              <div v-if="currentRun.mermaidDiagram" ref="mermaidRef" class="mermaid-container"></div>
              <div v-else class="empty-note">Run 启动后实时生成中文 DAG 流程图</div>
            </ResourceCard>

            <ResourceCard title="事件时间线" class="timeline-card">
              <el-timeline>
                <el-timeline-item
                  v-for="event in currentRun.events || []"
                  :key="event.id"
                  class="timeline-event"
                  :timestamp="formatTime(event.createdAt)"
                >
                  <div class="event-title">{{ eventDisplayLabel(event) }}</div>
                  <div class="event-msg">{{ eventDisplayMessage(event) }}</div>
                </el-timeline-item>
              </el-timeline>
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
        <el-alert v-if="agentList.length === 0" title="暂无可选 Agent，请先创建 Agent。" type="warning" show-icon :closable="false" />
        <div class="team-member-editor">
          <div v-for="(member, index) in newTeam.members" :key="index" class="member-editor-row">
            <el-select v-model="member.role" placeholder="角色">
              <el-option label="Planner 规划器" value="PLANNER" />
              <el-option label="Executor 执行者" value="EXECUTOR" />
              <el-option label="Reviewer 全局审查" value="REVIEWER" />
              <el-option label="StepReviewer 每步审查" value="SUB_REVIEWER" />
              <el-option label="MergeAgent 聚合" value="MERGER" />
            </el-select>
            <el-select v-model="member.agentId" placeholder="Agent" filterable>
              <el-option v-for="agent in agentList" :key="agent.agentId" :label="agent.name" :value="agent.agentId" />
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
import { useUiStore } from '../stores/ui'

const teamStore = useTeamStore()
const agentStore = useAgentStore()
const uiStore = useUiStore()
const {
  teams,
  selectedTeam,
  taskInput,
  reviewPreset,
  reviewOptions,
  currentRun,
  loading,
  creating,
  deleting,
  executing,
  loadingRuns,
  teamRuns,
  error
} = storeToRefs(teamStore)
const { agents: availableAgents } = storeToRefs(agentStore)

const showCreateTeamDrawer = ref(false)
const mermaidRef = ref(null)
const lastRenderedMermaid = ref('')
const finalReportRef = ref(null)
const capabilityOptions = ['planning', 'search', 'weather', 'venue', 'budget', 'coordination', 'review', 'report', 'general']
const reviewPresetOptions = [
  { label: '质量优先', value: 'quality' },
  { label: '平衡', value: 'balanced' },
  { label: '最快', value: 'fastest' },
  { label: '自定义', value: 'custom' }
]
const newTeam = ref(defaultTeam())
const teamList = computed(() => teams.value || [])
const teamRunList = computed(() => teamRuns.value || [])
const agentList = computed(() => availableAgents.value || [])
const selectedMembers = computed(() => selectedTeam.value?.members || [])
const planReview = computed(() => parseJson(currentRun.value?.planReviewJson))
const resultReview = computed(() => parseJson(currentRun.value?.resultReviewJson))
const visibleResultReview = computed(() => {
  return normalizeReviewOptions(currentRun.value?.reviewOptions).globalReviewEnabled ? resultReview.value : null
})
const selectionByStepId = computed(() => {
  const map = {}
  for (const event of currentRun.value?.events || []) {
    if (event.type === 'AGENT_SELECTED' && event.stepId) {
      map[event.stepId] = eventPayload(event)
    }
  }
  return map
})
const reflectedSteps = computed(() => (currentRun.value?.steps || []).filter(step => step.reflectionJson || step.lastReviewReason))
const hasReflections = computed(() => reflectedSteps.value.length > 0)
const runIsLive = computed(() => {
  return ['PENDING', 'PLANNING', 'PLAN_REVIEWING', 'EXECUTING', 'MERGING', 'GLOBAL_REVIEWING']
    .includes(currentRun.value?.status)
})
const createTeamDisabled = computed(() => {
  return !newTeam.value.name.trim()
    || agentList.value.length === 0
    || !newTeam.value.members.some(member => member.role === 'REVIEWER')
    || newTeam.value.members.some(member => !member.agentId || !member.role)
})
const mermaidTheme = computed(() => uiStore.isLightTheme ? 'default' : 'dark')

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

watch(mermaidTheme, async () => {
  lastRenderedMermaid.value = ''
  await nextTick()
  renderMermaid()
})

watch(reviewOptions, (options) => {
  reviewPreset.value = reviewPresetFor(options)
}, { deep: true })

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
  const firstAgent = agentList.value[0]?.agentId || 'default'
  for (const member of newTeam.value.members) {
    if (!agentList.value.some(agent => agent.agentId === member.agentId)) {
      member.agentId = firstAgent
    }
  }
}

function openCreateTeamDrawer() {
  newTeam.value = defaultTeam()
  normalizeTeamAgents()
  showCreateTeamDrawer.value = true
}

async function selectTeam(team) {
  teamStore.selectTeam(team)
  try {
    const runs = await teamStore.loadTeamRuns(team.teamId)
    if (runs.length > 0) {
      await teamStore.openRun(runs[0])
    }
  } catch (e) {
    ElMessage.error('加载团队运行历史失败: ' + (e.response?.data?.error || e.message))
  }
}

function fillDemoTask() {
  taskInput.value = '在重庆策划一场60人户外团建活动，要求考虑场地、天气风险、预算和人员协调，最终给出可执行方案。'
  if (!selectedTeam.value && teamList.value.length > 0) {
    teamStore.selectTeam(teamList.value[0])
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
    await teamStore.executeTask(taskInput.value, reviewOptions.value)
    await teamStore.loadTeamRuns(selectedTeam.value.teamId).catch(() => {})
    ElMessage.success('Run 已启动')
  } catch (e) {
    ElMessage.error('执行失败: ' + (e.response?.data?.error || e.message))
  }
}

function applyReviewPreset(value = reviewPreset.value) {
  const presets = {
    quality: {
      planReviewEnabled: true,
      subReviewEnabled: true,
      globalReviewEnabled: true,
      simpleFastPathEnabled: false
    },
    balanced: {
      planReviewEnabled: false,
      subReviewEnabled: true,
      globalReviewEnabled: true,
      simpleFastPathEnabled: true
    },
    fastest: {
      planReviewEnabled: false,
      subReviewEnabled: false,
      globalReviewEnabled: false,
      simpleFastPathEnabled: true
    }
  }
  if (value === 'custom') return
  reviewOptions.value = { ...(presets[value] || presets.quality) }
}

function reviewPresetFor(options) {
  const normalized = normalizeReviewOptions(options)
  if (normalized.planReviewEnabled
    && normalized.subReviewEnabled
    && normalized.globalReviewEnabled
    && !normalized.simpleFastPathEnabled) {
    return 'quality'
  }
  if (!normalized.planReviewEnabled
    && normalized.subReviewEnabled
    && normalized.globalReviewEnabled
    && normalized.simpleFastPathEnabled) {
    return 'balanced'
  }
  if (!normalized.planReviewEnabled
    && !normalized.subReviewEnabled
    && !normalized.globalReviewEnabled
    && normalized.simpleFastPathEnabled) {
    return 'fastest'
  }
  return 'custom'
}

function normalizeReviewOptions(options) {
  return {
    planReviewEnabled: true,
    subReviewEnabled: true,
    globalReviewEnabled: true,
    simpleFastPathEnabled: false,
    ...(options || {})
  }
}

async function openRun(run) {
  try {
    await teamStore.openRun(run)
  } catch (e) {
    ElMessage.error('打开 Run 失败: ' + (e.response?.data?.error || e.message))
  }
}

function addExecutor() {
  newTeam.value.members.push({
    role: 'EXECUTOR',
    agentId: agentList.value[0]?.agentId || 'default',
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
  return 'primary'
}

function stepStatusType(status) {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RETRYING') return 'warning'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}

function roleLabel(role) {
  return {
    PLANNER: 'Planner 规划器',
    EXECUTOR: 'Executor 执行者',
    REVIEWER: 'Reviewer 全局审查',
    SUB_REVIEWER: 'StepReviewer 每步审查',
    MERGER: 'MergeAgent 聚合'
  }[role] || role
}

function runStatusLabel(status) {
  return {
    PENDING: '待调度',
    PLANNING: '规划中',
    PLAN_REVIEWING: '规划审查中',
    EXECUTING: '执行中',
    MERGING: '聚合中',
    GLOBAL_REVIEWING: '全局终审中',
    COMPLETED: '已完成',
    FAILED: '失败'
  }[status] || status
}

function stepStatusLabel(status) {
  return {
    PENDING: '待开始',
    BLOCKED: '等待依赖',
    READY: '可执行',
    ASSIGNED: '已分配',
    RUNNING: '执行中',
    COMPLETED: '已完成',
    FAILED: '失败',
    RETRYING: '重试中',
    SUPERSEDED: '已替换'
  }[status] || status
}

function taskLevelLabel(level) {
  return {
    SIMPLE: '简易任务',
    COMPLEX: '复杂任务'
  }[level] || level || '-'
}

function riskLabel(level) {
  return {
    LOW: '低风险',
    HIGH: '高风险'
  }[level] || '低风险'
}

function qualityLabel(status) {
  return {
    PENDING: '待校验',
    PASSED: '已通过',
    RETRY_REQUESTED: '要求重试',
    REVIEW_SKIPPED: '已跳过审查',
    FLAWED_ACCEPTED: '瑕疵放行',
    FAILED: '审查失败'
  }[status] || status || '待校验'
}

function reviewOptionsLabel(options) {
  const normalized = normalizeReviewOptions(options)
  if (normalized.planReviewEnabled
    && normalized.subReviewEnabled
    && normalized.globalReviewEnabled
    && !normalized.simpleFastPathEnabled) {
    return '质量优先'
  }
  if (!normalized.planReviewEnabled
    && normalized.subReviewEnabled
    && normalized.globalReviewEnabled
    && normalized.simpleFastPathEnabled) {
    return '平衡'
  }
  if (!normalized.planReviewEnabled
    && !normalized.subReviewEnabled
    && !normalized.globalReviewEnabled
    && normalized.simpleFastPathEnabled) {
    return '最快'
  }
  const enabled = []
  if (normalized.planReviewEnabled) enabled.push('PlanReview')
  if (normalized.subReviewEnabled) enabled.push('每步 Review')
  if (normalized.globalReviewEnabled) enabled.push('GlobalReview')
  if (normalized.simpleFastPathEnabled) enabled.push('SIMPLE 直返')
  return enabled.length ? enabled.join(' / ') : '自定义'
}

function actionLabel(action) {
  return {
    CONTINUE: '通过',
    SUCCESS: '终审通过',
    RETRY: '重试执行',
    PASS: 'Step 通过',
    REWORK: '重做当前 Step',
    ACCEPT_WITH_RISK: '带风险接受',
    REMERGE: '要求重新合并',
    PARTIAL_REPLAN: '历史局部重规划',
    REPLAN: '历史整体重规划',
    FAILED: '判定失败'
  }[action] || action
}

function eventTypeLabel(type) {
  return {
    RUN_CREATED: 'Run 已创建',
    PLAN_STARTED: '开始规划',
    PLAN_CREATED: '计划已生成',
    PLAN_REVIEW_STARTED: '开始规划审查',
    PLAN_REVIEWED: '规划审查完成',
    TEAM_CONTROL_STARTED: 'DAG 调度启动',
    AGENT_SELECTED: 'Agent 已选择',
    RISK_DECIDED: '风险裁决完成',
    STEP_BLOCKED: 'Step 等待依赖',
    STEP_READY: 'Step 可执行',
    STEP_ASSIGNED: 'Step 已分配',
    STEP_STARTED: 'Step 开始执行',
    STEP_COMPLETED: 'Step 执行完成',
    STEP_FAILED: 'Step 执行失败',
    STEP_RETRY_STARTED: 'Step 开始重试',
    STEP_RETRY_COMPLETED: 'Step 重试完成',
    STEP_SUB_REVIEW_STARTED: '每步 Review 开始',
    STEP_SUB_REVIEWED: '每步 Review 完成',
    STEP_REFLECTION_RECORDED: '写入 Reflexion',
    MERGE_STARTED: '开始聚合',
    MERGE_COMPLETED: '聚合完成',
    CONFLICT_DETECTED: '冲突检测完成',
    ARBITRATION_STARTED: 'Planner 仲裁开始',
    ARBITRATION_COMPLETED: 'Planner 仲裁完成',
    GLOBAL_REVIEW_STARTED: '开始全局终审',
    GLOBAL_REVIEWED: '全局终审完成',
    RETRY_REQUESTED: '要求重试',
    REPLAN_REQUESTED: '要求重规划',
    STEP_TIMEOUT: 'Step 超时熔断',
    RUN_TIMEOUT: 'Run 超时熔断',
    RUN_COMPLETED: 'Run 已完成',
    RUN_FAILED: 'Run 失败'
  }[type] || type
}

function eventDisplayLabel(event) {
  if (isSkippedGlobalReviewEvent(event)) {
    return '已跳过 GlobalReview'
  }
  return eventTypeLabel(event?.type)
}

function eventDisplayMessage(event) {
  if (isSkippedGlobalReviewEvent(event)) {
    return event?.message || '用户跳过 GlobalReview，直接使用 MergeAgent 输出完成'
  }
  return event?.message || ''
}

function isSkippedGlobalReviewEvent(event) {
  return event?.type === 'GLOBAL_REVIEWED'
    && !normalizeReviewOptions(currentRun.value?.reviewOptions).globalReviewEnabled
}

function eventPayload(event) {
  return parseJson(event?.payloadJson)
}

function stepSelection(row) {
  return selectionByStepId.value[row?.stepId]
}

function selectionSourceLabel(source) {
  return {
    MODEL: '模型自主决策',
    RULE_FALLBACK: '规则兜底',
    RULE_SCORE: '规则候选',
    RULE_FAST_PATH: '快路径规则'
  }[source] || '模型决策'
}

function selectionName(selection, fallback = 'Executor') {
  if (!selection) return fallback
  const tags = selection.capabilityTags?.length ? `（${selection.capabilityTags.join('、')}）` : ''
  return `${selection.agentName || selection.agentId || fallback}${tags}`
}

function dependencyLabels(ids = []) {
  const steps = currentRun.value?.steps || []
  return ids.map(id => {
    const step = steps.find(item => item.stepId === id || item.clientStepId === id)
    return step ? `${step.stepIndex}. ${step.title}` : id
  })
}

function parseJson(value) {
  if (!value) return null
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value
    if (typeof parsed === 'string' && (parsed.trim().startsWith('{') || parsed.trim().startsWith('['))) {
      return JSON.parse(parsed)
    }
    return parsed
  } catch (e) {
    return null
  }
}

function formatTime(value) {
  if (!value) return ''
  return new Date(value).toLocaleTimeString()
}

function scrollToFinalReport() {
  const reduceMotion = typeof window !== 'undefined'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  finalReportRef.value?.$el?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
}

function downloadFinalReport() {
  const content = currentRun.value?.finalOutput
  const runId = currentRun.value?.runId
  if (!content || !runId) return
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `team-run-${runId}-final-report.md`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

async function renderMermaid() {
  if (!mermaidRef.value || !currentRun.value?.mermaidDiagram) return
  const renderKey = `${mermaidTheme.value}:${currentRun.value.mermaidDiagram}`
  if (lastRenderedMermaid.value === renderKey && mermaidRef.value.innerHTML) return
  try {
    const mermaid = await import('mermaid')
    mermaid.default.initialize({
      startOnLoad: false,
      theme: mermaidTheme.value,
      flowchart: {
        htmlLabels: true,
        nodeSpacing: 26,
        rankSpacing: 34,
        padding: 8,
        useMaxWidth: false
      },
      themeVariables: {
        fontSize: '10px',
        primaryTextColor: mermaidTheme.value === 'default' ? '#172033' : '#f8fafc',
        lineColor: mermaidTheme.value === 'default' ? '#64748b' : '#cbd5e1'
      }
    })
    const id = 'team-flow-' + currentRun.value.runId.replaceAll('-', '') + '-' + Date.now()
    const { svg } = await mermaid.default.render(id, currentRun.value.mermaidDiagram)
    mermaidRef.value.innerHTML = svg
    const svgEl = mermaidRef.value.querySelector('svg')
    if (svgEl) {
      svgEl.removeAttribute('height')
      svgEl.removeAttribute('width')
      svgEl.style.minWidth = `${Math.max(760, (currentRun.value.steps?.length ?? 1) * 190)}px`
      svgEl.style.maxWidth = 'none'
      svgEl.style.height = 'auto'
    }
    lastRenderedMermaid.value = renderKey
  } catch (e) {
    mermaidRef.value.innerHTML = '<pre>' + currentRun.value.mermaidDiagram + '</pre>'
    lastRenderedMermaid.value = renderKey
  }
}
</script>
