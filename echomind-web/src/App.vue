<template>
  <Transition name="app-shell" mode="out-in">
    <router-view v-if="route.path === '/login'" key="login" />
    <WorkbenchShell
      v-else
      key="workbench"
      :sessions="sessions"
      :sessions-loading="sessionsLoading"
      :deleting-session-id="deletingId"
      :active-session-id="activeSessionId"
      @refresh-sessions="loadSessions"
      @new-session="newSession"
      @open-session="openSession"
      @delete-session="deleteSession"
    />
  </Transition>
</template>

<script setup>
import { onMounted, provide, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import WorkbenchShell from './components/workbench/WorkbenchShell.vue'
import { useChatStore } from './stores/chat'
import { useSessionStore } from './stores/sessions'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const sessionStore = useSessionStore()
const authStore = useAuthStore()
const { sessions, loading: sessionsLoading, deletingId, activeSessionId } = storeToRefs(sessionStore)

watch(() => [route.path, route.query.sessionId, chatStore.sessionId], ([path, sid, currentChatSessionId]) => {
  if (path === '/chat') {
    sessionStore.setActive(sid || currentChatSessionId)
  } else if (currentChatSessionId) {
    sessionStore.setActive(currentChatSessionId)
  }
}, { immediate: true })

async function loadSessions() {
  try {
    await sessionStore.loadSessions()
  } catch (e) {
    /* 历史列表失败不阻塞工作台 */
  }
}

function openSession(sessionId) {
  router.push({ path: '/chat', query: { sessionId } })
}

function newSession() {
  chatStore.resetSession()
  router.replace({ path: '/chat' })
}

async function deleteSession(session) {
  const sessionId = session?.sessionId || session
  if (!sessionId) return
  const deletingCurrent = activeSessionId.value === sessionId
  try {
    await ElMessageBox.confirm(
      '确定删除这条会话历史吗？删除后不可恢复。',
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    )
    await sessionStore.deleteSession(sessionId)
    if (deletingCurrent) {
      chatStore.resetSession()
      router.replace({ path: '/chat' })
    }
    ElMessage.success('会话已删除')
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error('删除失败: ' + (e.response?.data?.error || e.message))
  }
}

provide('refreshSessions', loadSessions)
provide('deleteSession', deleteSession)

onMounted(() => {
  if (route.path !== '/login') {
    loadSessions()
  }
})

watch(() => authStore.user?.userId, async (userId, previousUserId) => {
  if (!userId || userId === previousUserId || route.path === '/login') return
  await loadSessions()
})

watch(() => route.path, async (path, previousPath) => {
  if (path !== '/login' && previousPath === '/login' && authStore.isAuthenticated) {
    await loadSessions()
  }
})
</script>
