<template>
  <div class="page-container">
    <div class="page-header" style="display: flex; justify-content: space-between; align-items: flex-start;">
      <div>
        <h2>Skill 市场</h2>
        <p>管理 Agent 技能：上传、启用、禁用</p>
      </div>
      <el-upload :before-upload="uploadSkill" :show-file-list="false" accept=".jar">
        <el-button type="primary">上传 Skill JAR</el-button>
      </el-upload>
    </div>

    <div style="display: flex; gap: 12px; margin-bottom: 20px;">
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 28px; font-weight: 700; color: #e4e4e7;">{{ skills.length }}</div>
        <div style="font-size: 13px; color: #71717a;">已安装</div>
      </div>
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 28px; font-weight: 700; color: #22c55e;">{{ enabledCount }}</div>
        <div style="font-size: 13px; color: #71717a;">已启用</div>
      </div>
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 28px; font-weight: 700; color: #71717a;">{{ disabledCount }}</div>
        <div style="font-size: 13px; color: #71717a;">已禁用</div>
      </div>
    </div>

    <div class="card-grid" style="flex: 1; overflow-y: auto;">
      <div v-for="skill in skills" :key="skill.skillId" class="stat-card skill-card">
        <div class="skill-status">
          <el-tag :type="skill.state === 'ENABLED' ? 'success' : 'info'" size="small" effect="dark">
            {{ skill.state === 'ENABLED' ? '启用' : skill.state }}
          </el-tag>
        </div>

        <div class="skill-name">{{ skill.metadata?.name || skill.skillId }}</div>
        <div class="skill-version">v{{ skill.metadata?.version }}</div>
        <div class="skill-desc">{{ skill.metadata?.description }}</div>

        <div class="skill-tags">
          <el-tag v-for="tag in skill.metadata?.tags || []" :key="tag" size="small" type="info" effect="plain">
            {{ tag }}
          </el-tag>
        </div>

        <div style="margin-top: 8px; font-size: 11px; color: #71717a;">
          {{ skill.metadata?.author || 'Unknown' }}
        </div>

        <div style="margin-top: 12px; display: flex; gap: 8px;">
          <el-button v-if="skill.state === 'DISABLED' || skill.state === 'LOADED'"
                     size="small" type="success" @click="toggleSkill(skill, true)">
            启用
          </el-button>
          <el-button v-if="skill.state === 'ENABLED'"
                     size="small" type="warning" @click="toggleSkill(skill, false)">
            禁用
          </el-button>
          <el-button size="small" type="danger" @click="deleteSkill(skill)" text>
            删除
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const skills = ref([])

const enabledCount = computed(() => skills.value.filter(s => s.state === 'ENABLED').length)
const disabledCount = computed(() => skills.value.filter(s => s.state !== 'ENABLED').length)

onMounted(() => loadSkills())

async function loadSkills() {
  try { skills.value = await api.skills.list() } catch (e) { /* ignore */ }
}

async function uploadSkill(file) {
  try {
    await api.skills.upload(file)
    ElMessage.success('Skill 上传成功: ' + file.name)
    await loadSkills()
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.response?.data?.error || e.message))
  }
  return false
}

async function toggleSkill(skill, enable) {
  try {
    if (enable) { await api.skills.enable(skill.skillId) }
    else { await api.skills.disable(skill.skillId) }
    ElMessage.success(enable ? '已启用' : '已禁用')
    await loadSkills()
  } catch (e) { ElMessage.error('操作失败') }
}

async function deleteSkill(skill) {
  try {
    await ElMessageBox.confirm('确定要删除该 Skill 吗？', '确认删除', { type: 'warning' })
    await api.skills.delete(skill.skillId)
    ElMessage.success('已删除')
    await loadSkills()
  } catch (e) { /* 取消 */ }
}
</script>
