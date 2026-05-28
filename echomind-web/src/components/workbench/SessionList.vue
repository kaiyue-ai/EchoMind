<template>
  <section class="session-list">
    <div class="session-list-head">
      <span>会话</span>
      <div class="session-actions">
        <el-button text size="small" :loading="loading" title="刷新会话" @click="$emit('refresh')">
          <el-icon><Refresh /></el-icon>
        </el-button>
        <el-button text size="small" title="新建会话" @click="$emit('create')">
          <el-icon><Plus /></el-icon>
        </el-button>
      </div>
    </div>
    <div class="session-scroll">
      <div v-if="loading && sessions.length === 0" class="session-skeleton-list" aria-hidden="true">
        <div v-for="item in 5" :key="item" class="session-skeleton-item">
          <el-skeleton animated :rows="2" />
        </div>
      </div>
      <template v-else>
        <div
          v-for="session in sessions"
          :key="session.sessionId"
          :class="['session-item', { active: activeSessionId === session.sessionId }]"
        >
          <button
            class="session-main"
            type="button"
            @click="$emit('open', session.sessionId)"
          >
            <span class="session-preview">{{ session.lastMessage || '(空对话)' }}</span>
            <span class="session-meta">
              <span>{{ session.messageCount || 0 }} 条</span>
              <span v-if="session.lastActivity">{{ formatTime(session.lastActivity) }}</span>
            </span>
          </button>
          <el-button
            text
            type="danger"
            size="small"
            class="session-delete"
            :loading="deletingId === session.sessionId"
            title="删除会话"
            @click.stop="$emit('delete', session)"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
      </template>
      <div v-if="!loading && sessions.length === 0" class="session-empty">
        暂无会话
      </div>
    </div>
  </section>
</template>

<script setup>
import { Delete, Plus, Refresh } from '@element-plus/icons-vue'

defineProps({
  sessions: { type: Array, default: () => [] },
  activeSessionId: { type: String, default: null },
  loading: { type: Boolean, default: false },
  deletingId: { type: String, default: null }
})

defineEmits(['refresh', 'create', 'open', 'delete'])

function formatTime(instant) {
  const d = new Date(instant)
  const now = new Date()
  const diffMs = now - d
  if (Number.isNaN(d.getTime())) return ''
  if (diffMs < 60000) return '刚刚'
  if (diffMs < 3600000) return Math.floor(diffMs / 60000) + ' 分钟前'
  if (diffMs < 86400000) return Math.floor(diffMs / 3600000) + ' 小时前'
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}
</script>
