<template>
  <main class="login-page">
    <section :class="['login-panel', { 'is-submitting': loading }]">
      <div class="login-brand">
        <span class="brand-mark miku-mark" aria-label="初音未来头像">
          <span class="miku-hair left"></span>
          <span class="miku-hair right"></span>
          <span class="miku-face"></span>
        </span>
        <div>
          <strong>EchoMind</strong>
          <span>项目三管理端</span>
        </div>
      </div>

      <div class="login-copy">
        <h1>登录管理端</h1>
        <p>查看项目三 Trace、Token、脱敏和告警数据。</p>
      </div>

      <el-form class="login-form" @submit.prevent="submit">
        <el-form-item label="用户名" :error="fieldErrors.username">
          <el-input
            v-model="username"
            autocomplete="username"
            placeholder="用户名"
            @input="fieldErrors.username = ''"
          />
        </el-form-item>
        <el-form-item label="密码" :error="fieldErrors.password">
          <el-input
            v-model="password"
            autocomplete="current-password"
            placeholder="密码"
            show-password
            type="password"
            @input="fieldErrors.password = ''"
          />
        </el-form-item>
        <el-button
          :class="['login-submit', { 'is-ready': canSubmit }]"
          type="primary"
          :loading="loading"
          @click="submit"
        >
          {{ loading ? '正在校验...' : '进入管理端' }}
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAdminAuthStore } from '../stores/adminAuth'

const router = useRouter()
const authStore = useAdminAuthStore()
const { loading } = storeToRefs(authStore)

const username = ref('admin')
const password = ref('')
const fieldErrors = ref({ username: '', password: '' })
const canSubmit = computed(() => username.value.trim().length > 0 && password.value.length > 0 && !loading.value)

async function submit() {
  fieldErrors.value = {
    username: username.value.trim() ? '' : '请输入用户名',
    password: password.value ? '' : '请输入密码'
  }
  if (fieldErrors.value.username || fieldErrors.value.password) {
    return
  }
  try {
    await authStore.login(username.value, password.value)
    ElMessage.success('登录成功')
    const redirect = router.currentRoute.value.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/usage')
  } catch (e) {
    ElMessage.error(authStore.error || '登录失败')
  }
}
</script>
