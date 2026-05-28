<template>
  <div ref="containerRef" class="message-list">
    <div v-if="loadingHistory" class="message-history-skeleton" aria-hidden="true">
      <article v-for="item in 3" :key="item" class="message-row">
        <div class="message-avatar skeleton-avatar"></div>
        <div class="message-content">
          <el-skeleton animated :rows="item === 1 ? 2 : 4" />
        </div>
      </article>
    </div>

    <div v-else-if="messages.length === 0" class="empty-chat">
      <div class="empty-chat-mark">EM</div>
      <h1>从一个任务开始</h1>
      <p>选择 Agent 和模型，直接把问题交给 EchoMind。</p>
    </div>

    <article
      v-for="(message, index) in messages"
      :key="index"
      :class="['message-row', `message-${message.role}`]"
    >
      <div class="message-avatar">
        <img v-if="message.role === 'user' && authStore.user?.avatarUrl" :src="authStore.user.avatarUrl" alt="用户头像" />
        <span v-else>{{ roleLabel(message.role) }}</span>
      </div>
      <div class="message-content">
        <MarkdownRenderer v-if="message.role === 'assistant'" :content="message.content" />
        <div v-else class="plain-message">{{ message.content }}</div>
        <div v-if="message.attachments?.length" class="message-attachments">
          <img
            v-for="att in message.attachments"
            :key="att.uri || att.url"
            v-show="att.type === 'image'"
            :src="att.url"
            :alt="att.fileName || '聊天图片'"
            class="message-image"
          />
        </div>
        <div v-if="message.skillResults?.length" class="skill-result-line">
          Skills: {{ message.skillResults.join(', ') }}
        </div>
      </div>
    </article>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'
import MarkdownRenderer from './MarkdownRenderer.vue'
import { useAuthStore } from '../../stores/auth'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  loadingHistory: { type: Boolean, default: false }
})

const containerRef = ref(null)
const authStore = useAuthStore()

watch(() => props.messages, () => {
  if (!props.loadingHistory) {
    nextTick(() => scrollToBottom('auto'))
  }
}, { deep: true })

watch(() => props.loadingHistory, (loading, previousLoading) => {
  if (previousLoading && !loading) {
    nextTick(() => scrollToBottom('auto'))
  }
})

function scrollToBottom(behavior = 'auto') {
  if (containerRef.value) {
    const previousScrollBehavior = containerRef.value.style.scrollBehavior
    containerRef.value.style.scrollBehavior = behavior === 'smooth' ? 'smooth' : 'auto'
    containerRef.value.scrollTop = containerRef.value.scrollHeight
    requestAnimationFrame(() => {
      if (containerRef.value) {
        containerRef.value.style.scrollBehavior = previousScrollBehavior
      }
    })
  }
}

function roleLabel(role) {
  if (role === 'user') return 'You'
  if (role === 'system') return 'SYS'
  return 'AI'
}

defineExpose({ scrollToBottom })
</script>
