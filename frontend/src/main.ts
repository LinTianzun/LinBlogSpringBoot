import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setAuthErrorHandler } from './api/request'
import { useSessionStore } from './stores/session'
import './styles/main.less'
import './styles/art-tokens.less'
// element-plus 暗色变量（.dark 选择器版）；组件样式由 ElementPlusResolver 按需引入
import 'element-plus/theme-chalk/dark/css-vars.css'
// ElMessage/ElMessageBox 是函数式 API，不走模板解析，样式需手动引入
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
// v-loading 指令按需引入不含指令注册，需手动全局注册
import { ElLoadingDirective } from 'element-plus'
import 'element-plus/es/components/loading/style/css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.directive('loading', ElLoadingDirective)

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
