<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="login-brand">
        <span class="brand-mark miku-mark" aria-label="初音未来头像">
          <span class="miku-hair left"></span>
          <span class="miku-hair right"></span>
          <span class="miku-face"></span>
        </span>
        <div>
          <strong>EchoMind</strong>
          <span>Agent 控制台</span>
        </div>
      </div>

      <div class="login-copy">
        <h1>{{ mode === 'login' ? '登录工作台' : '创建账号' }}</h1>
        <p>{{ mode === 'login' ? '使用独立账号进入你的对话空间。' : '新账号不会继承 default 兼容历史。' }}</p>
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
        <el-form-item label="用户名">
          <el-input v-model="username" autocomplete="username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            autocomplete="current-password"
            placeholder="密码"
            show-password
            type="password"
          />
        </el-form-item>
        <el-button class="login-submit" type="primary" :loading="loading" @click="submit">
          {{ mode === 'login' ? '进入控制台' : '创建并进入' }}
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<script setup>
import { ref } from 'vue'
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

async function submit() {
  if (!username.value.trim() || !password.value) {
    ElMessage.warning('请输入用户名和密码')
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
    router.replace('/chat')
  } catch (e) {
    ElMessage.error(authStore.error || '操作失败')
  }
}
</script>
