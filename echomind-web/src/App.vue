<template>
  <WorkbenchShell
    :sessions="sessions"
    :sessions-loading="sessionsLoading"
    :deleting-session-id="deletingId"
    :active-session-id="activeSessionId"
    @refresh-sessions="loadSessions"
    @new-session="newSession"
    @open-session="openSession"
    @delete-session="deleteSession"
  />
</template>

<script setup>
import { onMounted, provide, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import WorkbenchShell from './components/workbench/WorkbenchShell.vue'
import { useChatStore } from './stores/chat'
import { useSessionStore } from './stores/sessions'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const sessionStore = useSessionStore()
const { sessions, loading: sessionsLoading, deletingId, activeSessionId } = storeToRefs(sessionStore)

watch(() => route.query.sessionId, (sid) => {
  sessionStore.setActive(sid)
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

onMounted(loadSessions)
</script>
