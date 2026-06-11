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
          <span>Agent Workbench</span>
        </div>
      </div>

      <div class="login-copy">
        <Transition name="login-copy-shift" mode="out-in">
          <div :key="mode">
            <h1>{{ mode === 'login' ? '登录工作台' : '创建账号' }}</h1>
            <p>{{ mode === 'login' ? '使用独立账号进入你的对话空间。' : '新账号不会继承 default 兼容历史。' }}</p>
          </div>
        </Transition>
      </div>

      <el-segmented
        v-model="mode"
        class="login-mode"
        :options="[
          { label: '登录', value: 'login' },
          { label: '注册', value: 'register' }
        ]"
      />

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
          {{ loading ? '正在连接...' : mode === 'login' ? '进入工作台' : '创建并进入' }}
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
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const { loading } = storeToRefs(authStore)

const mode = ref('login')
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
    if (mode.value === 'login') {
      await authStore.login(username.value, password.value)
      ElMessage.success('登录成功')
    } else {
      await authStore.register(username.value, password.value)
      ElMessage.success('账号已创建')
    }
    const redirect = router.currentRoute.value.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/chat')
  } catch (e) {
    ElMessage.error(authStore.error || '操作失败')
  }
}
</script>
