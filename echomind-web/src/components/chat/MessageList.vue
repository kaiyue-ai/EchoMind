<template>
  <div class="message-list-shell">
    <div
      ref="containerRef"
      class="message-list"
      role="list"
      aria-label="聊天消息"
      @scroll.passive="handleScroll"
    >
      <span class="sr-only" aria-live="polite">{{ liveStatus }}</span>
      <div v-if="loadingHistory" class="message-history-skeleton" aria-hidden="true">
        <article
          v-for="item in 4"
          :key="item"
          :class="['message-row', item % 3 === 0 ? 'message-user' : 'message-assistant']"
        >
          <div class="message-avatar skeleton-avatar"></div>
          <div class="message-content">
            <el-skeleton animated :rows="item === 1 ? 2 : 4" />
          </div>
        </article>
      </div>

      <div v-else-if="displayMessages.length === 0" class="empty-chat">
        <div class="empty-chat-mark">EM</div>
        <h1>从一个任务开始</h1>
        <p>选择 Agent 和模型，直接把问题交给 EchoMind。</p>
      </div>

      <TransitionGroup v-else name="message-soft" tag="div" class="message-flow">
        <component
          :is="item.type === 'divider' ? 'div' : 'article'"
          v-for="item in timelineItems"
          :key="item.key"
          :class="item.type === 'divider'
            ? 'message-date-divider'
            : [
              'message-row',
              `message-${item.message.role}`,
              {
                'message-pending': item.message.pending,
                'message-streaming': item.message.streaming,
                'message-variant-error': item.message.variant === 'error'
              }
            ]"
          :role="item.type === 'message' ? 'listitem' : undefined"
        >
          <template v-if="item.type === 'divider'">
            <span>{{ item.label }}</span>
          </template>
          <template v-else>
            <div class="message-avatar">
              <img
                v-if="item.message.role === 'user' && authStore.user?.avatarUrl"
                :src="authStore.user.avatarUrl"
                alt="用户头像"
                width="36"
                height="36"
                decoding="async"
              />
              <span v-else>{{ roleLabel(item.message.role) }}</span>
            </div>
            <div class="message-content">
              <div class="message-bubble-head">
                <span class="message-role-label">{{ readableRole(item.message.role) }}</span>
                <span v-if="formatMessageTime(item.message)" class="message-time">{{ formatMessageTime(item.message) }}</span>
                <el-button
                  v-if="canCopyMessage(item.message)"
                  text
                  size="small"
                  class="message-copy-button"
                  :title="copiedKey === item.key ? '已复制' : '复制消息'"
                  :aria-label="copiedKey === item.key ? '已复制' : '复制消息'"
                  @click="copyMessage(item.message, item.key)"
                >
                  <el-icon><component :is="copiedKey === item.key ? Check : CopyDocument" /></el-icon>
                </el-button>
              </div>
              <div v-if="item.message.role === 'assistant' && item.message.pending" class="thinking-pill" aria-live="polite">
                <span class="thinking-orbit" aria-hidden="true">
                  <span class="thinking-dot"></span>
                  <span class="thinking-dot"></span>
                  <span class="thinking-dot"></span>
                </span>
                <span>{{ item.message.toolStatus || thinkingLabel }}</span>
              </div>
              <MarkdownRenderer
                v-else-if="item.message.role === 'assistant'"
                :content="item.message.content"
                :streaming="item.message.streaming"
              />
              <div v-else class="plain-message">{{ item.message.content }}</div>
              <div v-if="item.message.attachments?.length" class="message-attachments">
                <img
                  v-for="att in item.message.attachments"
                  :key="att.uri || att.url"
                  v-show="att.type === 'image'"
                  :src="att.url"
                  :alt="att.fileName || '聊天图片'"
                  class="message-image"
                  loading="lazy"
                  decoding="async"
                  @load="followIfNearBottom('auto')"
                />
              </div>
              <div v-if="item.message.skillResults?.length" class="skill-result-line">
                <span
                  v-for="skill in item.message.skillResults"
                  :key="skill"
                  class="skill-result-chip"
                >
                  {{ skill }}
                </span>
              </div>
              <div v-if="item.message.toolStatus && !item.message.pending" class="tool-status-line">
                {{ item.message.toolStatus }}
              </div>
            </div>
          </template>
        </component>
      </TransitionGroup>
    </div>

    <button
      v-if="showJumpButton"
      type="button"
      :class="['message-jump-button', { 'has-new-activity': hasNewActivity }]"
      @click="jumpToBottom"
    >
      {{ hasNewActivity ? '查看新回复' : '回到底部' }}
    </button>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, CopyDocument } from '@element-plus/icons-vue'
import { useAuthStore } from '../../stores/auth'
import { copyText } from '../../utils/clipboard'
import { normalizeChatMessages } from '../../utils/chatMessages'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  loadingHistory: { type: Boolean, default: false }
})

const containerRef = ref(null)
const authStore = useAuthStore()
const displayMessages = computed(() => normalizeChatMessages(props.messages))
const isNearBottom = ref(true)
const showJumpButton = ref(false)
const hasNewActivity = ref(false)
const thinkingStep = ref(0)
const copiedKey = ref('')
let scrollFrame = 0
let queuedScrollBehavior = 'auto'
let thinkingTimer = 0
let copiedTimer = 0

const thinkingLabels = ['理解问题', '组织上下文', '生成回复']
const thinkingLabel = computed(() => thinkingLabels[thinkingStep.value % thinkingLabels.length])
const timelineItems = computed(() => {
  const items = []
  let lastDateKey = ''
  displayMessages.value.forEach((message, index) => {
    const date = parseMessageDate(message)
    const dateKey = date ? date.toDateString() : ''
    if (dateKey && dateKey !== lastDateKey) {
      items.push({
        type: 'divider',
        key: `divider-${dateKey}`,
        label: formatDateDivider(date)
      })
      lastDateKey = dateKey
    }
    items.push({
      type: 'message',
      key: messageKey(message, index),
      message,
      index
    })
  })
  return items
})
const liveStatus = computed(() => {
  const last = displayMessages.value[displayMessages.value.length - 1]
  if (!last) return props.loadingHistory ? '正在加载历史消息' : ''
  if (last.pending || last.streaming) return '正在生成回复'
  if (last.variant === 'error') return '回复失败'
  return ''
})
const MarkdownRenderer = defineAsyncComponent({
  loader: () => import('./MarkdownRenderer.vue'),
  delay: 0,
  suspensible: false,
  loadingComponent: {
    template: '<div class="markdown-body markdown-renderer-loading" aria-hidden="true"><span></span><span></span><span></span></div>'
  }
})

watch(() => displayMessages.value.length, (count, previousCount = 0) => {
  if (!props.loadingHistory) {
    if (count < previousCount) {
      isNearBottom.value = true
      showJumpButton.value = false
      return
    }
    if (count > previousCount && isNearBottom.value) {
      nextTick(() => scrollToBottom('auto'))
    } else if (count > previousCount) {
      showJumpButton.value = true
      hasNewActivity.value = true
    }
  }
})

watch(() => props.loadingHistory, (loading, previousLoading) => {
  if (previousLoading && !loading) {
    nextTick(() => scrollToBottom('auto'))
  }
})

function scrollToBottom(behavior = 'auto') {
  queuedScrollBehavior = behavior === 'smooth' ? 'smooth' : queuedScrollBehavior
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    if (!containerRef.value) return
    const previousScrollBehavior = containerRef.value.style.scrollBehavior
    containerRef.value.style.scrollBehavior = queuedScrollBehavior === 'smooth' ? 'smooth' : 'auto'
    containerRef.value.scrollTop = containerRef.value.scrollHeight
    isNearBottom.value = true
    showJumpButton.value = false
    hasNewActivity.value = false
    queuedScrollBehavior = 'auto'
    requestAnimationFrame(() => {
      if (containerRef.value) {
        containerRef.value.style.scrollBehavior = previousScrollBehavior
      }
    })
  })
}

function followIfNearBottom(behavior = 'auto') {
  if (isNearBottom.value) {
    scrollToBottom(behavior)
  } else {
    showJumpButton.value = true
    hasNewActivity.value = true
  }
}

function handleScroll() {
  if (!containerRef.value) return
  const { scrollHeight, scrollTop, clientHeight } = containerRef.value
  isNearBottom.value = scrollHeight - scrollTop - clientHeight < 96
  if (isNearBottom.value) {
    showJumpButton.value = false
    hasNewActivity.value = false
  }
}

function jumpToBottom() {
  scrollToBottom('smooth')
}

function roleLabel(role) {
  if (role === 'user') return 'You'
  if (role === 'system') return 'SYS'
  return 'AI'
}

function readableRole(role) {
  if (role === 'user') return '你'
  if (role === 'system') return '系统'
  return 'EchoMind'
}

function messageKey(message, index) {
  return message?.clientId || message?.id || message?.messageId || `${message?.role || 'message'}-${index}`
}

function parseMessageDate(message) {
  const value = message?.timestamp || message?.createdAt || message?.time
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

function formatMessageTime(message) {
  const date = parseMessageDate(message)
  if (!date) return ''
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

function formatDateDivider(date) {
  const today = startOfDay(new Date())
  const target = startOfDay(date)
  const diffDays = Math.floor((today - target) / 86400000)
  if (diffDays === 0) return '今天'
  if (diffDays === 1) return '昨天'
  return date.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' })
}

function startOfDay(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

function canCopyMessage(message) {
  return Boolean(String(message?.content || '').trim())
}

async function copyMessage(message, key) {
  const text = String(message?.content || '').trim()
  if (!text) return
  try {
    await copyText(text)
    copiedKey.value = key
    if (copiedTimer) window.clearTimeout(copiedTimer)
    copiedTimer = window.setTimeout(() => {
      copiedKey.value = ''
    }, 1300)
  } catch (e) {
    ElMessage.error('复制失败，请手动选择内容复制')
  }
}

onBeforeUnmount(() => {
  if (scrollFrame) {
    cancelAnimationFrame(scrollFrame)
  }
  if (thinkingTimer) {
    window.clearInterval(thinkingTimer)
  }
  if (copiedTimer) {
    window.clearTimeout(copiedTimer)
  }
})

onMounted(() => {
  thinkingTimer = window.setInterval(() => {
    thinkingStep.value += 1
  }, 1400)
})

defineExpose({ followIfNearBottom, scrollToBottom })
</script>
