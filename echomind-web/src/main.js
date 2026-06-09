import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import App from './App.vue'
import router from './router'
import { applyBackgroundPreference, applyThemePreference } from './stores/ui'
import './styles/main.css'

applyThemePreference()
applyBackgroundPreference()

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
