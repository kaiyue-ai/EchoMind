import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/chat',
    name: 'Chat',
    component: () => import('../views/ChatView.vue'),
    meta: { title: '智能对话' }
  },
  {
    path: '/skills',
    name: 'Skills',
    component: () => import('../views/SkillsView.vue'),
    meta: { title: 'Skill 市场' }
  },
  {
    path: '/agents',
    name: 'Agents',
    component: () => import('../views/AgentsView.vue'),
    meta: { title: 'Agent 管理' }
  },
  {
    path: '/memory',
    name: 'Memory',
    component: () => import('../views/MemoryView.vue'),
    meta: { title: '记忆管理' }
  },
  {
    path: '/mcp',
    name: 'MCP',
    component: () => import('../views/McpView.vue'),
    meta: { title: 'MCP 工具' }
  },
  {
    path: '/team',
    name: 'Team',
    component: () => import('../views/TeamDashboard.vue'),
    meta: { title: 'Agent Team' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
