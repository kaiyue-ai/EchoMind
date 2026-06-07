<template>
  <div class="admin-page governance-page">
    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert" />

    <section class="admin-metric-grid usage-metrics">
      <div class="admin-metric-card">
        <span class="metric-icon blue"><el-icon><Bell /></el-icon></span>
        <div>
          <span>启用规则</span>
          <strong>{{ enabledRuleCount }}</strong>
          <small>共 {{ rules.length }} 条</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon amber"><el-icon><Warning /></el-icon></span>
        <div>
          <span>待关注</span>
          <strong>{{ criticalEventCount }}</strong>
          <small>严重或失败事件</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon green"><el-icon><Connection /></el-icon></span>
        <div>
          <span>已推送</span>
          <strong>{{ sentEventCount }}</strong>
          <small>飞书或静默记录</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon purple"><el-icon><Timer /></el-icon></span>
        <div>
          <span>静默事件</span>
          <strong>{{ silencedEventCount }}</strong>
          <small>按规则静默期抑制</small>
        </div>
      </div>
    </section>

    <section class="admin-filter-panel">
      <div class="admin-filter-group">
        <label>
          <span>告警类型</span>
          <el-select v-model="typeFilter" class="usage-range-select">
            <el-option label="全部类型" value="all" />
            <el-option v-for="type in alertTypes" :key="type" :label="typeLabel(type)" :value="type" />
          </el-select>
        </label>
        <label>
          <span>状态</span>
          <el-select v-model="statusFilter" class="usage-range-select">
            <el-option label="全部状态" value="all" />
            <el-option label="已发送" value="SENT" />
            <el-option label="已静默" value="SILENCED" />
            <el-option label="未配置" value="NOT_CONFIGURED" />
            <el-option label="失败" value="FAILED" />
          </el-select>
        </label>
      </div>
      <div class="admin-filter-actions">
        <el-button :loading="loading" @click="refresh">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" :loading="saving" @click="saveRules">
          <el-icon><Check /></el-icon>
          保存规则
        </el-button>
      </div>
    </section>

    <section class="admin-table-panel alert-rule-panel">
      <div class="dashboard-panel-head governance-head">
        <h2>告警规则</h2>
        <span :class="['webhook-env-hint', defaultWebhookConfigured ? 'is-ok' : 'is-missing']">
          {{ defaultWebhookConfigured ? '后端环境变量 Webhook 已生效' : '后端环境变量 Webhook 未生效，告警不会推送' }}
        </span>
      </div>
      <div v-loading="loading" class="alert-rule-list">
        <article v-for="rule in rules" :key="rule.ruleId" class="alert-rule-card">
          <div class="alert-rule-main">
            <label class="alert-field alert-switch-field">
              <span>启用</span>
              <el-switch
                v-model="rule.enabled"
                class="admin-switch"
                aria-label="启用告警规则"
              />
            </label>
            <label class="alert-field alert-rule-name-field">
              <span>{{ typeLabel(rule.alertType) }}</span>
              <el-input v-model="rule.ruleName" placeholder="规则名称" />
            </label>
            <label class="alert-field">
              <span>级别</span>
              <el-select v-model="rule.severity">
                <el-option label="提示" value="INFO" />
                <el-option label="警告" value="WARNING" />
                <el-option label="严重" value="CRITICAL" />
              </el-select>
            </label>
          </div>
          <div class="alert-rule-settings">
            <label v-if="usesRateThreshold(rule)" class="alert-field">
              <span>阈值</span>
              <el-input-number v-model="rule.thresholdPercent" :min="0" :max="100" controls-position="right" />
            </label>
            <label v-if="usesRateThreshold(rule)" class="alert-field">
              <span>窗口 / 分钟</span>
              <el-input-number v-model="rule.windowMinutes" :min="0" controls-position="right" />
            </label>
            <label class="alert-field">
              <span>静默 / 分钟</span>
              <el-input-number v-model="rule.quietMinutes" :min="0" controls-position="right" />
            </label>
            <label class="alert-field alert-switch-field">
              <span>升级</span>
              <el-switch
                v-model="rule.escalationEnabled"
                class="admin-switch"
                aria-label="启用升级告警"
              />
            </label>
            <label class="alert-field">
              <span>升级阈值</span>
              <el-input-number v-model="rule.escalationThreshold" :min="1" controls-position="right" />
            </label>
          </div>
        </article>
        <el-empty v-if="!loading && rules.length === 0" description="暂无告警规则" />
      </div>
    </section>

    <section class="admin-table-panel alert-events-panel">
      <div class="dashboard-panel-head governance-head">
        <h2>告警事件</h2>
        <span>包含 TraceID、建议动作和推送状态</span>
      </div>
      <div v-loading="loading" class="alert-event-list">
        <article
          v-for="event in filteredEvents"
          :key="event.eventId"
          :class="['alert-event-card', { 'is-escalated': event.escalated }]"
        >
          <div class="alert-event-head">
            <div class="alert-event-title">
              <div class="alert-event-badges">
                <StatusBadge :tone="severityTone(event.severity)">
                  {{ severityLabel(event.severity) }}
                </StatusBadge>
                <StatusBadge :tone="statusTone(event.status)">
                  {{ statusLabel(event.status) }}
                </StatusBadge>
                <StatusBadge :tone="event.escalated ? 'danger' : 'neutral'">
                  {{ event.escalated ? '已升级' : '普通' }}
                </StatusBadge>
              </div>
              <strong>{{ event.title }}</strong>
            </div>
            <time>{{ formatTime(event.createdAt) }}</time>
          </div>

          <div class="alert-event-meta">
            <span class="alert-meta-item">
              <b>类型</b>
              {{ typeLabel(event.alertType) }}
            </span>
            <span class="alert-meta-item">
              <b>用户</b>
              <strong>{{ event.username || event.userId || '-' }}</strong>
              <small>{{ shortId(event.userId) }}</small>
            </span>
            <span class="alert-meta-item">
              <b>TraceID</b>
              <button
                type="button"
                class="alert-trace-button"
                :disabled="!event.traceId"
                @click="openTrace(event.traceId)"
              >
                {{ shortId(event.traceId) }}
              </button>
            </span>
            <span class="alert-meta-item">
              <b>静默累计</b>
              {{ event.suppressedCount ? `${event.suppressedCount} 次` : '-' }}
            </span>
          </div>

          <div class="alert-event-body">
            <section class="alert-event-section alert-event-detail">
              <span>详情</span>
              <p>{{ event.message || '-' }}</p>
            </section>
            <section class="alert-event-section">
              <span>建议</span>
              <p>{{ event.suggestion || event.failureReason || '-' }}</p>
            </section>
            <section class="alert-event-section">
              <span>飞书响应</span>
              <p class="alert-provider-text">{{ event.providerResponse || event.failureReason || '-' }}</p>
            </section>
          </div>
        </article>
        <el-empty v-if="!loading && filteredEvents.length === 0" description="暂无告警事件" />
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, Check, Connection, Refresh, Timer, Warning } from '@element-plus/icons-vue'
import api from '../api/admin'
import StatusBadge from '../components/workbench/StatusBadge.vue'

const router = useRouter()
const rules = ref([])
const events = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const typeFilter = ref('all')
const statusFilter = ref('all')
const defaultWebhookConfigured = ref(false)

const alertTypes = [
  'CALL_ERROR',
  'ERROR_RATE',
  'PROVIDER_TOKEN_BUDGET_EXCEEDED',
  'PROVIDER_TOKEN_BUDGET_WARNING',
  'SENSITIVE_DATA'
]
const enabledRuleCount = computed(() => rules.value.filter(rule => rule.enabled).length)
const criticalEventCount = computed(() => events.value.filter(event => event.severity === 'CRITICAL' || event.status === 'FAILED').length)
const sentEventCount = computed(() => events.value.filter(event => event.status === 'SENT').length)
const silencedEventCount = computed(() => events.value.filter(event => event.status === 'SILENCED').length)
const filteredEvents = computed(() => events.value
  .filter(event => typeFilter.value === 'all' || event.alertType === typeFilter.value)
  .filter(event => statusFilter.value === 'all' || event.status === statusFilter.value))

onMounted(refresh)

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [ruleRes, eventRes] = await Promise.all([
      api.alerts.rules(),
      api.alerts.events({ limit: 200 })
    ])
    rules.value = (ruleRes.rules || []).map(normalizeRule)
    defaultWebhookConfigured.value = Boolean(ruleRes.defaultWebhookConfigured)
    events.value = eventRes.events || []
  } catch (e) {
    error.value = api.parseError(e, '加载告警中心失败')
  } finally {
    loading.value = false
  }
}

async function saveRules() {
  saving.value = true
  error.value = ''
  try {
    const res = await api.alerts.updateRules({ rules: rules.value.map(sanitizeRuleForSave) })
    rules.value = (res.rules || []).map(normalizeRule)
    defaultWebhookConfigured.value = Boolean(res.defaultWebhookConfigured)
    ElMessage.success('告警规则已保存')
  } catch (e) {
    error.value = api.parseError(e, '保存告警规则失败')
  } finally {
    saving.value = false
  }
}

function openTrace(traceId) {
  if (!traceId) return
  router.push({ path: '/traces', query: { traceId } })
}

function normalizeRule(rule) {
  const normalized = {
    ...rule,
    escalationEnabled: rule.escalationEnabled ?? true,
    escalationThreshold: rule.escalationThreshold || 3
  }
  return sanitizeDerivedFields(normalized)
}

function sanitizeRuleForSave(rule) {
  return sanitizeDerivedFields({ ...rule })
}

function sanitizeDerivedFields(rule) {
  if (!usesRateThreshold(rule)) {
    rule.thresholdPercent = null
    rule.windowMinutes = null
  }
  return rule
}

function usesRateThreshold(rule) {
  return rule?.alertType === 'ERROR_RATE'
}

function typeLabel(type) {
  return {
    CALL_ERROR: '调用错误',
    ERROR_RATE: '错误率',
    PROVIDER_TOKEN_BUDGET_EXCEEDED: 'Provider 预算超限',
    PROVIDER_TOKEN_BUDGET_WARNING: 'Provider 预算预警',
    SENSITIVE_DATA: '敏感数据'
  }[type] || type
}

function severityLabel(severity) {
  return { INFO: '提示', WARNING: '警告', CRITICAL: '严重' }[severity] || severity
}

function severityTone(severity) {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARNING') return 'warning'
  return 'neutral'
}

function statusLabel(status) {
  return { SENT: '已发送', SILENCED: '已静默', FAILED: '失败', NOT_CONFIGURED: '未配置' }[status] || status
}

function statusTone(status) {
  if (status === 'FAILED') return 'danger'
  if (status === 'SENT') return 'success'
  if (status === 'SILENCED') return 'warning'
  return 'neutral'
}

function formatTime(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

function shortId(value) {
  if (!value) return '-'
  const text = String(value)
  return text.length > 12 ? `${text.slice(0, 6)}…${text.slice(-4)}` : text
}
</script>
