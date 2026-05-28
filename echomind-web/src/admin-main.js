import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import AdminApp from './AdminApp.vue'
import router from './admin-router'
import { applyBackgroundPreference, applyThemePreference } from './stores/ui'
import './styles/admin.css'

applyThemePreference()
applyBackgroundPreference()

const app = createApp(AdminApp)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
