<template>
  <div class="workspace-page">
    <header class="workspace-header">
      <div>
        <span class="eyebrow">External MCP</span>
        <h1>MCP 工具</h1>
      </div>
      <div class="workspace-header-actions">
        <el-button :loading="loading" @click="mcpStore.loadMcp()">刷新</el-button>
        <el-button type="primary" @click="showMountDrawer = true">挂载服务</el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert">
      <template #default>
        <el-button text size="small" @click="mcpStore.loadMcp()">重试</el-button>
      </template>
    </el-alert>

    <section class="split-layout">
      <div class="panel-block">
        <div class="section-head">
          <div>
            <span class="eyebrow">Servers</span>
            <h2>已挂载服务</h2>
          </div>
          <span class="count-pill">{{ servers.length }}</span>
        </div>
        <ResourceGridSkeleton v-if="loading && servers.length === 0" :count="3" :rows="4" class="stack-list" />
        <div v-else class="stack-list" :class="{ 'is-refreshing': loading }">
          <ResourceCard v-for="server in servers" :key="server.id" :meta="formatConnection(server)">
            <template #title>{{ server.id }}</template>
            <template #actions>
              <StatusBadge :tone="server.running ? 'success' : 'danger'">
                {{ server.running ? '运行中' : '已停止' }}
              </StatusBadge>
            </template>
            <div class="detail-list">
              <span v-if="server.workingDirectory">目录: {{ server.workingDirectory }}</span>
              <span v-if="server.url">URL: {{ server.url }}</span>
              <span v-if="server.endpoint">端点: {{ server.endpoint }}</span>
              <span v-if="server.error" class="danger-text">{{ server.error }}</span>
              <span>工具: {{ server.toolCount ?? '-' }}</span>
            </div>
            <template #footer>
              <el-button size="small" :loading="refreshingId === server.id" @click="mcpStore.refreshServer(server.id)">刷新工具</el-button>
              <el-button size="small" type="danger" :loading="mutatingId === server.id" @click="confirmUnmount(server)">卸载</el-button>
            </template>
          </ResourceCard>
          <el-empty v-if="!loading && servers.length === 0" description="暂无外部 MCP 服务" />
        </div>
      </div>

      <div class="panel-block">
        <div class="section-head">
          <div>
            <span class="eyebrow">Tools</span>
            <h2>工具目录</h2>
          </div>
          <span class="count-pill">{{ tools.length }}</span>
        </div>
        <ResourceGridSkeleton v-if="loading && tools.length === 0" :count="4" :rows="4" class="stack-list" />
        <div v-else class="stack-list" :class="{ 'is-refreshing': loading }">
          <ResourceCard v-for="tool in tools" :key="tool.name">
            <template #title>{{ tool.name }}</template>
            <template #actions><StatusBadge tone="success">MCP</StatusBadge></template>
            <p class="resource-desc">{{ tool.description || '暂无说明' }}</p>
            <div v-if="tool.inputSchema?.properties" class="schema-list">
              <div v-for="(prop, key) in tool.inputSchema.properties" :key="key" class="schema-row">
                <code>{{ key }}</code>
                <span>{{ prop.type }}{{ prop.description ? ' - ' + prop.description : '' }}</span>
              </div>
            </div>
            <template #footer>
              <el-button size="small" type="primary" @click="openCallDrawer(tool)">调用工具</el-button>
            </template>
          </ResourceCard>
          <el-empty v-if="!loading && tools.length === 0" description="暂无外部 MCP 工具" />
        </div>
      </div>
    </section>

    <DrawerForm v-model="showMountDrawer" title="挂载外部 MCP 服务" size="520px">
      <el-form label-position="top" class="stack-form">
        <el-form-item label="服务 ID">
          <el-input v-model="mountForm.id" placeholder="nowcoder-java-interview" />
        </el-form-item>
        <el-form-item label="传输方式">
          <el-select v-model="mountForm.transport" class="full-width">
            <el-option label="stdio 本地子进程" value="stdio" />
            <el-option label="SSE 远程服务" value="sse" />
            <el-option label="Streamable HTTP 远程服务" value="streamable-http" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isStdioMount" label="启动命令">
          <el-input v-model="mountForm.commandText" type="textarea" :rows="5" placeholder="java -jar /app/mcp/demo.jar" />
        </el-form-item>
        <el-form-item v-if="isStdioMount" label="工作目录">
          <el-input v-model="mountForm.workingDirectory" placeholder="/app/mcp" />
        </el-form-item>
        <el-form-item v-if="isRemoteMount" label="服务 URL">
          <el-input v-model="mountForm.url" placeholder="https://mcp.example.com" />
        </el-form-item>
        <el-form-item v-if="isRemoteMount" label="服务端点">
          <el-input v-model="mountForm.endpoint" :placeholder="mountForm.transport === 'sse' ? '/sse' : '/mcp'" />
        </el-form-item>
        <el-form-item v-if="isRemoteMount" label="请求头 JSON">
          <el-input v-model="mountForm.headersText" type="textarea" :rows="4" placeholder='{"Authorization":"Bearer token"}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showMountDrawer = false">取消</el-button>
        <el-button type="primary" :loading="mounting" @click="mountServer">挂载</el-button>
      </template>
    </DrawerForm>

    <DrawerForm v-model="showCallDrawer" :title="'调用工具: ' + callTarget?.name" size="560px">
      <el-form label-position="top" class="stack-form">
        <el-form-item label="参数 JSON">
          <el-input v-model="callArgs" type="textarea" :rows="8" placeholder='{"key": "value"}' />
        </el-form-item>
      </el-form>
      <div v-if="callResult" class="result-box">
        <div class="result-box-title">
          结果
          <el-tag :type="callResult.isError ? 'danger' : 'success'" size="small">
            {{ callResult.isError ? '错误' : '成功' }}
          </el-tag>
        </div>
        <div v-for="(item, i) in callResult.content" :key="i" class="plain-message">{{ item.text }}</div>
      </div>
      <template #footer>
        <el-button @click="showCallDrawer = false">关闭</el-button>
        <el-button type="primary" :loading="calling" @click="executeCall">执行</el-button>
      </template>
    </DrawerForm>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import DrawerForm from '../components/workbench/DrawerForm.vue'
import ResourceCard from '../components/workbench/ResourceCard.vue'
import ResourceGridSkeleton from '../components/workbench/ResourceGridSkeleton.vue'
import StatusBadge from '../components/workbench/StatusBadge.vue'
import { useMcpStore } from '../stores/mcp'

const mcpStore = useMcpStore()
const { servers, tools, loading, calling, mounting, refreshingId, mutatingId, error } = storeToRefs(mcpStore)
const showMountDrawer = ref(false)
const showCallDrawer = ref(false)
const callTarget = ref(null)
const callArgs = ref('{}')
const callResult = ref(null)
const mountForm = reactive({
  id: '',
  transport: 'stdio',
  commandText: '',
  workingDirectory: '',
  url: '',
  endpoint: '',
  headersText: '{}'
})
const isStdioMount = computed(() => mountForm.transport === 'stdio')
const isRemoteMount = computed(() => mountForm.transport !== 'stdio')

onMounted(() => mcpStore.loadMcp().catch(() => {}))

function formatCommand(command) {
  return Array.isArray(command) ? command.join(' ') : ''
}

function formatConnection(server) {
  if (server?.url) {
    return `${server.transport || 'mcp'} ${server.url}${server.endpoint || ''}`
  }
  return formatCommand(server?.command)
}

function parseCommand(text) {
  return text
    .split(/\r?\n/)
    .flatMap(line => line.trim().split(/\s+/))
    .filter(Boolean)
}

async function mountServer() {
  const command = parseCommand(mountForm.commandText)
  if (!mountForm.id.trim()) {
    ElMessage.warning('服务 ID 不能为空')
    return
  }
  if (isStdioMount.value && command.length === 0) {
    ElMessage.warning('启动命令不能为空')
    return
  }
  if (isRemoteMount.value && !mountForm.url.trim()) {
    ElMessage.warning('远程服务 URL 不能为空')
    return
  }
  let headers = {}
  try {
    const parsedHeaders = mountForm.headersText.trim() ? JSON.parse(mountForm.headersText) : {}
    if (parsedHeaders === null || typeof parsedHeaders !== 'object' || Array.isArray(parsedHeaders)) {
      ElMessage.warning('请求头必须是合法 JSON 对象')
      return
    }
    headers = Object.fromEntries(
      Object.entries(parsedHeaders)
        .filter(([name, value]) => name && value !== null && value !== undefined)
        .map(([name, value]) => [name, String(value)])
    )
  } catch {
    ElMessage.warning('请求头必须是合法 JSON 对象')
    return
  }
  try {
    await mcpStore.mountServer({
      id: mountForm.id.trim(),
      transport: mountForm.transport,
      command: isStdioMount.value ? command : [],
      workingDirectory: isStdioMount.value ? (mountForm.workingDirectory.trim() || null) : null,
      url: isRemoteMount.value ? mountForm.url.trim() : null,
      endpoint: isRemoteMount.value ? (mountForm.endpoint.trim() || null) : null,
      headers: isRemoteMount.value ? headers : {}
    })
    ElMessage.success('MCP 服务已挂载')
    mountForm.id = ''
    mountForm.transport = 'stdio'
    mountForm.commandText = ''
    mountForm.workingDirectory = ''
    mountForm.url = ''
    mountForm.endpoint = ''
    mountForm.headersText = '{}'
    showMountDrawer.value = false
  } catch (e) {
    ElMessage.error('挂载失败: ' + (e.response?.data?.error || e.message))
  }
}

async function confirmUnmount(server) {
  try {
    await ElMessageBox.confirm(`确定卸载 ${server.id} 吗？`, '卸载外部 MCP 服务', {
      type: 'warning',
      confirmButtonText: '卸载',
      cancelButtonText: '取消'
    })
    await mcpStore.unmountServer(server.id)
    ElMessage.success('MCP 服务已卸载')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error('卸载失败: ' + (e.response?.data?.error || e.message))
    }
  }
}

function openCallDrawer(tool) {
  callTarget.value = tool
  callArgs.value = '{}'
  callResult.value = null
  showCallDrawer.value = true
}

async function executeCall() {
  if (!callTarget.value) return
  try {
    const args = JSON.parse(callArgs.value)
    callResult.value = await mcpStore.callTool(callTarget.value.name, args)
  } catch (e) {
    ElMessage.error('调用失败: ' + (e.response?.data?.error || e.message))
  }
}
</script>
