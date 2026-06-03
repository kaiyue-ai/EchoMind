<template>
  <button class="brand-mark avatar-upload" type="button" title="更换头像" @click.stop="chooseAvatar">
    <img v-if="authStore.user?.avatarUrl" :src="authStore.user.avatarUrl" alt="用户头像" />
    <span v-else class="miku-mark avatar-fallback" aria-label="初音未来头像">
      <span class="miku-hair left"></span>
      <span class="miku-hair right"></span>
      <span class="miku-face"></span>
    </span>
  </button>
  <input ref="avatarInput" class="avatar-input" type="file" accept="image/jpeg,image/png,image/gif,image/webp" @change="uploadAvatar" />
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../stores/auth'

const authStore = useAuthStore()
const avatarInput = ref(null)

function chooseAvatar() {
  avatarInput.value?.click()
}

async function uploadAvatar(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning('头像不能超过 2MB')
    return
  }
  try {
    await authStore.uploadAvatar(file)
    ElMessage.success('头像已更新')
  } catch (e) {
    ElMessage.error(authStore.error || '头像上传失败')
  }
}
</script>
