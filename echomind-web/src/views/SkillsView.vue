<template>
  <div class="workspace-page">
    <header class="workspace-header">
      <div>
        <span class="eyebrow">Capabilities</span>
        <h1>Skill 市场</h1>
      </div>
      <div class="workspace-header-actions">
        <el-button :loading="loading" @click="skillStore.loadSkills()">刷新</el-button>
        <el-upload :before-upload="uploadSkill" :show-file-list="false" accept=".jar">
          <el-button type="primary" :loading="uploading">上传 Skill JAR</el-button>
        </el-upload>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert">
      <template #default>
        <el-button text size="small" @click="skillStore.loadSkills()">重试</el-button>
      </template>
    </el-alert>

    <section class="metric-strip">
      <div class="metric-card">
        <span>已安装</span>
        <strong>{{ skills.length }}</strong>
      </div>
      <div class="metric-card">
        <span>已启用</span>
        <strong>{{ enabledCount }}</strong>
      </div>
      <div class="metric-card">
        <span>未启用</span>
        <strong>{{ disabledCount }}</strong>
      </div>
    </section>

    <ResourceGridSkeleton v-if="showInitialSkeleton" />
    <div v-else class="resource-grid" :class="{ 'is-refreshing': loading }">
      <ResourceCard v-for="skill in skills" :key="skill.skillId" :meta="`v${skill.metadata?.version || '-'}`">
        <template #title>{{ skill.metadata?.name || skill.skillId }}</template>
        <template #actions>
          <StatusBadge :tone="skill.state === 'ENABLED' ? 'success' : 'neutral'">
            {{ skill.state === 'ENABLED' ? '启用' : skill.state }}
          </StatusBadge>
        </template>
        <p class="resource-desc">{{ skill.metadata?.description || '暂无说明' }}</p>
        <div class="tag-row">
          <el-tag v-for="tag in skill.metadata?.tags || []" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
        </div>
        <div class="detail-list">
          <span>ID: {{ skill.skillId }}</span>
          <span>作者: {{ skill.metadata?.author || 'Unknown' }}</span>
        </div>
        <template #footer>
          <el-button
            v-if="skill.state === 'DISABLED' || skill.state === 'LOADED'"
            size="small"
            type="success"
            :loading="mutatingId === skill.skillId"
            @click="toggleSkill(skill, true)"
          >
            启用
          </el-button>
          <el-button
            v-if="skill.state === 'ENABLED'"
            size="small"
            type="warning"
            :loading="mutatingId === skill.skillId"
            @click="toggleSkill(skill, false)"
          >
            禁用
          </el-button>
          <el-button size="small" type="danger" text :loading="mutatingId === skill.skillId" @click="deleteSkill(skill)">
            删除
          </el-button>
        </template>
      </ResourceCard>
      <el-empty v-if="!loading && skills.length === 0" description="暂无 Skill" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import ResourceCard from '../components/workbench/ResourceCard.vue'
import ResourceGridSkeleton from '../components/workbench/ResourceGridSkeleton.vue'
import StatusBadge from '../components/workbench/StatusBadge.vue'
import { useSkillStore } from '../stores/skills'

const skillStore = useSkillStore()
const { skills, loading, uploading, mutatingId, error, enabledCount, disabledCount } = storeToRefs(skillStore)
const showInitialSkeleton = computed(() => loading.value && skills.value.length === 0)

onMounted(() => skillStore.loadSkills().catch(() => {}))

async function uploadSkill(file) {
  try {
    await skillStore.uploadSkill(file)
    ElMessage.success('Skill 上传成功: ' + file.name)
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.response?.data?.error || e.message))
  }
  return false
}

async function toggleSkill(skill, enable) {
  try {
    await skillStore.setEnabled(skill, enable)
    ElMessage.success(enable ? '已启用' : '已禁用')
  } catch (e) {
    ElMessage.error('操作失败: ' + (e.response?.data?.error || e.message))
  }
}

async function deleteSkill(skill) {
  try {
    await ElMessageBox.confirm('确定要删除该 Skill 吗？', '确认删除', { type: 'warning' })
    await skillStore.deleteSkill(skill)
    ElMessage.success('已删除')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error('删除失败: ' + (e.response?.data?.error || e.message))
    }
  }
}
</script>
