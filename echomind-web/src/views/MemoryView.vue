<template>
  <div class="page-container">
    <div class="page-header">
      <h2>记忆管理</h2>
      <p>查询和管理会话记忆数据</p>
    </div>

    <div style="display: flex; gap: 12px; margin-bottom: 20px;">
      <el-input v-model="searchSessionId" placeholder="输入 Session ID 查看记忆..."
                clearable style="width: 400px;">
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
      <el-button type="primary" @click="loadMemory" :loading="loading" :disabled="!searchSessionId">
        查询
      </el-button>
      <el-button type="danger" @click="clearMemory" :disabled="!searchSessionId || messages.length === 0">
        清除记忆
      </el-button>
    </div>

    <div v-if="messages.length > 0" style="display: flex; gap: 12px; margin-bottom: 20px;">
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 24px; font-weight: 700; color: #e4e4e7;">{{ messages.length }}</div>
        <div style="font-size: 13px; color: #71717a;">消息总数</div>
      </div>
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 24px; font-weight: 700; color: #22c55e;">{{ userCount }}</div>
        <div style="font-size: 13px; color: #71717a;">用户消息</div>
      </div>
      <div class="stat-card" style="flex: 1; text-align: center;">
        <div style="font-size: 24px; font-weight: 700; color: #a1a1aa;">{{ assistantCount }}</div>
        <div style="font-size: 13px; color: #71717a;">AI 响应</div>
      </div>
    </div>

    <div v-if="messages.length > 0" style="flex: 1; overflow-y: auto;">
      <el-timeline>
        <el-timeline-item
          v-for="(msg, i) in messages" :key="i"
          :timestamp="msg.timestamp"
          :type="msg.role === 'user' ? 'primary' : msg.role === 'assistant' ? 'success' : 'warning'"
          :hollow="msg.role === 'system'"
          placement="top"
        >
          <el-card shadow="hover">
            <template #header>
              <el-tag :type="roleTagType(msg.role)" size="small">{{ msg.role }}</el-tag>
              <span style="margin-left: 8px; font-size: 12px; color: #71717a;">
                {{ msg.timestamp }}
              </span>
            </template>
            <div style="white-space: pre-wrap; line-height: 1.6; max-height: 200px; overflow-y: auto;">
              {{ msg.content }}
            </div>
            <div v-if="msg.metadata" style="margin-top: 8px; font-size: 11px; color: #71717a;">
              元数据: {{ JSON.stringify(msg.metadata) }}
            </div>
          </el-card>
        </el-timeline-item>
      </el-timeline>
    </div>

    <div v-if="searched && messages.length === 0" style="text-align: center; color: #71717a; margin-top: 60px;">
      <div style="font-size: 48px; margin-bottom: 16px;">📭</div>
      <div>未找到该 Session 的记忆数据</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const searchSessionId = ref('')
const messages = ref([])
const loading = ref(false)
const searched = ref(false)

const userCount = computed(() => messages.value.filter(m => m.role === 'user').length)
const assistantCount = computed(() => messages.value.filter(m => m.role === 'assistant').length)

function roleTagType(role) {
  if (role === 'user') return 'primary'
  if (role === 'assistant') return 'success'
  return 'warning'
}

async function loadMemory() {
  if (!searchSessionId.value) return
  loading.value = true
  searched.value = true
  try {
    messages.value = await api.memory.get(searchSessionId.value)
  } catch (e) {
    messages.value = []
  } finally {
    loading.value = false
  }
}

async function clearMemory() {
  try {
    await ElMessageBox.confirm('确定清除该会话的所有记忆吗？此操作不可恢复。', '确认清除', { type: 'warning' })
    await api.memory.clear(searchSessionId.value)
    messages.value = []
    ElMessage.success('记忆已清除')
  } catch (e) { /* 取消 */ }
}
</script>
