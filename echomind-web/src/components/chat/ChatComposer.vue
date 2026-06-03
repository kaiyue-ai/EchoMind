<template>
  <form class="chat-composer" aria-label="聊天输入" @submit.prevent="requestSend">
    <div v-if="attachments.length" class="attachment-preview-row">
      <div v-for="att in attachments" :key="att.uri || att.url" class="attachment-preview">
        <img :src="att.url" :alt="att.fileName || '图片'" />
        <button type="button" class="attachment-remove" aria-label="移除图片" @click="$emit('removeAttachment', att)">
          <el-icon><Close /></el-icon>
        </button>
      </div>
    </div>
    <div
      :class="['composer-row', { 'has-content': hasContent, 'is-busy': loading || uploading }]"
      :aria-busy="loading || uploading"
    >
      <el-input
        class="composer-input"
        :model-value="modelValue"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 5 }"
        resize="none"
        :disabled="loading"
        :placeholder="loading ? '正在生成回复...' : '输入任务或问题...'"
        @update:model-value="$emit('update:modelValue', $event)"
        @compositionstart="isComposing = true"
        @compositionend="isComposing = false"
        @keydown.enter.exact="handleEnter"
      />
      <input ref="imageInput" type="file" accept="image/*" class="hidden-input" @change="handleImageSelected" />
      <div class="composer-actions">
        <el-button
          circle
          class="composer-tool-button"
          native-type="button"
          :loading="uploading"
          :disabled="loading || uploading"
          title="上传图片"
          aria-label="上传图片"
          @click="imageInput?.click()"
        >
          <el-icon><Picture /></el-icon>
        </el-button>
        <el-button
          type="primary"
          circle
          :class="['composer-send-button', { 'is-ready': !disabled }]"
          native-type="submit"
          :loading="loading"
          :disabled="disabled"
          title="发送消息"
          aria-label="发送消息"
        >
          <el-icon><Promotion /></el-icon>
        </el-button>
      </div>
    </div>
  </form>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Close, Picture, Promotion } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  attachments: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'send', 'selectImage', 'removeAttachment'])
const imageInput = ref(null)
const isComposing = ref(false)

const hasContent = computed(() => props.modelValue.trim().length > 0 || props.attachments.length > 0)
const disabled = computed(() => props.loading || props.uploading || (!props.modelValue.trim() && props.attachments.length === 0))

function requestSend() {
  if (!disabled.value) emit('send')
}

function handleEnter(event) {
  if (isComposing.value || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  requestSend()
}

function handleImageSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) emit('selectImage', file)
}
</script>
