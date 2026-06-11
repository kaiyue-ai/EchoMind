<template>
  <div class="admin-page dashboard-page">
    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert" />

    <section class="admin-metric-grid dashboard-metrics">
      <div class="admin-metric-card">
        <span class="metric-icon green"><el-icon><DataLine /></el-icon></span>
        <div>
          <span>今日请求</span>
          <strong>{{ formatNumber(summary.todayRequests) }}</strong>
          <small>累计 {{ formatNumber(summary.totalRequests) }}</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon amber"><el-icon><Box /></el-icon></span>
        <div>
          <span>今日 Token</span>
          <strong>{{ formatCompact(summary.todayTokens?.totalTokens) }}</strong>
          <small>输入 {{ formatCompact(summary.todayTokens?.promptTokens) }} / 输出 {{ formatCompact(summary.todayTokens?.completionTokens) }}</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon blue"><el-icon><Coin /></el-icon></span>
        <div>
          <span>范围 Token</span>
          <strong>{{ formatCompact(summary.rangeTokens?.totalTokens) }}</strong>
          <small>所选时间范围内</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon purple"><el-icon><Timer /></el-icon></span>
        <div>
          <span>平均响应</span>
          <strong>{{ formatDurationMs(summary.rangeAverageDurationMs) }}</strong>
          <small>全量均值 {{ formatDurationMs(summary.averageDurationMs) }}</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon blue"><el-icon><User /></el-icon></span>
        <div>
          <span>客户端用户</span>
          <strong>{{ formatNumber(summary.totalUsers) }}</strong>
          <small>正常 {{ formatNumber(summary.activeUsers) }} / 封禁 {{ formatNumber(summary.disabledUsers) }}</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon amber"><el-icon><Document /></el-icon></span>
        <div>
          <span>累计 Token</span>
          <strong>{{ formatCompact(summary.totalTokens?.totalTokens) }}</strong>
          <small>累计 Provider 真用量</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon amber"><el-icon><Warning /></el-icon></span>
        <div>
          <span>错误率</span>
          <strong>{{ formatPercent(summary.rangeErrorRatePercent) }}</strong>
          <small>{{ rangeLabel }} 调用错误占比</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon green"><el-icon><Lock /></el-icon></span>
        <div>
          <span>脱敏事件</span>
          <strong>{{ formatNumber(summary.rangeSensitiveEvents) }}</strong>
          <small>{{ rangeLabel }} 命中记录</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon purple"><el-icon><Bell /></el-icon></span>
        <div>
          <span>告警事件</span>
          <strong>{{ formatNumber(summary.rangeAlertEvents) }}</strong>
          <small>{{ rangeLabel }} 推送或静默</small>
        </div>
      </div>
    </section>

    <section class="admin-filter-panel dashboard-filter">
      <div class="admin-filter-group">
        <label>
          <span>时间范围</span>
          <el-select v-model="range" class="usage-range-select">
            <el-option label="近 24 小时" value="1d" />
            <el-option label="近 7 天" value="7d" />
            <el-option label="近 30 天" value="30d" />
            <el-option label="近 90 天" value="90d" />
            <el-option label="全部时间" value="all" />
          </el-select>
        </label>
      </div>
      <div class="admin-filter-actions">
        <el-button :loading="loading" @click="refresh">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </section>

    <section class="dashboard-grid">
      <div class="dashboard-panel model-panel">
        <div class="dashboard-panel-head">
          <h2>模型分布</h2>
          <span>{{ rangeLabel }}</span>
        </div>
        <div class="model-distribution-body">
          <div ref="modelPieRef" class="dashboard-chart pie-chart"></div>
          <table class="dashboard-mini-table">
            <thead>
              <tr>
                <th>模型</th>
                <th>请求</th>
                <th>Token</th>
                <th>均响</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="model in modelDistribution" :key="model.modelId">
                <td>{{ model.modelId }}</td>
                <td>{{ formatNumber(model.callCount) }}</td>
                <td>{{ formatCompact(model.totalTokens) }}</td>
                <td>{{ formatDurationMs(model.averageDurationMs) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <el-empty v-if="!loading && modelDistribution.length === 0" description="暂无模型调用数据" />
      </div>

      <div class="dashboard-panel trend-panel">
        <div class="dashboard-panel-head">
          <h2>Token 使用趋势</h2>
          <span>{{ rangeLabel }}</span>
        </div>
        <div ref="trendChartRef" class="dashboard-chart trend-chart"></div>
        <el-empty v-if="!loading && tokenTrend.length === 0" description="暂无趋势数据" />
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Bell, Box, Coin, DataLine, Document, Lock, Refresh, Timer, User, Warning } from '@element-plus/icons-vue'
import api from '../api/admin'
import { useUiStore } from '../stores/ui'
import { runWhenIdle } from '../utils/scheduler'

const uiStore = useUiStore()
const loading = ref(false)
const error = ref('')
const range = ref('7d')
const summary = ref({
  totalTokens: { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0 },
  rangeTokens: { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0 },
  todayTokens: { promptTokens: 0, completionTokens: 0, totalTokens: 0, callCount: 0 },
  totalUsers: 0,
  activeUsers: 0,
  disabledUsers: 0,
  rangeRequests: 0,
  todayRequests: 0,
  totalRequests: 0,
  averageDurationMs: 0,
  rangeAverageDurationMs: 0,
  rangeErrorRatePercent: 0,
  rangeSensitiveEvents: 0,
  rangeAlertEvents: 0
})
const modelDistribution = ref([])
const tokenTrend = ref([])
const modelPieRef = ref(null)
const trendChartRef = ref(null)
let modelChart = null
let trendChart = null
let chartLibrary = null
let chartLibraryPromise = null
let cancelChartBootstrap = null

const rangeLabel = computed(() => {
  const labels = { '1d': '近 24 小时', '7d': '近 7 天', '30d': '近 30 天', '90d': '近 90 天', all: '全部时间' }
  return labels[range.value] || '近 7 天'
})

watch(range, refresh)
watch([modelDistribution, tokenTrend], renderCharts, { deep: true })
watch(() => uiStore.theme, renderCharts)

onMounted(async () => {
  await refresh()
  window.addEventListener('resize', resizeCharts)
  cancelChartBootstrap = runWhenIdle(() => renderCharts(), 900)
})

onBeforeUnmount(() => {
  cancelChartBootstrap?.()
  window.removeEventListener('resize', resizeCharts)
  modelChart?.dispose()
  trendChart?.dispose()
})

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.dashboard.overview({ range: range.value })
    summary.value = res.summary || summary.value
    modelDistribution.value = res.modelDistribution || []
    tokenTrend.value = res.tokenTrend || []
    await nextTick()
    runWhenIdle(() => renderCharts(), 600)
  } catch (e) {
    error.value = api.parseError(e, '加载仪表盘失败')
  } finally {
    loading.value = false
  }
}

function renderCharts() {
  nextTick(() => {
    renderModelChart()
    renderTrendChart()
  })
}

async function renderModelChart() {
  if (!modelPieRef.value) return
  const echarts = await loadChartLibrary()
  if (!modelPieRef.value) return
  modelChart = modelChart || echarts.init(modelPieRef.value)
  const theme = chartTheme()
  modelChart.setOption({
    color: ['#3b82f6', '#18c7b3', '#a56bff', '#ffae30', '#36f08a', '#ff647c'],
    tooltip: {
      trigger: 'item',
      formatter: params => `${params.name}<br/>${formatCompact(params.value)} Token (${params.percent}%)`
    },
    series: [{
      type: 'pie',
      radius: ['48%', '74%'],
      center: ['50%', '52%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: theme.panel, borderWidth: 2 },
      label: { color: theme.text, formatter: '{b}' },
      data: modelDistribution.value.map(model => ({
        name: model.modelId || 'unknown',
        value: Number(model.totalTokens || 0)
      }))
    }]
  })
}

async function renderTrendChart() {
  if (!trendChartRef.value) return
  const echarts = await loadChartLibrary()
  if (!trendChartRef.value) return
  trendChart = trendChart || echarts.init(trendChartRef.value)
  const theme = chartTheme()
  const dates = tokenTrend.value.map(point => point.date)
  trendChart.setOption({
    color: ['#3b82f6', '#18c7b3', '#a56bff'],
    tooltip: { trigger: 'axis' },
    legend: {
      top: 4,
      right: 8,
      textStyle: { color: theme.muted },
      data: ['Prompt', 'Completion', 'Total']
    },
    grid: { left: 52, right: 22, top: 48, bottom: 36 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: theme.axis } },
      axisLabel: { color: theme.muted }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: theme.muted, formatter: value => formatCompact(value) },
      splitLine: { lineStyle: { color: theme.grid } }
    },
    series: [
      smoothLine('Prompt', tokenTrend.value.map(point => point.promptTokens)),
      smoothLine('Completion', tokenTrend.value.map(point => point.completionTokens)),
      smoothLine('Total', tokenTrend.value.map(point => point.totalTokens), true)
    ]
  })
}

function chartTheme() {
  return uiStore.isLightTheme
    ? { text: '#172033', muted: '#6b7890', axis: '#d9e2ef', grid: '#e7edf6', panel: '#ffffff' }
    : { text: '#dce8f7', muted: '#c5d3e2', axis: '#314157', grid: '#26364c', panel: '#122133' }
}

function smoothLine(name, data, area = false) {
  return {
    name,
    type: 'line',
    smooth: true,
    symbolSize: 6,
    data,
    areaStyle: area ? { opacity: 0.16 } : undefined
  }
}

function resizeCharts() {
  modelChart?.resize()
  trendChart?.resize()
}

async function loadChartLibrary() {
  if (chartLibrary) return chartLibrary
  if (!chartLibraryPromise) {
    chartLibraryPromise = Promise.all([
      import('echarts/core'),
      import('echarts/components'),
      import('echarts/charts'),
      import('echarts/renderers')
    ]).then(([echartsCore, components, charts, renderers]) => {
      echartsCore.use([
        components.GridComponent,
        components.LegendComponent,
        components.TooltipComponent,
        charts.LineChart,
        charts.PieChart,
        renderers.CanvasRenderer
      ])
      chartLibrary = echartsCore
      return chartLibrary
    })
  }
  return chartLibraryPromise
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

function formatPercent(value) {
  return `${Number(value || 0).toFixed(2)}%`
}
</script>
