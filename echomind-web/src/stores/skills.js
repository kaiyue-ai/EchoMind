import { defineStore } from 'pinia'
import api from '../api'

/**
 * Skill状态中心。
 *
 * Skill启停会影响后端CapabilityRegistry，因此所有页面都应该通过这里刷新状态，
 * 避免某个页面仍展示旧的工具可用性。
 */
export const useSkillStore = defineStore('skills', {
  state: () => ({
    skills: [],
    loading: false,
    uploading: false,
    mutatingId: null,
    error: null,
    lastLoadedAt: null
  }),
  getters: {
    enabledCount: (state) => state.skills.filter(skill => skill.state === 'ENABLED').length,
    disabledCount: (state) => state.skills.filter(skill => skill.state !== 'ENABLED').length
  },
  actions: {
    async loadSkills(force = false) {
      if (this.loading) return this.skills
      if (!force && this.skills.length > 0) {
        this.refreshSkills().catch(() => {})
        return this.skills
      }
      this.loading = true
      this.error = null
      try {
        this.skills = await api.skills.list()
        this.lastLoadedAt = Date.now()
        return this.skills
      } catch (error) {
        this.error = api.parseError(error, '加载Skill失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async refreshSkills() {
      this.error = null
      try {
        this.skills = await api.skills.list()
        this.lastLoadedAt = Date.now()
        return this.skills
      } catch (error) {
        this.error = api.parseError(error, '刷新Skill失败')
        throw error
      }
    },
    async uploadSkill(file) {
      this.uploading = true
      this.error = null
      try {
        const uploaded = await api.skills.upload(file)
        await this.loadSkills(true)
        return uploaded
      } catch (error) {
        this.error = api.parseError(error, '上传Skill失败')
        throw error
      } finally {
        this.uploading = false
      }
    },
    async setEnabled(skill, enabled) {
      this.mutatingId = skill.skillId
      this.error = null
      const previousSkills = this.skills.map(item => ({ ...item }))
      this.skills = this.skills.map(item => item.skillId === skill.skillId
        ? { ...item, state: enabled ? 'ENABLED' : 'DISABLED' }
        : item)
      try {
        if (enabled) await api.skills.enable(skill.skillId)
        else await api.skills.disable(skill.skillId)
        await this.refreshSkills().catch(() => {})
      } catch (error) {
        this.skills = previousSkills
        this.error = api.parseError(error, '更新Skill状态失败')
        throw error
      } finally {
        this.mutatingId = null
      }
    },
    async deleteSkill(skill) {
      this.mutatingId = skill.skillId
      this.error = null
      const previousSkills = this.skills
      this.skills = this.skills.filter(item => item.skillId !== skill.skillId)
      try {
        await api.skills.delete(skill.skillId)
        await this.refreshSkills().catch(() => {})
      } catch (error) {
        this.skills = previousSkills
        this.error = api.parseError(error, '删除Skill失败')
        throw error
      } finally {
        this.mutatingId = null
      }
    }
  }
})
