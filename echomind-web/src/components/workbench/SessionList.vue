<template>
  <section class="session-list">
    <div class="session-list-head">
      <div class="session-list-title">
        <span>会话</span>
        <span class="session-count">{{ sessions.length }}</span>
      </div>
      <div class="session-actions">
        <el-button text size="small" :loading="loading" title="刷新会话" @click="$emit('refresh')">
          <el-icon><Refresh /></el-icon>
        </el-button>
        <el-button text size="small" title="新建会话" @click="$emit('create')">
          <el-icon><Plus /></el-icon>
        </el-button>
      </div>
    </div>
    <div class="session-search">
      <el-input
        v-model="query"
        size="small"
        clearable
        placeholder="搜索历史"
        aria-label="搜索会话历史"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>
    </div>
    <div class="session-scroll">
      <div v-if="loading && sessions.length === 0" class="session-skeleton-list" aria-hidden="true">
        <div v-for="item in 5" :key="item" class="session-skeleton-item">
          <el-skeleton animated :rows="2" />
        </div>
      </div>
      <TransitionGroup v-else name="list-soft" tag="div" class="session-transition-list">
        <div
          v-for="item in sessionTimelineItems"
          :key="item.key"
          :class="item.type === 'group'
            ? 'session-group-label'
            : ['session-item', { active: activeSessionId === item.session.sessionId }]"
          :style="item.type === 'session' ? { '--item-index': item.index } : undefined"
        >
          <template v-if="item.type === 'group'">
            <span>{{ item.label }}</span>
            <span>{{ item.count }}</span>
          </template>
          <template v-else>
            <button
              class="session-main"
              type="button"
              :title="sessionTitle(item.session)"
              :aria-current="activeSessionId === item.session.sessionId ? 'true' : undefined"
              @click="$emit('open', item.session.sessionId)"
            >
              <span class="session-preview">{{ previewText(item.session) }}</span>
              <span class="session-meta">
                <span>{{ messageCountLabel(item.session.messageCount) }}</span>
                <span v-if="item.session.lastActivity">{{ formatRelativeTime(item.session.lastActivity) }}</span>
              </span>
            </button>
            <el-button
              text
              type="danger"
              size="small"
              class="session-delete"
              :loading="deletingId === item.session.sessionId"
              title="删除会话"
              @click.stop="$emit('delete', item.session)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </template>
        </div>
      </TransitionGroup>
      <div v-if="!loading && sessions.length === 0" class="session-empty session-empty-panel">
        <span class="session-empty-title">暂无会话</span>
        <span>新建后会在这里保留完整历史。</span>
      </div>
      <div v-else-if="!loading && filteredSessions.length === 0" class="session-empty session-empty-panel">
        <span class="session-empty-title">没有匹配结果</span>
        <span>换个关键词试试。</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Delete, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { markdownPreviewText } from '../../utils/chatMessages'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  activeSessionId: { type: String, default: null },
  loading: { type: Boolean, default: false },
  deletingId: { type: String, default: null }
})

defineEmits(['refresh', 'create', 'open', 'delete'])

const query = ref('')

const filteredSessions = computed(() => {
  const keyword = normalizeText(query.value).toLowerCase()
  if (!keyword) return props.sessions
  return props.sessions.filter(session => {
    const haystack = [
      session.sessionId,
      session.lastMessage,
      session.messageCount,
      formatFullTime(session.lastActivity)
    ].map(normalizeText).join(' ').toLowerCase()
    return haystack.includes(keyword)
  })
})

const groupedSessions = computed(() => {
  const buckets = [
    { key: 'today', label: '今天', sessions: [] },
    { key: 'yesterday', label: '昨天', sessions: [] },
    { key: 'week', label: '近 7 天', sessions: [] },
    { key: 'earlier', label: '更早', sessions: [] }
  ]
  filteredSessions.value.forEach(session => {
    buckets[groupIndex(session.lastActivity)].sessions.push(session)
  })
  return buckets.filter(group => group.sessions.length > 0)
})

const sessionTimelineItems = computed(() => {
  const items = []
  groupedSessions.value.forEach(group => {
    items.push({ type: 'group', key: `${group.key}-label`, label: group.label, count: group.sessions.length })
    group.sessions.forEach((session, index) => {
      items.push({ type: 'session', key: session.sessionId, session, index })
    })
  })
  return items
})

function groupIndex(instant) {
  const date = parseDate(instant)
  if (!date) return 3
  const startToday = startOfDay(new Date())
  const startTarget = startOfDay(date)
  const diffDays = Math.floor((startToday - startTarget) / 86400000)
  if (diffDays <= 0) return 0
  if (diffDays === 1) return 1
  if (diffDays < 7) return 2
  return 3
}

function startOfDay(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

function previewText(session) {
  return markdownPreviewText(session?.lastMessage) || '(空对话)'
}

function messageCountLabel(count) {
  const value = Number(count) || 0
  return `${value} 条`
}

function sessionTitle(session) {
  const time = formatFullTime(session?.lastActivity)
  return [previewText(session), time].filter(Boolean).join('\n')
}

function formatRelativeTime(instant) {
  const d = parseDate(instant)
  if (!d) return ''
  const now = new Date()
  const diffMs = now - d
  if (diffMs < 60000) return '刚刚'
  if (diffMs < 3600000) return Math.floor(diffMs / 60000) + ' 分钟前'
  if (diffMs < 86400000) return Math.floor(diffMs / 3600000) + ' 小时前'
  if (diffMs < 604800000) return Math.floor(diffMs / 86400000) + ' 天前'
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

function formatFullTime(instant) {
  const d = parseDate(instant)
  if (!d) return ''
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function parseDate(instant) {
  if (!instant) return null
  const d = new Date(instant)
  return Number.isNaN(d.getTime()) ? null : d
}

function normalizeText(value) {
  return String(value ?? '').replace(/\s+/g, ' ').trim()
}
</script>
