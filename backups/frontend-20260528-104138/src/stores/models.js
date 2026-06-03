import { defineStore } from 'pinia'
import api from '../api'

/**
 * 模型状态中心。
 *
 * 聊天页和后续模型设置页都从这里读取模型列表，避免重复请求和状态不一致。
 */
export const useModelStore = defineStore('models', {
  state: () => ({
    models: [],
    loading: false,
    switching: false,
    error: null
  }),
  actions: {
    async loadModels(force = false) {
      if (this.loading) return this.models
      if (!force && this.models.length > 0) return this.models
      this.loading = true
      this.error = null
      try {
        this.models = await api.models.list()
        return this.models
      } catch (error) {
        this.error = api.parseError(error, '加载模型失败')
        throw error
      } finally {
        this.loading = false
      }
    },
    async switchModel(providerId, modelName) {
      this.switching = true
      this.error = null
      try {
        return await api.models.switch(providerId, modelName)
      } catch (error) {
        this.error = api.parseError(error, '切换模型失败')
        throw error
      } finally {
        this.switching = false
      }
    }
  }
})
