<template>
  <div class="workspace-page trace-page">
    <header class="workspace-header">
      <div>
        <span class="eyebrow">Observability</span>
        <h1>Trace 追踪</h1>
      </div>
      <div class="workspace-header-actions">
        <el-input
          v-model="userIdFilter"
          class="trace-user-filter"
          clearable
          placeholder="客户端用户ID"
          @keyup.enter="loadRecent"
          @clear="loadRecent"
        />
        <el-select v-model="traceScope" class="trace-scope" size="default" @change="loadRecent">
          <el-option label="业务链路" value="business" />
          <el-option label="全部 Trace" value="all" />
        </el-select>
        <el-select v-model="lookback" class="trace-lookback" size="default" @change="loadRecent">
          <el-option label="最近 1 小时" value="1h" />
          <el-option label="最近 6 小时" value="6h" />
          <el-option label="最近 24 小时" value="24h" />
        </el-select>
        <el-button :loading="loadingConfig || loadingRecent" @click="refreshAll">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert" />
    <el-alert v-if="config && !backendEnabled" :title="config.message" type="warning" show-icon class="page-alert" />

    <section class="metric-strip trace-metrics">
      <div class="metric-card">
        <span>Trace 导出</span>
        <strong>{{ exporterLabel }}</strong>
      </div>
      <div class="metric-card">
        <span>查询后端</span>
        <strong>{{ backendLabel }}</strong>
      </div>
      <div class="metric-card">
        <span>最近链路</span>
        <strong>{{ recentTraces.length }}</strong>
      </div>
      <div class="metric-card">
        <span>错误链路</span>
        <strong>{{ errorCount }}</strong>
      </div>
    </section>

    <section class="trace-search panel-block">
      <el-input
        v-model="traceIdInput"
        clearable
        placeholder="输入 16 或 32 位 TraceID"
        @keyup.enter="searchTrace"
      >
        <template #prepend>TraceID</template>
      </el-input>
      <el-button type="primary" :loading="loadingDetail" :disabled="!backendEnabled" @click="searchTrace">
        <el-icon><Search /></el-icon>
        查询
      </el-button>
      <el-button v-if="selectedTrace?.externalUrl" tag="a" :href="selectedTrace.externalUrl" target="_blank">
        <el-icon><Link /></el-icon>
        查询后端
      </el-button>
    </section>

    <div class="trace-layout">
      <section class="panel-block trace-list-panel">
        <div class="section-head">
          <div>
            <span class="eyebrow">Recent</span>
            <h2>最近链路</h2>
          </div>
        </div>
        <div v-loading="loadingRecent" class="trace-list">
          <button
            v-for="trace in recentTraces"
            :key="trace.traceId"
            type="button"
            :class="['trace-list-item', { active: selectedTrace?.traceId === trace.traceId }]"
            @click="selectRecent(trace)"
          >
            <div class="trace-list-title">
              <strong>{{ trace.operationName || '(unknown)' }}</strong>
              <StatusBadge :tone="trace.hasError ? 'danger' : 'success'">
                {{ trace.hasError ? 'ERROR' : 'OK' }}
              </StatusBadge>
            </div>
            <span class="trace-id">{{ trace.traceId }}</span>
            <div class="trace-list-meta">
              <span>{{ formatDuration(trace.durationMicros) }}</span>
              <span>{{ trace.spanCount }} spans</span>
              <span v-if="hasTokenUsage(trace.fields)">{{ formatTokenTotal(trace.fields) }}</span>
              <span>{{ formatTime(trace.startTimeMicros) }}</span>
            </div>
          </button>
          <el-empty v-if="!loadingRecent && backendEnabled && recentTraces.length === 0" :description="emptyTraceText" />
          <el-empty v-if="!backendEnabled" description="未接入查询后端" />
        </div>
      </section>

      <section class="panel-block trace-detail-panel">
        <div class="section-head">
          <div>
            <span class="eyebrow">Detail</span>
            <h2>{{ selectedTrace?.operationName || '链路详情' }}</h2>
          </div>
          <StatusBadge v-if="selectedTrace" :tone="selectedTrace.hasError ? 'danger' : 'success'">
            {{ selectedTrace.hasError ? 'ERROR' : 'OK' }}
          </StatusBadge>
        </div>

        <div v-if="selectedTrace" class="trace-detail">
          <div class="trace-summary-grid">
            <div>
              <span>TraceID</span>
              <code>{{ selectedTrace.traceId }}</code>
            </div>
            <div>
              <span>服务</span>
              <strong>{{ selectedTrace.serviceName || '-' }}</strong>
            </div>
            <div>
              <span>总耗时</span>
              <strong>{{ formatDuration(selectedTrace.durationMicros) }}</strong>
            </div>
            <div>
              <span>开始时间</span>
              <strong>{{ formatTime(selectedTrace.startTimeMicros) }}</strong>
            </div>
            <div>
              <span>用户</span>
              <strong>{{ traceUserLabel(selectedTrace.fields) }}</strong>
            </div>
            <div>
              <span>Agent</span>
              <strong>{{ fieldValue(selectedTrace.fields?.agentId) }}</strong>
            </div>
            <div>
              <span>会话</span>
              <strong>{{ fieldValue(selectedTrace.fields?.sessionId) }}</strong>
            </div>
            <div>
              <span>模型</span>
              <strong>{{ fieldValue(selectedTrace.fields?.modelId) }}</strong>
            </div>
            <div class="token-summary-card">
              <span>Token</span>
              <strong>{{ formatTokenUsage(selectedTrace.fields) }}</strong>
            </div>
          </div>

          <div class="span-timeline">
            <div
              v-for="span in selectedTrace.spans"
              :key="span.spanId"
              class="span-row"
              :style="{ '--span-left': spanLeft(span) + '%', '--span-width': spanWidth(span) + '%' }"
            >
              <div class="span-row-head">
                <div>
                  <strong>{{ span.operationName }}</strong>
                  <span>{{ span.serviceName }}</span>
                </div>
                <StatusBadge :tone="span.hasError ? 'danger' : 'neutral'">
                  {{ formatDuration(span.durationMicros) }}
                </StatusBadge>
              </div>
              <div v-if="hasTraceFields(span.fields)" class="span-field-chips">
                <span v-if="span.fields?.modelId">{{ span.fields.modelId }}</span>
                <span v-if="span.fields?.userId">user {{ span.fields.userId }}</span>
                <span v-if="span.fields?.agentId">agent {{ span.fields.agentId }}</span>
                <span v-if="hasTokenUsage(span.fields)">{{ formatTokenTotal(span.fields) }}</span>
              </div>
              <div class="span-bar-track">
                <div :class="['span-bar', { error: span.hasError }]"></div>
              </div>
              <details v-if="Object.keys(span.tags || {}).length || (span.logs || []).length" class="span-meta">
                <summary>属性与日志</summary>
                <pre>{{ formatSpanMeta(span) }}</pre>
              </details>
            </div>
          </div>
        </div>

        <el-empty v-else description="选择一条 Trace 查看 Span 时间线" />
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Link, Refresh, Search } from '@element-plus/icons-vue'
import api from '../api/admin'
import StatusBadge from '../components/workbench/StatusBadge.vue'

const route = useRoute()
const router = useRouter()
const config = ref(null)
const recentTraces = ref([])
const selectedTrace = ref(null)
const traceIdInput = ref('')
const userIdFilter = ref('')
const traceScope = ref('business')
const lookback = ref('1h')
const error = ref('')
const loadingConfig = ref(false)
const loadingRecent = ref(false)
const loadingDetail = ref(false)
let latestRequestedTraceId = null
let suppressRouteWatch = false

const backendEnabled = computed(() => Boolean(config.value?.backend?.enabled))
const exporterLabel = computed(() => {
  const exporter = config.value?.exporter
  if (!exporter) return '-'
  if (!exporter.enabled) return '未开启'
  return `${exporter.type}/${exporter.protocol || 'otlp'}`
})
const backendLabel = computed(() => {
  const backend = config.value?.backend
  if (!backend) return '-'
  return backend.enabled ? `${backend.type}/${backend.serviceName}` : '未接入'
})
const errorCount = computed(() => recentTraces.value.filter(trace => trace.hasError).length)
const emptyTraceText = computed(() => (
  traceScope.value === 'business' ? '暂无业务链路，先在客户端发起一次对话' : '暂无 Trace'
))

onMounted(async () => {
  const initialTraceId = String(route.query.traceId || '')
  const initialUserId = String(route.query.userId || '')
  if (initialUserId) {
    userIdFilter.value = initialUserId
  }
  if (initialTraceId) {
    traceIdInput.value = initialTraceId
  }
  await refreshAll()
  if (initialTraceId) {
    await loadTrace(initialTraceId)
  }
})

watch(() => route.query.traceId, (traceId) => {
  if (suppressRouteWatch) {
    suppressRouteWatch = false
    return
  }
  const nextTraceId = String(traceId || '')
  if (!nextTraceId || nextTraceId === selectedTrace.value?.traceId) return
  traceIdInput.value = nextTraceId
  loadTrace(nextTraceId)
})

async function refreshAll() {
  await loadConfig()
  if (backendEnabled.value) {
    await loadRecent()
  }
}

async function loadConfig() {
  loadingConfig.value = true
  error.value = ''
  try {
    config.value = await api.observability.traceConfig()
  } catch (e) {
    error.value = api.parseError(e, '加载 Trace 配置失败')
  } finally {
    loadingConfig.value = false
  }
}

async function loadRecent() {
  if (!backendEnabled.value) return
  loadingRecent.value = true
  error.value = ''
  try {
    const res = await api.observability.traces({
      limit: 20,
      lookback: lookback.value,
      scope: traceScope.value,
      userId: userIdFilter.value.trim() || undefined
    })
    recentTraces.value = res.traces || []
    if (!selectedTrace.value && recentTraces.value.length && !route.query.traceId) {
      await selectRecent(recentTraces.value[0])
    }
  } catch (e) {
    error.value = api.parseError(e, '加载最近 Trace 失败')
  } finally {
    loadingRecent.value = false
  }
}

async function searchTrace() {
  const traceId = traceIdInput.value.trim()
  if (!traceId) return
  suppressRouteWatch = true
  router.replace({ query: { ...route.query, traceId } })
  await loadTrace(traceId)
}

async function selectRecent(trace) {
  traceIdInput.value = trace.traceId
  suppressRouteWatch = true
  router.replace({ query: { ...route.query, traceId: trace.traceId } })
  await loadTrace(trace.traceId)
}

async function loadTrace(traceId) {
  if (!backendEnabled.value) {
    ElMessage.warning('查询后端未接入，暂时不能查询 Trace')
    return
  }
  latestRequestedTraceId = traceId
  loadingDetail.value = true
  error.value = ''
  try {
    const res = await api.observability.trace(traceId)
    if (latestRequestedTraceId !== traceId) return
    selectedTrace.value = res.trace
  } catch (e) {
    if (latestRequestedTraceId !== traceId) return
    error.value = api.parseError(e, '查询 Trace 失败')
  } finally {
    if (latestRequestedTraceId === traceId) {
      loadingDetail.value = false
    }
  }
}

function spanLeft(span) {
  const trace = selectedTrace.value
  if (!trace || !trace.durationMicros) return 0
  return clamp(((span.startTimeMicros - trace.startTimeMicros) / trace.durationMicros) * 100)
}

function spanWidth(span) {
  const trace = selectedTrace.value
  if (!trace || !trace.durationMicros) return 1
  return Math.max(1, clamp((span.durationMicros / trace.durationMicros) * 100))
}

function clamp(value) {
  return Math.max(0, Math.min(100, value))
}

function formatDuration(micros) {
  const value = Number(micros || 0)
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}s`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}ms`
  return `${value}us`
}

function formatTime(micros) {
  const value = Number(micros || 0)
  if (!value) return '-'
  return new Date(Math.floor(value / 1000)).toLocaleString()
}

function traceUserLabel(fields) {
  const userId = fieldValue(fields?.userId)
  const username = fieldValue(fields?.username)
  if (userId === '-' && username === '-') return '-'
  if (username === '-' || username === userId) return userId
  return `${username} / ${userId}`
}

function fieldValue(value) {
  return value === null || value === undefined || value === '' ? '-' : String(value)
}

function hasTraceFields(fields) {
  if (!fields) return false
  return Boolean(
    fields.userId ||
      fields.username ||
      fields.accountType ||
      fields.agentId ||
      fields.sessionId ||
      fields.modelId ||
      hasTokenUsage(fields)
  )
}

function hasTokenUsage(fields) {
  return fields?.totalTokens !== null && fields?.totalTokens !== undefined
}

function formatTokenTotal(fields) {
  if (!hasTokenUsage(fields)) return '-'
  return `${formatNumber(fields.totalTokens)} tokens`
}

function formatTokenUsage(fields) {
  if (!hasTokenUsage(fields)) return '-'
  return `${formatNumber(fields.promptTokens || 0)} / ${formatNumber(fields.completionTokens || 0)} / ${formatNumber(fields.totalTokens || 0)}`
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString()
}

function formatSpanMeta(span) {
  return JSON.stringify({ tags: span.tags || {}, logs: span.logs || [] }, null, 2)
}
</script>
