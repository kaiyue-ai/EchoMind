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
    error: null
  }),
  getters: {
    enabledCount: (state) => state.skills.filter(skill => skill.state === 'ENABLED').length,
    disabledCount: (state) => state.skills.filter(skill => skill.state !== 'ENABLED').length
  },
  actions: {
    async loadSkills() {
      this.loading = true
      this.error = null
      try {
        this.skills = await api.skills.list()
        return this.skills
      } catch (error) {
        this.error = api.parseError(error, '加载Skill失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async uploadSkill(file) {
      this.uploading = true
      this.error = null
      try {
        const uploaded = await api.skills.upload(file)
        await this.loadSkills()
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
      try {
        if (enabled) await api.skills.enable(skill.skillId)
        else await api.skills.disable(skill.skillId)
        await this.loadSkills()
      } catch (error) {
        this.error = api.parseError(error, '更新Skill状态失败')
        throw error
      } finally {
        this.mutatingId = null
      }
    },
    async deleteSkill(skill) {
      this.mutatingId = skill.skillId
      this.error = null
      try {
        await api.skills.delete(skill.skillId)
        await this.loadSkills()
      } catch (error) {
        this.error = api.parseError(error, '删除Skill失败')
        throw error
      } finally {
        this.mutatingId = null
      }
    }
  }
})
