<template>
  <div class="admin-page usage-page">
    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert" />

    <section class="admin-metric-grid usage-metrics">
      <div class="admin-metric-card">
        <span class="metric-icon blue"><el-icon><Document /></el-icon></span>
        <div>
          <span>总请求数</span>
          <strong>{{ formatNumber(totals.callCount) }}</strong>
          <small>所选范围内</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon amber"><el-icon><Box /></el-icon></span>
        <div>
          <span>总 Token</span>
          <strong>{{ formatCompact(totals.totalTokens) }}</strong>
          <small>输入: {{ formatCompact(totals.promptTokens) }} / 输出: {{ formatCompact(totals.completionTokens) }}</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon green"><el-icon><User /></el-icon></span>
        <div>
          <span>客户端用户</span>
          <strong>{{ formatNumber(users.length) }}</strong>
          <small>已隔离统计</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon purple"><el-icon><Timer /></el-icon></span>
        <div>
          <span>平均耗时</span>
          <strong>{{ averageDuration }}</strong>
          <small>每次请求</small>
        </div>
      </div>
    </section>

    <section class="admin-filter-panel">
      <div class="admin-filter-group">
        <label>
          <span>客户端用户</span>
          <el-select v-model="selectedUserId" class="usage-user-select" filterable @change="loadCalls">
            <el-option label="全部用户" value="all" />
            <el-option
              v-for="user in users"
              :key="user.userId"
              :label="`${user.username} · ${formatNumber(user.totals.totalTokens)} Token`"
              :value="user.userId"
            />
          </el-select>
        </label>
        <label>
          <span>时间范围</span>
          <el-select v-model="range" class="usage-range-select">
            <el-option label="近 24 小时" value="1d" />
            <el-option label="近 7 天" value="7d" />
            <el-option label="近 30 天" value="30d" />
            <el-option label="全部时间" value="all" />
          </el-select>
        </label>
        <label>
          <span>搜索</span>
          <el-input
            v-model="keyword"
            class="usage-keyword"
            clearable
            placeholder="TraceID / 模型 / 会话"
          >
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
        <el-button type="primary" @click="exportCsv">
          <el-icon><Download /></el-icon>
          导出 CSV
        </el-button>
      </div>
    </section>

    <section class="admin-table-panel">
      <div v-loading="loading || loadingCalls" class="admin-table-wrap">
        <table class="admin-record-table">
          <thead>
            <tr>
              <th>客户端用户</th>
              <th>模型</th>
              <th>Agent</th>
              <th>会话</th>
              <th>类型</th>
              <th>计量模式</th>
              <th>TOKEN</th>
              <th>TraceID</th>
              <th>状态</th>
              <th>耗时</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="call in pagedCalls" :key="call.id">
              <td>
                <strong>{{ call.username || call.userId }}</strong>
                <span>{{ shortId(call.userId) }}</span>
              </td>
              <td>{{ call.modelId || '-' }}</td>
              <td>{{ call.agentId || '-' }}</td>
              <td><code>{{ shortId(call.sessionId) }}</code></td>
              <td><span class="admin-chip blue-chip">{{ operationLabel(call.operation) }}</span></td>
              <td><span class="admin-chip">{{ call.usageSource || 'PROVIDER' }}</span></td>
              <td class="token-cell">
                <div>
                  <span class="token-down">↓ {{ formatNumber(call.promptTokens) }}</span>
                  <span class="token-up">↑ {{ formatNumber(call.completionTokens) }}</span>
                </div>
                <small>Σ {{ formatNumber(call.totalTokens) }}</small>
              </td>
              <td class="trace-cell">
                <button type="button" @click="openTrace(call.traceId)">{{ shortId(call.traceId) }}</button>
              </td>
              <td>
                <StatusBadge :tone="call.status === 'OK' ? 'success' : 'danger'">
                  {{ call.status }}
                </StatusBadge>
              </td>
              <td>{{ formatDurationMs(call.durationMs) }}</td>
              <td>{{ formatTime(call.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
        <el-empty v-if="!loading && !loadingCalls && filteredCalls.length === 0" description="暂无调用记录" />
      </div>
    </section>

    <footer class="admin-pagination-bar">
      <span>显示 {{ pageStart }} 至 {{ pageEnd }} 共 {{ filteredCalls.length }} 条结果</span>
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
          :total="filteredCalls.length"
        />
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Box, Document, Download, Refresh, Search, Timer, User } from '@element-plus/icons-vue'
import api from '../api/admin'
import StatusBadge from '../components/workbench/StatusBadge.vue'

const router = useRouter()
const users = ref([])
const calls = ref([])
const selectedUserId = ref('all')
const range = ref('7d')
const keyword = ref('')
const loading = ref(false)
const loadingCalls = ref(false)
const error = ref('')
const pageSize = ref(20)
const currentPage = ref(1)

const totals = computed(() => selectedListTotals.value || { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0 })
const selectedListTotals = ref(null)
const filteredCalls = computed(() => {
  const cutoff = rangeCutoff.value
  const search = keyword.value.trim().toLowerCase()
  return calls.value
    .filter(call => !cutoff || new Date(call.createdAt).getTime() >= cutoff)
    .filter(call => {
      if (!search) return true
      return [
        call.traceId,
        call.modelId,
        call.sessionId,
        call.operation,
        call.username,
        call.userId
      ].some(value => String(value || '').toLowerCase().includes(search))
    })
})
const pagedCalls = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredCalls.value.slice(start, start + pageSize.value)
})
const pageStart = computed(() => filteredCalls.value.length ? ((currentPage.value - 1) * pageSize.value) + 1 : 0)
const pageEnd = computed(() => Math.min(currentPage.value * pageSize.value, filteredCalls.value.length))
const rangeCutoff = computed(() => {
  if (range.value === 'all') return 0
  const days = range.value === '1d' ? 1 : range.value === '30d' ? 30 : 7
  return Date.now() - days * 24 * 60 * 60 * 1000
})
const averageDuration = computed(() => {
  const values = filteredCalls.value.map(call => Number(call.durationMs || 0)).filter(Boolean)
  if (!values.length) return '0s'
  return formatDurationMs(values.reduce((sum, value) => sum + value, 0) / values.length)
})

watch([filteredCalls, pageSize], () => {
  const maxPage = Math.max(1, Math.ceil(filteredCalls.value.length / pageSize.value))
  if (currentPage.value > maxPage) {
    currentPage.value = maxPage
  }
})

watch([range, keyword], () => {
  currentPage.value = 1
})

onMounted(refresh)

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.usage.users()
    selectedListTotals.value = res.totals
    users.value = res.users || []
    if (selectedUserId.value !== 'all' && !users.value.some(user => user.userId === selectedUserId.value)) {
      selectedUserId.value = 'all'
    }
    if (typeof router.currentRoute.value.query.userId === 'string') {
      const routedUserId = router.currentRoute.value.query.userId
      if (routedUserId === 'all' || users.value.some(user => user.userId === routedUserId)) {
        selectedUserId.value = routedUserId
      }
    }
    await loadCalls()
  } catch (e) {
    error.value = api.parseError(e, '加载用户用量失败')
  } finally {
    loading.value = false
  }
}

async function loadCalls() {
  loadingCalls.value = true
  error.value = ''
  currentPage.value = 1
  try {
    if (selectedUserId.value === 'all') {
      const res = await api.usage.calls({ limit: 1000 })
      calls.value = res.calls || []
      return
    }
    const res = await api.usage.userCalls(selectedUserId.value, { limit: 500 })
    calls.value = (res.calls || []).map(call => ({
      ...call,
      userId: res.userId,
      username: res.username
    }))
  } catch (e) {
    error.value = api.parseError(e, '加载调用明细失败')
  } finally {
    loadingCalls.value = false
  }
}

function openTrace(traceId) {
  router.push({ path: '/traces', query: { traceId } })
}

function resetFilters() {
  selectedUserId.value = 'all'
  range.value = '7d'
  keyword.value = ''
  loadCalls()
}

function exportCsv() {
  const header = ['user', 'userId', 'model', 'agent', 'sessionId', 'operation', 'promptTokens', 'completionTokens', 'totalTokens', 'traceId', 'status', 'durationMs', 'createdAt']
  const lines = filteredCalls.value.map(call => header.map(key => csvValue(call[key] ?? '')).join(','))
  const blob = new Blob([[header.join(','), ...lines].join('\n')], { type: 'text/csv;charset=utf-8' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `echomind-usage-${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}

function csvValue(value) {
  return `"${String(value).replaceAll('"', '""')}"`
}

function operationLabel(operation) {
  if (!operation) return '-'
  if (operation.includes('stream')) return '流式'
  if (operation.includes('sync')) return '同步'
  return operation.replace('echomind.chat.', '')
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

function formatDurationMs(value) {
  const ms = Number(value || 0)
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${Math.round(ms)}ms`
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function shortId(value) {
  const text = String(value || '-')
  if (text.length <= 12) return text
  return `${text.slice(0, 6)}...${text.slice(-4)}`
}
</script>
