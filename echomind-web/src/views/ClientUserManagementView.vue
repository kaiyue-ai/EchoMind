<template>
  <div class="admin-page client-user-page">
    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert" />

    <section class="admin-metric-grid usage-metrics">
      <div class="admin-metric-card">
        <span class="metric-icon blue"><el-icon><User /></el-icon></span>
        <div>
          <span>客户端用户</span>
          <strong>{{ formatNumber(totals.totalUsers) }}</strong>
          <small>只统计客户端账号</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon green"><el-icon><CircleCheck /></el-icon></span>
        <div>
          <span>正常账号</span>
          <strong>{{ formatNumber(totals.activeUsers) }}</strong>
          <small>可登录和发起对话</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon purple"><el-icon><ChatDotRound /></el-icon></span>
        <div>
          <span>会话 / 消息</span>
          <strong>{{ formatNumber(totals.totalSessions) }}</strong>
          <small>消息 {{ formatNumber(totals.totalMessages) }}</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon amber"><el-icon><Box /></el-icon></span>
        <div>
          <span>总 Token</span>
          <strong>{{ formatCompact(totals.tokenTotals?.totalTokens) }}</strong>
          <small>调用 {{ formatNumber(totals.tokenTotals?.callCount) }} 次</small>
        </div>
      </div>
    </section>

    <section class="admin-filter-panel">
      <div class="admin-filter-group">
        <label>
          <span>账号状态</span>
          <el-select v-model="statusFilter" class="usage-range-select">
            <el-option label="全部状态" value="all" />
            <el-option label="正常" value="ACTIVE" />
            <el-option label="已封禁" value="DISABLED" />
          </el-select>
        </label>
        <label>
          <span>搜索</span>
          <el-input v-model="keyword" class="usage-keyword" clearable placeholder="用户名 / 用户ID">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </label>
      </div>
      <div class="admin-filter-actions">
        <el-button :loading="loading" @click="refresh">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </section>

    <section class="admin-table-panel">
      <div v-loading="loading" class="admin-table-wrap">
        <table class="admin-record-table client-user-table">
          <thead>
            <tr>
              <th>客户端用户</th>
              <th>状态</th>
              <th>会话</th>
              <th>消息</th>
              <th>总 Token</th>
              <th>调用次数</th>
              <th>创建时间</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in pagedUsers" :key="user.userId">
              <td>
                <strong>{{ user.username }}</strong>
                <span>{{ user.userId }}</span>
              </td>
              <td>
                <StatusBadge :tone="user.status === 'ACTIVE' ? 'success' : 'danger'">
                  {{ user.status === 'ACTIVE' ? '正常' : '已封禁' }}
                </StatusBadge>
              </td>
              <td>{{ formatNumber(user.sessionCount) }}</td>
              <td>{{ formatNumber(user.messageCount) }}</td>
              <td class="strong-token">{{ formatNumber(user.tokenTotals?.totalTokens) }}</td>
              <td>{{ formatNumber(user.tokenTotals?.callCount) }}</td>
              <td>{{ formatDate(user.createdAt) }}</td>
              <td>{{ formatDate(user.updatedAt) }}</td>
              <td>
                <div class="row-actions client-user-actions">
                  <el-button type="primary" @click="openUsage(user.userId)">记录</el-button>
                  <el-button
                    :type="user.status === 'ACTIVE' ? 'warning' : 'success'"
                    :loading="busyUserId === user.userId"
                    @click="toggleStatus(user)"
                  >
                    {{ user.status === 'ACTIVE' ? '封禁' : '解封' }}
                  </el-button>
                  <el-button
                    type="danger"
                    :loading="busyUserId === user.userId"
                    @click="confirmDelete(user)"
                  >
                    删除
                  </el-button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <el-empty v-if="!loading && filteredUsers.length === 0" description="暂无客户端用户" />
      </div>
    </section>

    <footer class="admin-pagination-bar">
      <span>显示 {{ pageStart }} 至 {{ pageEnd }} 共 {{ filteredUsers.length }} 条结果</span>
      <div class="admin-pagination-actions">
        <span>每页:</span>
        <el-select v-model="pageSize" class="page-size-select">
          <el-option :value="20" label="20" />
          <el-option :value="50" label="50" />
          <el-option :value="100" label="100" />
        </el-select>
        <el-pagination
          v-model:current-page="currentPage"
          background
          layout="prev, pager, next"
          :page-size="pageSize"
          :total="filteredUsers.length"
        />
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Box, ChatDotRound, CircleCheck, Refresh, Search, User } from '@element-plus/icons-vue'
import api from '../api/admin'
import StatusBadge from '../components/workbench/StatusBadge.vue'

const router = useRouter()
const users = ref([])
const totals = ref({
  totalUsers: 0,
  activeUsers: 0,
  disabledUsers: 0,
  totalSessions: 0,
  totalMessages: 0,
  tokenTotals: { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0 }
})
const keyword = ref('')
const statusFilter = ref('all')
const loading = ref(false)
const busyUserId = ref('')
const error = ref('')
const pageSize = ref(20)
const currentPage = ref(1)

const filteredUsers = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return users.value
    .filter(user => statusFilter.value === 'all' || user.status === statusFilter.value)
    .filter(user => {
      if (!search) return true
      return [user.username, user.userId].some(value => String(value || '').toLowerCase().includes(search))
    })
})
const pagedUsers = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredUsers.value.slice(start, start + pageSize.value)
})
const pageStart = computed(() => filteredUsers.value.length ? ((currentPage.value - 1) * pageSize.value) + 1 : 0)
const pageEnd = computed(() => Math.min(currentPage.value * pageSize.value, filteredUsers.value.length))

watch([filteredUsers, pageSize], () => {
  const maxPage = Math.max(1, Math.ceil(filteredUsers.value.length / pageSize.value))
  if (currentPage.value > maxPage) currentPage.value = maxPage
})

watch([keyword, statusFilter], () => {
  currentPage.value = 1
})

onMounted(refresh)

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.clientUsers.list()
    totals.value = res.totals || totals.value
    users.value = res.users || []
  } catch (e) {
    error.value = api.parseError(e, '加载客户端用户失败')
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  keyword.value = ''
  statusFilter.value = 'all'
}

function openUsage(userId) {
  router.push({ path: '/usage', query: { userId } })
}

async function toggleStatus(user) {
  const nextStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const label = nextStatus === 'DISABLED' ? '封禁' : '解封'
  await ElMessageBox.confirm(`确认${label}客户端用户「${user.username}」？`, `${label}用户`, {
    type: nextStatus === 'DISABLED' ? 'warning' : 'info',
    confirmButtonText: label,
    cancelButtonText: '取消'
  })
  busyUserId.value = user.userId
  try {
    const updated = await api.clientUsers.updateStatus(user.userId, nextStatus)
    replaceUser(updated)
    ElMessage.success(`${label}成功`)
    await refresh()
  } catch (e) {
    error.value = api.parseError(e, `${label}失败`)
  } finally {
    busyUserId.value = ''
  }
}

async function confirmDelete(user) {
  await ElMessageBox.confirm(
    `删除后会清理该用户的账号、会话、消息、Token 记录、配额和记忆缓存。确认删除「${user.username}」？`,
    '删除客户端用户',
    {
      type: 'error',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      distinguishCancelAndClose: true
    }
  )
  busyUserId.value = user.userId
  try {
    const res = await api.clientUsers.delete(user.userId)
    users.value = users.value.filter(row => row.userId !== user.userId)
    ElMessage.success(`已删除 ${res.username}`)
    await refresh()
  } catch (e) {
    error.value = api.parseError(e, '删除用户失败')
  } finally {
    busyUserId.value = ''
  }
}

function replaceUser(updated) {
  const index = users.value.findIndex(user => user.userId === updated.userId)
  if (index >= 0) {
    users.value.splice(index, 1, updated)
  }
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString()
}

function formatCompact(value) {
  const number = Number(value || 0)
  if (number >= 1_000_000) return `${(number / 1_000_000).toFixed(2)}M`
  if (number >= 1_000) return `${(number / 1_000).toFixed(2)}K`
  return formatNumber(number)
}

function formatDate(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}
</script>
