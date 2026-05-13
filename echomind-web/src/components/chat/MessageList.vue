<template>
  <div ref="containerRef" class="message-list">
    <div v-if="messages.length === 0" class="empty-chat">
      <div class="empty-chat-mark">EM</div>
      <h1>从一个任务开始</h1>
      <p>选择 Agent 和模型，直接把问题交给 EchoMind。</p>
    </div>

    <article
      v-for="(message, index) in messages"
      :key="index"
      :class="['message-row', `message-${message.role}`]"
    >
      <div class="message-avatar">{{ roleLabel(message.role) }}</div>
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

const props = defineProps({
  messages: { type: Array, default: () => [] }
})

const containerRef = ref(null)

watch(() => props.messages, () => {
  nextTick(scrollToBottom)
}, { deep: true })

function scrollToBottom() {
  if (containerRef.value) {
    containerRef.value.scrollTop = containerRef.value.scrollHeight
  }
}

function roleLabel(role) {
  if (role === 'user') return 'You'
  if (role === 'system') return 'SYS'
  return 'AI'
}

defineExpose({ scrollToBottom })
</script>
