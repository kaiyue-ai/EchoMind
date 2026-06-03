<template>
  <div class="workspace-page mcp-page">
    <header class="workspace-header mcp-hero">
      <div>
        <span class="eyebrow">External MCP</span>
        <h1>MCP 服务</h1>
        <p class="workspace-subtitle">
          服务由后端配置挂载，前端只展示运行状态和刷新结果。
        </p>
      </div>
      <div class="workspace-header-actions">
        <el-button
          :loading="loading"
          aria-label="刷新 MCP 服务"
          title="刷新 MCP 服务"
          @click="mcpStore.loadMcp(true)"
        >
          <el-icon><Refresh /></el-icon>
          <span class="desktop-action-label">刷新</span>
        </el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert">
      <template #default>
        <el-button text size="small" @click="mcpStore.loadMcp(true)">重试</el-button>
      </template>
    </el-alert>

    <section class="panel-block mcp-services-panel">
      <div class="section-head">
        <div>
          <span class="eyebrow">Servers</span>
          <h2>服务端已配置服务</h2>
        </div>
        <span class="count-pill">{{ servers.length }}</span>
      </div>

      <ResourceGridSkeleton v-if="loading && servers.length === 0" :count="4" :rows="4" class="mcp-service-grid" />

      <div v-else class="resource-grid mcp-service-grid" :class="{ 'is-refreshing': loading }">
        <ResourceCard v-for="server in servers" :key="server.id" :meta="formatConnection(server)">
          <template #title>{{ server.id }}</template>
          <template #actions>
            <StatusBadge :tone="server.running ? 'success' : 'danger'">
              {{ server.running ? '运行中' : '已停止' }}
            </StatusBadge>
          </template>

          <div class="mcp-service-summary">
            <div class="mcp-service-stat">
              <span>传输</span>
              <strong>{{ server.transport || 'mcp' }}</strong>
            </div>
            <div class="mcp-service-stat">
              <span>工具数</span>
              <strong>{{ server.toolCount ?? 0 }}</strong>
            </div>
          </div>

          <div class="detail-list">
            <span v-if="server.workingDirectory">目录: {{ server.workingDirectory }}</span>
            <span v-if="server.url">URL: {{ server.url }}</span>
            <span v-if="server.endpoint">端点: {{ server.endpoint }}</span>
            <span v-if="server.error" class="danger-text">{{ server.error }}</span>
          </div>

          <template #footer>
            <el-button
              size="small"
              :loading="refreshingId === server.id"
              @click="mcpStore.refreshServer(server.id)"
            >
              <el-icon><Refresh /></el-icon>
              刷新服务
            </el-button>
          </template>
        </ResourceCard>
      </div>

      <el-empty
        v-if="!loading && servers.length === 0"
        class="mcp-empty-state"
        description="暂无服务端配置的外部 MCP 服务"
      >
        <p class="muted-text">在服务端配置 echomind.mcp.external-servers 后重启或刷新即可显示。</p>
      </el-empty>
    </section>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { Refresh } from '@element-plus/icons-vue'
import ResourceCard from '../components/workbench/ResourceCard.vue'
import ResourceGridSkeleton from '../components/workbench/ResourceGridSkeleton.vue'
import StatusBadge from '../components/workbench/StatusBadge.vue'
import { useMcpStore } from '../stores/mcp'

const mcpStore = useMcpStore()
const { servers, loading, refreshingId, error } = storeToRefs(mcpStore)

onMounted(() => mcpStore.loadMcp().catch(() => {}))

function formatCommand(command) {
  return Array.isArray(command) ? command.join(' ') : ''
}

function formatConnection(server) {
  if (server?.url) {
    return `${server.transport || 'mcp'} ${server.url}${server.endpoint || ''}`
  }
  return formatCommand(server?.command) || '服务端配置'
}
</script>
