<template>
  <div class="admin-page provider-budget-page">
    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert" />

    <section class="admin-metric-grid usage-metrics">
      <div class="admin-metric-card">
        <span class="metric-icon blue"><el-icon><Connection /></el-icon></span>
        <div>
          <span>Provider</span>
          <strong>{{ formatNumber(budgets.length) }}</strong>
          <small>已发现模型提供商</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon green"><el-icon><CircleCheck /></el-icon></span>
        <div>
          <span>启用预算</span>
          <strong>{{ formatNumber(activeBudgetCount) }}</strong>
          <small>会参与调用前拦截</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon amber"><el-icon><Warning /></el-icon></span>
        <div>
          <span>预警或超限</span>
          <strong>{{ formatNumber(attentionBudgetCount) }}</strong>
          <small>按 Provider 总用量计算</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon purple"><el-icon><Box /></el-icon></span>
        <div>
          <span>今日 Token</span>
          <strong>{{ formatCompact(todayTotalTokens) }}</strong>
          <small>全部 Provider 合计</small>
        </div>
      </div>
    </section>

    <section class="admin-filter-panel">
      <div class="admin-filter-group">
        <label>
          <span>状态</span>
          <el-select v-model="statusFilter" class="usage-range-select">
            <el-option label="全部状态" value="all" />
            <el-option label="启用" value="ACTIVE" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </label>
        <label>
          <span>搜索</span>
          <el-input v-model="keyword" class="usage-keyword" clearable placeholder="Provider ID">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </label>
      </div>
      <div class="admin-filter-actions">
        <el-button :loading="loading" @click="refresh">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" :loading="saving" @click="saveBudgets">
          <el-icon><Check /></el-icon>
          保存预算
        </el-button>
      </div>
    </section>

    <section class="admin-table-panel">
      <div v-loading="loading" class="admin-table-wrap">
        <table class="admin-record-table provider-budget-table">
          <thead>
            <tr>
              <th>Provider</th>
              <th>状态</th>
              <th>日预算</th>
              <th>周预算</th>
              <th>月预算</th>
              <th>预警阈值</th>
              <th>当前状态</th>
              <th>更新时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="budget in filteredBudgets" :key="budget.providerId">
              <td>
                <strong>{{ budget.providerId }}</strong>
                <span>Provider total budget</span>
              </td>
              <td>
                <el-switch
                  v-model="budget.status"
                  class="admin-switch"
                  active-value="ACTIVE"
                  inactive-value="DISABLED"
                  aria-label="启停 Provider Token 预算"
                />
              </td>
              <td>
                <BudgetLimitEditor
                  v-model="budget.dailyLimitTokens"
                  :used="budget.todayUsedTokens"
                  :percent="budget.dailyUsagePercent"
                />
              </td>
              <td>
                <BudgetLimitEditor
                  v-model="budget.weeklyLimitTokens"
                  :used="budget.weekUsedTokens"
                  :percent="budget.weeklyUsagePercent"
                />
              </td>
              <td>
                <BudgetLimitEditor
                  v-model="budget.monthlyLimitTokens"
                  :used="budget.monthUsedTokens"
                  :percent="budget.monthlyUsagePercent"
                />
              </td>
              <td>
                <el-input-number
                  v-model="budget.warningThresholdPercent"
                  class="budget-threshold-input"
                  :min="1"
                  :max="100"
                  controls-position="right"
                />
              </td>
              <td>
                <StatusBadge :tone="budgetTone(budget)">
                  {{ budgetLabel(budget) }}
                </StatusBadge>
              </td>
              <td>{{ formatTime(budget.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <el-empty v-if="!loading && filteredBudgets.length === 0" description="暂无 Provider 预算" />
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, ref } from 'vue'
import { ElMessage, ElInputNumber } from 'element-plus'
import { Box, Check, CircleCheck, Connection, Refresh, Search, Warning } from '@element-plus/icons-vue'
import api from '../api/admin'
import StatusBadge from '../components/workbench/StatusBadge.vue'

const BudgetLimitEditor = defineComponent({
  name: 'BudgetLimitEditor',
  props: {
    modelValue: { type: [Number, String, null], default: null },
    used: { type: [Number, String], default: 0 },
    percent: { type: [Number, String], default: 0 }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('div', { class: 'budget-limit-editor' }, [
      h(ElInputNumber, {
        modelValue: props.modelValue || 0,
        min: 0,
        step: 10000,
        controlsPosition: 'right',
        'onUpdate:modelValue': value => emit('update:modelValue', value || null)
      }),
      h('div', { class: 'quota-meter' }, [
        h('span', null, `${formatCompact(props.used)} / ${props.modelValue ? formatCompact(props.modelValue) : '未配置'}`),
        h('div', null, [
          h('i', { style: { width: `${Math.min(100, Number(props.percent || 0))}%` } })
        ])
      ])
    ])
  }
})

const budgets = ref([])
const keyword = ref('')
const statusFilter = ref('all')
const loading = ref(false)
const saving = ref(false)
const error = ref('')

const filteredBudgets = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return budgets.value
    .filter(budget => statusFilter.value === 'all' || budget.status === statusFilter.value)
    .filter(budget => !search || String(budget.providerId || '').toLowerCase().includes(search))
})
const activeBudgetCount = computed(() => budgets.value.filter(budget => budget.status === 'ACTIVE').length)
const attentionBudgetCount = computed(() => budgets.value.filter(budget =>
  budget.dailyExceeded || budget.weeklyExceeded || budget.monthlyExceeded
  || budget.dailyWarning || budget.weeklyWarning || budget.monthlyWarning
).length)
const todayTotalTokens = computed(() => budgets.value.reduce((sum, budget) => sum + Number(budget.todayUsedTokens || 0), 0))

onMounted(refresh)

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.providerBudgets.list()
    budgets.value = (res.budgets || []).map(normalizeBudget)
  } catch (e) {
    error.value = api.parseError(e, '加载 Provider Token 预算失败')
  } finally {
    loading.value = false
  }
}

async function saveBudgets() {
  saving.value = true
  error.value = ''
  try {
    const res = await api.providerBudgets.update({
      budgets: budgets.value.map(budget => ({
        providerId: budget.providerId,
        dailyLimitTokens: normalizeLimit(budget.dailyLimitTokens),
        weeklyLimitTokens: normalizeLimit(budget.weeklyLimitTokens),
        monthlyLimitTokens: normalizeLimit(budget.monthlyLimitTokens),
        warningThresholdPercent: normalizeThreshold(budget.warningThresholdPercent),
        status: budget.status || 'ACTIVE'
      }))
    })
    budgets.value = (res.budgets || []).map(normalizeBudget)
    ElMessage.success('Provider Token 预算已保存')
  } catch (e) {
    error.value = api.parseError(e, '保存 Provider Token 预算失败')
  } finally {
    saving.value = false
  }
}

function normalizeBudget(budget) {
  return {
    providerId: budget.providerId,
    dailyLimitTokens: budget.dailyLimitTokens || null,
    weeklyLimitTokens: budget.weeklyLimitTokens || null,
    monthlyLimitTokens: budget.monthlyLimitTokens || null,
    warningThresholdPercent: normalizeThreshold(budget.warningThresholdPercent),
    status: budget.status || 'ACTIVE',
    todayUsedTokens: Number(budget.todayUsedTokens || 0),
    weekUsedTokens: Number(budget.weekUsedTokens || 0),
    monthUsedTokens: Number(budget.monthUsedTokens || 0),
    dailyUsagePercent: Number(budget.dailyUsagePercent || 0),
    weeklyUsagePercent: Number(budget.weeklyUsagePercent || 0),
    monthlyUsagePercent: Number(budget.monthlyUsagePercent || 0),
    dailyExceeded: Boolean(budget.dailyExceeded),
    weeklyExceeded: Boolean(budget.weeklyExceeded),
    monthlyExceeded: Boolean(budget.monthlyExceeded),
    dailyWarning: Boolean(budget.dailyWarning),
    weeklyWarning: Boolean(budget.weeklyWarning),
    monthlyWarning: Boolean(budget.monthlyWarning),
    updatedAt: budget.updatedAt
  }
}

function budgetTone(budget) {
  if (budget.status === 'DISABLED') return 'neutral'
  if (budget.dailyExceeded || budget.weeklyExceeded || budget.monthlyExceeded) return 'danger'
  if (budget.dailyWarning || budget.weeklyWarning || budget.monthlyWarning) return 'warning'
  return 'success'
}

function budgetLabel(budget) {
  if (budget.status === 'DISABLED') return '停用'
  if (budget.dailyExceeded || budget.weeklyExceeded || budget.monthlyExceeded) return '超限'
  if (budget.dailyWarning || budget.weeklyWarning || budget.monthlyWarning) return '预警'
  return '正常'
}

function normalizeLimit(value) {
  const number = Number(value || 0)
  return number > 0 ? number : null
}

function normalizeThreshold(value) {
  const number = Number(value || 80)
  return Math.min(100, Math.max(1, Math.round(number)))
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

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}
</script>
