<template>
  <div class="admin-page governance-page">
    <el-alert v-if="error" :title="error" type="error" show-icon class="page-alert" />

    <section class="admin-metric-grid usage-metrics">
      <div class="admin-metric-card">
        <span class="metric-icon blue"><el-icon><Lock /></el-icon></span>
        <div>
          <span>启用规则</span>
          <strong>{{ enabledRuleCount }}</strong>
          <small>共 {{ rules.length }} 条</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon amber"><el-icon><Warning /></el-icon></span>
        <div>
          <span>阻断规则</span>
          <strong>{{ blockRuleCount }}</strong>
          <small>命中后拦截请求或响应</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon green"><el-icon><Document /></el-icon></span>
        <div>
          <span>事件数</span>
          <strong>{{ events.length }}</strong>
          <small>最近记录</small>
        </div>
      </div>
      <div class="admin-metric-card">
        <span class="metric-icon purple"><el-icon><Connection /></el-icon></span>
        <div>
          <span>关联 Trace</span>
          <strong>{{ traceCount }}</strong>
          <small>可跳转排查链路</small>
        </div>
      </div>
    </section>

    <section class="admin-filter-panel">
      <div class="admin-filter-group">
        <label>
          <span>事件方向</span>
          <el-select v-model="directionFilter" class="usage-range-select">
            <el-option label="全部方向" value="all" />
            <el-option label="请求" value="REQUEST" />
            <el-option label="响应" value="RESPONSE" />
          </el-select>
        </label>
        <label>
          <span>处理动作</span>
          <el-select v-model="actionFilter" class="usage-range-select">
            <el-option label="全部动作" value="all" />
            <el-option label="脱敏" value="MASK" />
            <el-option label="阻断" value="BLOCK" />
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

    <section class="admin-table-panel">
      <div class="dashboard-panel-head governance-head">
        <h2>敏感数据规则</h2>
        <el-button @click="addRule">
          <el-icon><Plus /></el-icon>
          新增规则
        </el-button>
      </div>
      <div v-loading="loading" class="admin-table-wrap">
        <table class="admin-record-table governance-table">
          <thead>
            <tr>
              <th>启用</th>
              <th>规则名称</th>
              <th>正则表达式</th>
              <th>替代文本</th>
              <th>动作</th>
              <th>类型</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rule in rules" :key="rule.ruleId || rule.localId">
              <td>
                <el-switch
                  v-model="rule.enabled"
                  class="admin-switch"
                  inline-prompt
                  active-text="开"
                  inactive-text="关"
                  aria-label="启用敏感数据规则"
                />
              </td>
              <td><el-input v-model="rule.ruleName" /></td>
              <td><el-input v-model="rule.pattern" /></td>
              <td><el-input v-model="rule.replacement" /></td>
              <td>
                <el-select v-model="rule.action">
                  <el-option label="脱敏" value="MASK" />
                  <el-option label="阻断" value="BLOCK" />
                </el-select>
              </td>
              <td>
                <span class="admin-chip">{{ rule.builtIn ? '内置' : '自定义' }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <el-empty v-if="!loading && rules.length === 0" description="暂无脱敏规则" />
      </div>
    </section>

    <section class="admin-table-panel">
      <div class="dashboard-panel-head governance-head">
        <h2>脱敏事件</h2>
        <span>仅展示脱敏后的样本</span>
      </div>
      <div v-loading="loading" class="admin-table-wrap">
        <table class="admin-record-table governance-event-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>用户</th>
              <th>规则</th>
              <th>方向</th>
              <th>动作</th>
              <th>命中</th>
              <th>TraceID</th>
              <th>样本</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="event in filteredEvents" :key="event.eventId">
              <td>{{ formatTime(event.createdAt) }}</td>
              <td>
                <strong>{{ event.username || event.userId }}</strong>
                <span>{{ shortId(event.userId) }}</span>
              </td>
              <td>{{ event.ruleName }}</td>
              <td><span class="admin-chip blue-chip">{{ directionLabel(event.direction) }}</span></td>
              <td>
                <StatusBadge :tone="event.action === 'BLOCK' ? 'danger' : 'success'">
                  {{ actionLabel(event.action) }}
                </StatusBadge>
              </td>
              <td>{{ event.matchCount }}</td>
              <td class="trace-cell">
                <button type="button" @click="openTrace(event.traceId)">{{ shortId(event.traceId) }}</button>
              </td>
              <td><code>{{ event.sample }}</code></td>
            </tr>
          </tbody>
        </table>
        <el-empty v-if="!loading && filteredEvents.length === 0" description="暂无脱敏事件" />
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check, Connection, Document, Lock, Plus, Refresh, Warning } from '@element-plus/icons-vue'
import api from '../api/admin'
import StatusBadge from '../components/workbench/StatusBadge.vue'

const router = useRouter()
const rules = ref([])
const events = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const directionFilter = ref('all')
const actionFilter = ref('all')

const enabledRuleCount = computed(() => rules.value.filter(rule => rule.enabled).length)
const blockRuleCount = computed(() => rules.value.filter(rule => rule.enabled && rule.action === 'BLOCK').length)
const traceCount = computed(() => new Set(events.value.map(event => event.traceId).filter(Boolean)).size)
const filteredEvents = computed(() => events.value
  .filter(event => directionFilter.value === 'all' || event.direction === directionFilter.value)
  .filter(event => actionFilter.value === 'all' || event.action === actionFilter.value))

onMounted(refresh)

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [ruleRes, eventRes] = await Promise.all([
      api.sensitive.rules(),
      api.sensitive.events({ limit: 200 })
    ])
    rules.value = (ruleRes.rules || []).map(rule => ({ ...rule }))
    events.value = eventRes.events || []
  } catch (e) {
    error.value = api.parseError(e, '加载脱敏治理数据失败')
  } finally {
    loading.value = false
  }
}

function addRule() {
  rules.value.unshift({
    localId: `local-${Date.now()}`,
    ruleId: '',
    ruleName: '自定义关键词',
    pattern: '关键词',
    replacement: '[CUSTOM]',
    action: 'MASK',
    enabled: true,
    builtIn: false
  })
}

async function saveRules() {
  saving.value = true
  error.value = ''
  try {
    const res = await api.sensitive.updateRules({ rules: rules.value })
    rules.value = (res.rules || []).map(rule => ({ ...rule }))
    ElMessage.success('脱敏规则已保存')
  } catch (e) {
    error.value = api.parseError(e, '保存脱敏规则失败')
  } finally {
    saving.value = false
  }
}

function openTrace(traceId) {
  if (!traceId) return
  router.push({ path: '/traces', query: { traceId } })
}

function directionLabel(direction) {
  return direction === 'RESPONSE' ? '响应' : '请求'
}

function actionLabel(action) {
  return action === 'BLOCK' ? '阻断' : '脱敏'
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
