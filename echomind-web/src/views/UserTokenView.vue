<template>
  <div class="admin-page user-token-page">
    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert" />

    <section class="admin-metric-grid usage-metrics">
      <div class="admin-metric-card">
        <span class="metric-icon green"><el-icon><User /></el-icon></span>
        <div>
          <span>消费用户</span>
          <strong>{{ formatNumber(uniqueUserCount) }}</strong>
          <small>有 Provider Token 记录</small>
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
        <span class="metric-icon blue"><el-icon><Document /></el-icon></span>
        <div>
          <span>调用次数</span>
          <strong>{{ formatNumber(totals.callCount) }}</strong>
          <small>全部客户端模型调用</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon purple"><el-icon><Connection /></el-icon></span>
        <div>
          <span>模型维度</span>
          <strong>{{ formatNumber(uniqueModelCount) }}</strong>
          <small>按用户 × 模型聚合</small>
        </div>
      </div>
    </section>

    <section class="admin-filter-panel">
      <div class="admin-filter-group">
        <label>
          <span>客户端用户</span>
          <el-select v-model="selectedUserId" class="usage-user-select" filterable>
            <el-option label="全部用户" value="all" />
            <el-option
              v-for="user in userOptions"
              :key="user.userId"
              :label="user.username"
              :value="user.userId"
            />
          </el-select>
        </label>
        <label>
          <span>模型</span>
          <el-select v-model="selectedModelId" class="usage-user-select" filterable>
            <el-option label="全部模型" value="all" />
            <el-option v-for="model in modelOptions" :key="model" :label="model" :value="model" />
          </el-select>
        </label>
        <label>
          <span>搜索</span>
          <el-input v-model="keyword" class="usage-keyword" clearable placeholder="用户名 / 模型 / 用户ID">
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
        <table class="admin-record-table user-token-table">
          <thead>
            <tr>
              <th>客户端用户</th>
              <th>模型</th>
              <th>总 Token</th>
              <th>Prompt</th>
              <th>Completion</th>
              <th>调用次数</th>
              <th>平均耗时</th>
              <th>日配额</th>
              <th>月配额</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in pagedRows" :key="`${row.userId}-${row.modelId}`">
              <td>
                <strong>{{ row.username }}</strong>
                <span>{{ row.userId }}</span>
              </td>
              <td>{{ row.modelId || '-' }}</td>
              <td class="strong-token">{{ formatNumber(row.totals.totalTokens) }}</td>
              <td><span class="token-down">{{ formatNumber(row.totals.promptTokens) }}</span></td>
              <td><span class="token-up">{{ formatNumber(row.totals.completionTokens) }}</span></td>
              <td>{{ formatNumber(row.totals.callCount) }}</td>
              <td>{{ formatDurationMs(row.averageDurationMs) }}</td>
              <td>
                <div class="quota-meter">
                  <span>{{ quotaText(quotaFor(row.userId)?.todayUsedTokens, quotaFor(row.userId)?.dailyLimitTokens) }}</span>
                  <div><i :style="{ width: quotaPercent(quotaFor(row.userId)?.todayUsedTokens, quotaFor(row.userId)?.dailyLimitTokens) + '%' }"></i></div>
                </div>
              </td>
              <td>
                <div class="quota-meter">
                  <span>{{ quotaText(quotaFor(row.userId)?.monthUsedTokens, quotaFor(row.userId)?.monthlyLimitTokens) }}</span>
                  <div><i :style="{ width: quotaPercent(quotaFor(row.userId)?.monthUsedTokens, quotaFor(row.userId)?.monthlyLimitTokens) + '%' }"></i></div>
                </div>
              </td>
              <td>
                <StatusBadge :tone="quotaTone(row.userId)">
                  {{ quotaLabel(row.userId) }}
                </StatusBadge>
              </td>
              <td>
                <div class="row-actions">
                  <el-button type="primary" @click="openUserUsage(row.userId)">
                  查看记录
                  </el-button>
                  <el-button @click="openQuotaEditor(row)">
                    配额
                  </el-button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <el-empty v-if="!loading && filteredRows.length === 0" description="暂无用户 Token 汇总" />
      </div>
    </section>

    <footer class="admin-pagination-bar">
      <span>显示 {{ pageStart }} 至 {{ pageEnd }} 共 {{ filteredRows.length }} 条结果</span>
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
          :total="filteredRows.length"
        />
      </div>
    </footer>

    <el-dialog v-model="quotaDialogVisible" title="编辑用户配额" width="520px">
      <el-form v-if="editingQuota" label-position="top" class="quota-form">
        <el-form-item label="客户端用户">
          <el-input :model-value="`${editingQuota.username} · ${editingQuota.userId}`" disabled />
        </el-form-item>
        <el-form-item label="每日 Token 限额">
          <el-input-number v-model="quotaForm.dailyLimitTokens" :min="0" :step="1000" controls-position="right" />
        </el-form-item>
        <el-form-item label="每月 Token 限额">
          <el-input-number v-model="quotaForm.monthlyLimitTokens" :min="0" :step="10000" controls-position="right" />
        </el-form-item>
        <el-form-item label="配额状态">
          <el-select v-model="quotaForm.status">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quotaDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingQuota" @click="saveQuota">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Box, Connection, Document, Refresh, Search, User } from '@element-plus/icons-vue'
import api from '../api/admin'
import StatusBadge from '../components/workbench/StatusBadge.vue'

const router = useRouter()
const rows = ref([])
const quotas = ref([])
const totals = ref({ promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0 })
const selectedUserId = ref('all')
const selectedModelId = ref('all')
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const pageSize = ref(20)
const currentPage = ref(1)
const quotaDialogVisible = ref(false)
const editingQuota = ref(null)
const savingQuota = ref(false)
const quotaForm = ref({
  dailyLimitTokens: 0,
  monthlyLimitTokens: 0,
  status: 'ACTIVE'
})

const uniqueUserCount = computed(() => new Set(rows.value.map(row => row.userId)).size)
const uniqueModelCount = computed(() => new Set(rows.value.map(row => row.modelId)).size)
const userOptions = computed(() => {
  const byId = new Map()
  rows.value.forEach(row => byId.set(row.userId, { userId: row.userId, username: row.username }))
  return [...byId.values()].sort((a, b) => a.username.localeCompare(b.username))
})
const modelOptions = computed(() => [...new Set(rows.value.map(row => row.modelId).filter(Boolean))].sort())
const filteredRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return rows.value
    .filter(row => selectedUserId.value === 'all' || row.userId === selectedUserId.value)
    .filter(row => selectedModelId.value === 'all' || row.modelId === selectedModelId.value)
    .filter(row => {
      if (!search) return true
      return [row.userId, row.username, row.modelId].some(value => String(value || '').toLowerCase().includes(search))
    })
})
const pagedRows = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredRows.value.slice(start, start + pageSize.value)
})
const pageStart = computed(() => filteredRows.value.length ? ((currentPage.value - 1) * pageSize.value) + 1 : 0)
const pageEnd = computed(() => Math.min(currentPage.value * pageSize.value, filteredRows.value.length))

watch([filteredRows, pageSize], () => {
  const maxPage = Math.max(1, Math.ceil(filteredRows.value.length / pageSize.value))
  if (currentPage.value > maxPage) currentPage.value = maxPage
})

watch([selectedUserId, selectedModelId, keyword], () => {
  currentPage.value = 1
})

onMounted(refresh)

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.usage.userModelTokens()
    const quotaRes = await api.quotas.list()
    totals.value = res.totals || totals.value
    rows.value = res.rows || []
    quotas.value = quotaRes.users || []
  } catch (e) {
    error.value = api.parseError(e, '加载用户 Token 汇总失败')
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  selectedUserId.value = 'all'
  selectedModelId.value = 'all'
  keyword.value = ''
}

function openUserUsage(userId) {
  router.push({ path: '/usage', query: { userId } })
}

function quotaFor(userId) {
  return quotas.value.find(quota => quota.userId === userId) || null
}

function quotaTone(userId) {
  const quota = quotaFor(userId)
  if (!quota || quota.status === 'DISABLED') return 'neutral'
  if (quota.dailyExceeded || quota.monthlyExceeded) return 'danger'
  return 'success'
}

function quotaLabel(userId) {
  const quota = quotaFor(userId)
  if (!quota) return '未配置'
  if (quota.status === 'DISABLED') return '停用'
  if (quota.dailyExceeded || quota.monthlyExceeded) return '超限'
  return '正常'
}

function openQuotaEditor(row) {
  const quota = quotaFor(row.userId)
  editingQuota.value = {
    userId: row.userId,
    username: row.username
  }
  quotaForm.value = {
    dailyLimitTokens: quota?.dailyLimitTokens || 0,
    monthlyLimitTokens: quota?.monthlyLimitTokens || 0,
    status: quota?.status || 'ACTIVE'
  }
  quotaDialogVisible.value = true
}

async function saveQuota() {
  if (!editingQuota.value) return
  savingQuota.value = true
  try {
    const updated = await api.quotas.update(editingQuota.value.userId, {
      dailyLimitTokens: quotaForm.value.dailyLimitTokens || null,
      monthlyLimitTokens: quotaForm.value.monthlyLimitTokens || null,
      status: quotaForm.value.status
    })
    const index = quotas.value.findIndex(quota => quota.userId === updated.userId)
    if (index >= 0) {
      quotas.value.splice(index, 1, updated)
    } else {
      quotas.value.push(updated)
    }
    ElMessage.success('配额已保存')
    quotaDialogVisible.value = false
  } catch (e) {
    error.value = api.parseError(e, '保存配额失败')
  } finally {
    savingQuota.value = false
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

function formatDurationMs(value) {
  const ms = Number(value || 0)
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${Math.round(ms)}ms`
}

function quotaPercent(used, limit) {
  if (!limit) return 0
  return Math.min(100, (Number(used || 0) / Number(limit || 1)) * 100)
}

function quotaText(used, limit) {
  if (!limit) return '未配置'
  return `${formatCompact(used)} / ${formatCompact(limit)}`
}
</script>
