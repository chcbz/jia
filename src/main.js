import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import routes from './router/index'
import './assets/icon/iconfont.css'
import Varlet from '@varlet/ui'
import '@varlet/ui/es/style'
import axios from 'axios'
import { useApiStore } from './stores/api'

// 创建应用实例
const app = createApp(App)
app.use(Varlet)

// 初始化Pinia
const pinia = createPinia()
app.use(pinia)

// 配置axios
const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 60000
})

// 添加请求拦截器
http.interceptors.request.use(async config => {
  const apiStore = useApiStore()
  try {
    const token = await apiStore.token()
    if (!token) {
      return Promise.reject(new Error('No token available - redirecting'))
    }
    config.headers.Authorization = `Bearer ${token}`
    return config
  } catch (error) {
    return Promise.reject(error)
  }
})

// 添加响应拦截器
http.interceptors.response.use(
  response => response,
  async error => {
    const { config, response } = error
    if (response?.status === 401) {
      const apiStore = useApiStore()
      // 清除过期的token并尝试重新获取
      apiStore.cleanToken()
      try {
        const newToken = await apiStore.token()
        if (newToken) {
          // 重试原始请求
          config.headers.Authorization = `Bearer ${newToken}`
          return http(config)
        }
      } catch (e) {
        return Promise.reject(error)
      }
    }
    return Promise.reject(error)
  }
)

// 将axios挂载到Vue原型和全局对象上
app.config.globalProperties.$http = http
window.$http = http

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
