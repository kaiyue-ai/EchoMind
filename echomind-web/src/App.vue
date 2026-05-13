<template>
  <WorkbenchShell
    :sessions="sessions"
    :sessions-loading="sessionsLoading"
    :active-session-id="activeSessionId"
    @refresh-sessions="loadSessions"
    @new-session="newSession"
    @open-session="openSession"
  />
</template>

<script setup>
import { onMounted, provide, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import WorkbenchShell from './components/workbench/WorkbenchShell.vue'
import { useChatStore } from './stores/chat'
import { useSessionStore } from './stores/sessions'

const route = useRoute()
const router = useRouter()
const chatStore = useChatStore()
const sessionStore = useSessionStore()
const { sessions, loading: sessionsLoading, activeSessionId } = storeToRefs(sessionStore)

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

provide('refreshSessions', loadSessions)

onMounted(loadSessions)
</script>
