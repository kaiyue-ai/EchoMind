<template>
  <form class="chat-composer" @submit.prevent="$emit('send')">
    <div v-if="attachments.length" class="attachment-preview-row">
      <div v-for="att in attachments" :key="att.uri || att.url" class="attachment-preview">
        <img :src="att.url" :alt="att.fileName || '图片'" />
        <button type="button" class="attachment-remove" @click="$emit('removeAttachment', att)">x</button>
      </div>
    </div>
    <div class="composer-row">
      <el-input
        class="composer-input"
        :model-value="modelValue"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 5 }"
        resize="none"
        :disabled="loading"
        placeholder="输入任务或问题..."
        @update:model-value="$emit('update:modelValue', $event)"
        @keydown.enter.exact.prevent="$emit('send')"
      />
      <input ref="imageInput" type="file" accept="image/*" class="hidden-input" @change="handleImageSelected" />
      <div class="composer-actions">
        <el-button
          circle
          class="composer-tool-button"
          :loading="uploading"
          :disabled="loading"
          title="上传图片"
          @click="imageInput?.click()"
        >
          <el-icon><Picture /></el-icon>
        </el-button>
        <el-button
          type="primary"
          circle
          class="composer-send-button"
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
import { Picture, Promotion } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  attachments: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'send', 'selectImage', 'removeAttachment'])
const imageInput = ref(null)

const disabled = computed(() => props.loading || (!props.modelValue.trim() && props.attachments.length === 0))

function handleImageSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) emit('selectImage', file)
}
</script>
