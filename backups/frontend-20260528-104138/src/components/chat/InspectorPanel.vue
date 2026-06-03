<template>
  <aside class="inspector-panel">
    <div class="inspector-mobile-head">
      <strong>运行上下文</strong>
      <el-button text title="关闭上下文" @click="$emit('close')">
        <el-icon><Close /></el-icon>
      </el-button>
    </div>

    <section class="inspector-section">
      <div class="inspector-title">运行上下文</div>
      <div class="metric-grid">
        <div class="metric-cell">
          <span>消息</span>
          <strong>{{ messageCount }}</strong>
        </div>
        <div class="metric-cell">
          <span>Skills</span>
          <strong>{{ enabledSkillCount }}</strong>
        </div>
        <div class="metric-cell">
          <span>MCP</span>
          <strong>{{ runningMcpCount }}</strong>
        </div>
        <div class="metric-cell">
          <span>模型</span>
          <strong>{{ models.length }}</strong>
        </div>
      </div>
    </section>

    <section class="inspector-section">
      <div class="inspector-title">模型</div>
      <el-select :model-value="selectedModel" size="small" class="full-width" @update:model-value="$emit('update:selectedModel', $event)">
        <el-option label="跟随 Agent 默认模型" value="__agent_default__">
          <span>跟随 Agent 默认模型</span>
          <span class="option-tag">{{ agentDefaultModelId || '未配置' }}</span>
        </el-option>
        <el-option
          v-for="model in models"
          :key="model.providerId + ':' + model.modelName"
          :label="model.providerId + '/' + model.modelName"
          :value="model.providerId + ':' + model.modelName"
        >
          <span>{{ model.providerId }}/{{ model.modelName }}</span>
          <span v-if="supportsVision(model)" class="option-tag">VISION</span>
        </el-option>
      </el-select>
      <p v-if="followsAgentDefaultModel" class="field-hint">当前生效：{{ agentDefaultModelId || '未配置' }}</p>
      <p v-if="hasAttachments && !selectedModelSupportsVision" class="field-warning">
        当前模型不支持图片，请切换到带 VISION 能力的模型。
      </p>
    </section>

    <section class="inspector-section">
      <div class="inspector-title">Agent</div>
      <el-select :model-value="selectedAgent" size="small" class="full-width" @update:model-value="$emit('update:selectedAgent', $event)">
        <el-option v-for="agent in agents" :key="agent.agentId" :label="agent.name" :value="agent.agentId" />
      </el-select>
    </section>

    <section class="inspector-section">
      <div class="inspector-title">会话</div>
      <div class="session-chip">{{ sessionId || '尚未创建' }}</div>
      <el-button size="small" text @click="$emit('newSession')">新建会话</el-button>
    </section>

    <section class="inspector-section">
      <div class="inspector-title">已启用 Skill</div>
      <div class="compact-list">
        <span v-for="skill in enabledSkills.slice(0, 8)" :key="skill.skillId" class="mini-pill">
          {{ skill.metadata?.name || skill.skillId }}
        </span>
        <span v-if="enabledSkills.length === 0" class="muted-text">暂无启用 Skill</span>
      </div>
    </section>

    <section class="inspector-section">
      <div class="inspector-title">外部 MCP</div>
      <div class="compact-list">
        <span v-for="server in runningServers.slice(0, 6)" :key="server.id" class="mini-pill">
          {{ server.id }}
        </span>
        <span v-if="runningServers.length === 0" class="muted-text">暂无运行中服务</span>
      </div>
    </section>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { Close } from '@element-plus/icons-vue'

const props = defineProps({
  models: { type: Array, default: () => [] },
  agents: { type: Array, default: () => [] },
  skills: { type: Array, default: () => [] },
  servers: { type: Array, default: () => [] },
  agentDefaultModelId: { type: String, default: '' },
  selectedModel: { type: String, default: '' },
  selectedAgent: { type: String, default: 'default' },
  selectedModelSupportsVision: { type: Boolean, default: false },
  hasAttachments: { type: Boolean, default: false },
  sessionId: { type: String, default: null },
  messageCount: { type: Number, default: 0 }
})

defineEmits(['update:selectedModel', 'update:selectedAgent', 'newSession', 'close'])

const enabledSkills = computed(() => props.skills.filter(skill => skill.state === 'ENABLED'))
const enabledSkillCount = computed(() => enabledSkills.value.length)
const runningServers = computed(() => props.servers.filter(server => server.running))
const runningMcpCount = computed(() => runningServers.value.length)
const followsAgentDefaultModel = computed(() => !props.selectedModel || props.selectedModel === '__agent_default__')

function supportsVision(model) {
  return Array.isArray(model?.capabilities)
    && model.capabilities.some(c => String(c).toUpperCase() === 'VISION')
}
</script>
