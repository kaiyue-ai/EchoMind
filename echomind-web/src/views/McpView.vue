<template>
  <div class="page-container">
    <div class="page-header">
      <h2>MCP 工具管理</h2>
      <p>Model Context Protocol — 管理外部工具发现与调用</p>
    </div>

    <div style="display: flex; gap: 12px; margin-bottom: 20px;">
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 14px; font-weight: 600; margin-bottom: 4px;">服务器</div>
        <div style="font-size: 20px; font-weight: 700; color: #e4e4e7;">{{ serverInfo.name || '-' }}</div>
      </div>
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 14px; font-weight: 600; margin-bottom: 4px;">协议版本</div>
        <div style="font-size: 20px; font-weight: 700; color: #e4e4e7;">{{ serverInfo.version || '-' }}</div>
      </div>
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 14px; font-weight: 600; margin-bottom: 4px;">工具数量</div>
        <div style="font-size: 20px; font-weight: 700; color: #22c55e;">{{ tools.length }}</div>
      </div>
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 14px; font-weight: 600; margin-bottom: 4px;">状态</div>
        <div style="font-size: 20px;">
          <span class="status-dot active"></span>运行中
        </div>
      </div>
    </div>

    <div style="flex: 1; overflow-y: auto;">
      <h3 style="margin-bottom: 12px; font-size: 16px;">已注册 MCP 工具</h3>
      <div class="card-grid">
        <div v-for="tool in tools" :key="tool.name" class="stat-card">
          <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
            <el-tag type="success" effect="dark" size="small">MCP</el-tag>
            <span style="font-weight: 600;">{{ tool.name }}</span>
          </div>
          <div style="font-size: 13px; color: #a1a1aa; margin-bottom: 12px; line-height: 1.5;">
            {{ tool.description }}
          </div>

          <div v-if="tool.inputSchema?.properties" style="margin-bottom: 12px;">
            <div style="font-size: 12px; font-weight: 600; margin-bottom: 4px; color: #71717a;">参数:</div>
            <div v-for="(prop, key) in tool.inputSchema.properties" :key="key"
                 style="font-size: 12px; color: #a1a1aa; padding: 2px 0;">
              <code>{{ key }}</code>: {{ prop.type }}{{ prop.description ? ' — ' + prop.description : '' }}
            </div>
          </div>

          <el-button size="small" type="primary" @click="openCallDialog(tool)">
            调用工具
          </el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="showCallDialog" :title="'调用: ' + callTarget?.name" width="500px">
      <el-form label-width="80px">
        <el-form-item label="参数 (JSON)">
          <el-input v-model="callArgs" type="textarea" :rows="5"
                    placeholder='{"key": "value"}' />
        </el-form-item>
      </el-form>
      <div v-if="callResult" style="margin-top: 12px; padding: 12px; background: #0a0a0a; border-radius: 8px; border: 1px solid #27272a;">
        <div style="font-weight: 600; margin-bottom: 8px;">
          结果
          <el-tag :type="callResult.isError ? 'danger' : 'success'" size="small" style="margin-left: 8px;">
            {{ callResult.isError ? '错误' : '成功' }}
          </el-tag>
        </div>
        <div v-for="(item, i) in callResult.content" :key="i" style="white-space: pre-wrap;">
          {{ item.text }}
        </div>
      </div>
      <template #footer>
        <el-button @click="showCallDialog = false">关闭</el-button>
        <el-button type="primary" @click="executeCall" :loading="callLoading">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const serverInfo = ref({ name: 'EchoMind-MCP', version: '1.0.0' })
const tools = ref([])
const showCallDialog = ref(false)
const callTarget = ref(null)
const callArgs = ref('{}')
const callResult = ref(null)
const callLoading = ref(false)

onMounted(async () => {
  try {
    serverInfo.value = await api.mcp.serverInfo()
    tools.value = await api.mcp.tools()
  } catch (e) { /* use defaults */ }
})

function openCallDialog(tool) {
  callTarget.value = tool
  callArgs.value = '{}'
  callResult.value = null
  showCallDialog.value = true
}

async function executeCall() {
  if (!callTarget.value) return
  callLoading.value = true
  try {
    const args = JSON.parse(callArgs.value)
    callResult.value = await api.mcp.callTool(callTarget.value.name, args)
  } catch (e) {
    ElMessage.error('调用失败: ' + (e.response?.data?.error || e.message))
  } finally {
    callLoading.value = false
  }
}
</script>
