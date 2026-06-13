import { defineStore } from 'pinia'
import api from '../api'

const POLLING_ACTIVE_DELAY_MS = 800

/**
 * Agent Team状态中心。
 *
 * 团队列表、当前选中团队、执行结果和执行中状态统一维护，
 * 后续加团队持久化或协作事件流时可以只改这一处。
 */
export const useTeamStore = defineStore('team', {
  state: () => ({
    teams: [],
    selectedTeam: null,
    taskInput: '',
    reviewPreset: 'quality',
    reviewOptions: defaultReviewOptions(),
    teamResult: null,
    currentRun: null,
    userRuns: [],
    teamRuns: [],
    pollingTimer: null,
    pollingDelay: POLLING_ACTIVE_DELAY_MS,
    lastRunSnapshot: '',
    pollingInFlight: false,
    loading: false,
    loadingRuns: false,
    creating: false,
    deleting: false,
    executing: false,
    error: null
  }),
  actions: {
    async loadTeams() {
      this.loading = true
      this.error = null
      try {
        this.teams = await api.team.list()
        return this.teams
      } catch (error) {
        this.error = api.parseError(error, '加载团队失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async loadUserRuns() {
      try {
        this.userRuns = await api.team.listUserRuns()
        return this.userRuns
      } catch (error) {
        this.error = api.parseError(error, '加载团队运行历史失败')
        throw error
      }
    },
    async loadTeamRuns(teamId = this.selectedTeam?.teamId) {
      if (!teamId) {
        this.teamRuns = []
        return []
      }
      this.loadingRuns = true
      this.error = null
      try {
        this.teamRuns = await api.team.listRuns(teamId)
        return this.teamRuns
      } catch (error) {
        this.error = api.parseError(error, '加载团队运行历史失败')
        throw error
      } finally {
        this.loadingRuns = false
      }
    },
    selectTeam(team) {
      this.selectedTeam = team
      this.teamResult = null
      this.currentRun = null
      this.teamRuns = []
      this.stopPolling()
    },
    async openRun(run) {
      if (!run || !this.selectedTeam) return null
      this.stopPolling()
      this.currentRun = run
      const detail = await this.refreshRun(run.runId)
      if (detail && !this.isTerminalRun(detail.status)) {
        this.startPolling(detail.runId)
      }
      return detail
    },
    async createTeam(config) {
      this.creating = true
      this.error = null
      try {
        const created = await api.team.create(config)
        await this.loadTeams()
        this.selectedTeam = this.teams.find(team => team.teamId === created.teamId) || created
        return created
      } catch (error) {
        this.error = api.parseError(error, '创建团队失败')
        throw error
      } finally {
        this.creating = false
      }
    },
    async deleteTeam(teamId = this.selectedTeam?.teamId) {
      if (!teamId) return
      this.deleting = true
      this.error = null
      try {
        await api.team.delete(teamId)
        this.stopPolling()
        const wasSelected = this.selectedTeam?.teamId === teamId
        this.teams = this.teams.filter(team => team.teamId !== teamId)
        if (wasSelected) {
          this.selectedTeam = this.teams[0] || null
          this.teamResult = null
          this.currentRun = null
        }
        return true
      } catch (error) {
        this.error = api.parseError(error, '删除团队失败')
        throw error
      } finally {
        this.deleting = false
      }
    },
    async executeTask(task = this.taskInput, reviewOptions = this.reviewOptions) {
      if (!this.selectedTeam || !task?.trim()) return null
      this.executing = true
      this.error = null
      this.teamResult = null
      this.currentRun = null
      try {
        this.currentRun = await api.team.createRun(this.selectedTeam.teamId, task, normalizeReviewOptions(reviewOptions))
        await this.loadTeamRuns(this.selectedTeam.teamId).catch(() => {})
        this.startPolling(this.currentRun.runId)
        return this.currentRun
      } catch (error) {
        this.error = api.parseError(error, '执行团队任务失败')
        throw error
      } finally {
        this.executing = false
      }
    },
    async refreshRun(runId = this.currentRun?.runId) {
      if (!this.selectedTeam || !runId) return null
      try {
        const nextRun = await api.team.getRun(this.selectedTeam.teamId, runId)
        const nextSnapshot = stableRunSnapshot(nextRun)
        if (nextSnapshot !== this.lastRunSnapshot) {
          this.currentRun = nextRun
          this.lastRunSnapshot = nextSnapshot
        }
        this.pollingDelay = POLLING_ACTIVE_DELAY_MS
        if (this.isTerminalRun(nextRun.status)) {
          this.stopPolling()
          this.currentRun = nextRun
          this.teamResult = nextRun
          this.loadTeamRuns(this.selectedTeam.teamId).catch(() => {})
        }
        return nextRun
      } catch (error) {
        this.error = api.parseError(error, '刷新团队任务失败')
        this.stopPolling()
        throw error
      }
    },
    startPolling(runId) {
      this.stopPolling()
      this.pollingDelay = POLLING_ACTIVE_DELAY_MS
      this.lastRunSnapshot = ''
      const tick = () => {
        if (this.pollingInFlight) {
          this.pollingTimer = window.setTimeout(tick, this.pollingDelay)
          return
        }
        this.pollingInFlight = true
        this.refreshRun(runId)
          .catch(() => {})
          .finally(() => {
            this.pollingInFlight = false
            if (!this.pollingTimer || this.isTerminalRun(this.currentRun?.status)) return
            this.pollingTimer = window.setTimeout(tick, this.pollingDelay)
          })
      }
      this.pollingTimer = window.setTimeout(tick, 0)
    },
    stopPolling() {
      if (this.pollingTimer) {
        window.clearTimeout(this.pollingTimer)
        this.pollingTimer = null
      }
      this.pollingDelay = POLLING_ACTIVE_DELAY_MS
      this.pollingInFlight = false
    },
    isTerminalRun(status) {
      return ['COMPLETED', 'FAILED'].includes(status)
    }
  }
})

function defaultReviewOptions() {
  return {
    planReviewEnabled: true,
    subReviewEnabled: true,
    globalReviewEnabled: true,
    simpleFastPathEnabled: false
  }
}

function normalizeReviewOptions(options) {
  return {
    ...defaultReviewOptions(),
    ...(options || {})
  }
}

function stableRunSnapshot(run) {
  if (!run) return ''
  return JSON.stringify({
    status: run.status,
    updatedAt: run.updatedAt,
    finalOutput: run.finalOutput,
    mermaidDiagram: run.mermaidDiagram,
    steps: run.steps,
    events: run.events
  })
}
