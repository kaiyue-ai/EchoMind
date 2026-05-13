import { defineStore } from 'pinia'
import api from '../api'

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
    teamResult: null,
    currentRun: null,
    pollingTimer: null,
    loading: false,
    creating: false,
    deleting: false,
    executing: false,
    resuming: false,
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
    selectTeam(team) {
      this.selectedTeam = team
      this.teamResult = null
      this.currentRun = null
      this.stopPolling()
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
    async executeTask(task = this.taskInput) {
      if (!this.selectedTeam || !task?.trim()) return null
      this.executing = true
      this.error = null
      this.teamResult = null
      this.currentRun = null
      try {
        this.currentRun = await api.team.createRun(this.selectedTeam.teamId, task)
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
        this.currentRun = await api.team.getRun(this.selectedTeam.teamId, runId)
        if (this.isTerminalRun(this.currentRun.status)) {
          this.stopPolling()
          this.teamResult = this.currentRun
        }
        return this.currentRun
      } catch (error) {
        this.error = api.parseError(error, '刷新团队任务失败')
        this.stopPolling()
        throw error
      }
    },
    startPolling(runId) {
      this.stopPolling()
      this.pollingTimer = window.setInterval(() => {
        this.refreshRun(runId).catch(() => {})
      }, 1500)
      this.refreshRun(runId).catch(() => {})
    },
    stopPolling() {
      if (this.pollingTimer) {
        window.clearInterval(this.pollingTimer)
        this.pollingTimer = null
      }
    },
    isTerminalRun(status) {
      return ['COMPLETED', 'FAILED', 'NEEDS_CLARIFICATION'].includes(status)
    },
    async resumeRun(answer) {
      if (!this.selectedTeam || !this.currentRun || !answer?.trim()) return null
      this.resuming = true
      this.error = null
      try {
        this.currentRun = await api.team.resumeRun(this.selectedTeam.teamId, this.currentRun.runId, answer)
        this.startPolling(this.currentRun.runId)
        return this.currentRun
      } catch (error) {
        this.error = api.parseError(error, '继续团队任务失败')
        throw error
      } finally {
        this.resuming = false
      }
    }
  }
})
