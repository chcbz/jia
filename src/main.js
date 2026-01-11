import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import routes from './router/index'
import './assets/icon/iconfont.css'
import Varlet from '@varlet/ui'
import '@varlet/ui/es/style'

// 创建应用实例
const app = createApp(App)
app.use(Varlet)

// 初始化Pinia
const pinia = createPinia()
app.use(pinia)

// 全局配置
app.config.productionTip = false
app.config.devtools = true

if (/no-background-color=true/.test(location.href)) {
  document.body.style['background-color'] = '#fff'
}

// 路由配置
import { createRouter, createWebHistory } from 'vue-router'
const router = createRouter({
  history: createWebHistory(),
  routes
})

// i18n配置
import { i18n } from './stores/i18n'
app.use(i18n)

app.use(router)

// 挂载应用
app.mount('#app')
