import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import AdminApp from './AdminApp.vue'
import router from './admin-router'
import { applyBackgroundPreference, applyThemePreference } from './stores/ui'
import './styles/admin.css'

applyThemePreference()
applyBackgroundPreference()

const app = createApp(AdminApp)

app.use(createPinia())
app.use(router)
app.mount('#app')
