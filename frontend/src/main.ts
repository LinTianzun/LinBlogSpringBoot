import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setAuthErrorHandler } from './api/request'
import { useSessionStore } from './stores/session'
import './styles/main.less'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// §4.3/§7.2：40100/40102 全局认证失败 → 清会话并跳登录页（合并同目标导航）
setAuthErrorHandler(() => {
  const session = useSessionStore()
  session.clear()
  if (router.currentRoute.value.path !== '/login-register') {
    void router.push('/login-register')
  }
})

router.isReady().then(() => {
  app.mount('#app')
})
