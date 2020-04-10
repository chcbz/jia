import axios from 'axios'
import store from '@/store'
import router from '../router'
import { RefreshToken } from '@/api/user'
// import { setToken } from '@/libs/util'
import { setToken, getToken, localRead, localSave } from '@/libs/util'
// import { Spin } from 'iview'
const addErrorLog = errorInfo => {
  const { statusText, status, request: { responseURL } } = errorInfo
  let info = {
    type: 'ajax',
    code: status,
    mes: statusText,
    url: responseURL
  }
  if (!responseURL.includes('save_error_logger')) store.dispatch('addErrorLog', info)
}

/* 是否有请求正在刷新token */
window.isRefreshing = false

/* 被挂起的请求数组 */
let refreshSubscribers = []

/* push所有请求到数组中 */
function subscribeTokenRefresh (cb) {
  refreshSubscribers.push(cb)
}

/* 刷新请求（refreshSubscribers数组中的请求得到新的token之后会自执行，用新的token去请求数据） */
function onRrefreshed (token) {
  refreshSubscribers.map(cb => cb(token))
}

// 判断链接接的token输入方式
function judgeConfig (apiName, config, token) {
  if (apiName === '/user/get') {
    config.params['access_token'] = token
  } else {
    token = 'Bearer' + token // 把token加入到默认请求参数中
    config.headers.common['Authorization'] = token
  }
}

class HttpRequest {
  constructor (baseUrl = baseURL) {
    this.baseUrl = baseUrl
    this.queue = {}
  }
  getInsideConfig () {
    const config = {
      baseURL: this.baseUrl,
      headers: {
        //
      }
    }
    return config
  }
  destroy (url) {
    delete this.queue[url]
    if (!Object.keys(this.queue).length) {
      // Spin.hide()
    }
  }
  interceptors (instance, url) {
    // 请求拦截
    instance.interceptors.request.use(config => {
      var urlArr = config.url.split('/')
      urlArr.splice(0, 3)
      var apiName = '/' // 接口名
      for (var i = 0; i < urlArr.length; i++) {
        apiName = apiName + urlArr[i] + '/'
      }
      apiName = apiName.substr(0, apiName.length - 1)
      const tokenarr = localRead('tokenarr')
      if (tokenarr == null || tokenarr === '') {
      } else {
        if (apiName === '/oauth/token' || apiName === '/sms/gen' || apiName === '/sms/validate' || apiName === '/wx/mp/createJsapiSignature' || apiName === '/oauth/client/register') {} else {
          var expires_in = tokenarr.expires_in
          var now_time = new Date().getTime()
          // debugger
          var currentName = (router.currentRoute.name === 'login') ? 'home' : router.currentRoute.name
          // console.log((expires_in - now_time) / 60000)
          if (expires_in < now_time) {
            setToken('')
            localSave('currentName', currentName)
            // router.push('/login')
          } else if (now_time < expires_in && expires_in < now_time + 10 * 60 * 1000) { // token失效前十分钟，自动刷新
            if (!window.isRefreshing) {
              window.isRefreshing = true
              RefreshToken().then(res => {
                window.isRefreshing = false
                const data = res.data
                setToken(data)
                onRrefreshed(data.access_token)
                /* 执行onRefreshed函数后清空数组中保存的请求 */
                refreshSubscribers = []
              }).catch(err => {
                setToken('')
                localSave('currentName', currentName)
                router.push('/login')
                console.log(err)
              })
            }
            /* 把请求(token)=>{....}都push到一个数组中 */
            let retry = new Promise((resolve, reject) => {
              /* (token) => {...}这个函数就是回调函数 */
              subscribeTokenRefresh((token) => {
                // token回调
                judgeConfig(apiName, config, token)
                /* 将请求挂起 */
                resolve(config)
              })
            })
            return retry
          } else {
            // token回调
            var Ttoken = getToken()
            judgeConfig(apiName, config, Ttoken)
          }
        }
      }
      // 添加全局的loading...
      if (!Object.keys(this.queue).length) {
        // Spin.show() // 不建议开启，因为界面不友好
      }
      this.queue[url] = true
      return config
    }, error => {
      return Promise.reject(error)
    })
    // 响应拦截
    instance.interceptors.response.use(res => {
      this.destroy(url)
      const { data, status } = res
      return { data, status }
    }, error => {
      this.destroy(url)
      let errorInfo = error.response
      if (!errorInfo) {
        const { request: { statusText, status }, config } = JSON.parse(JSON.stringify(error))
        errorInfo = {
          statusText,
          status,
          request: { responseURL: config.url }
        }
      }
      // 响应状态系统更新为401自动跳转到登录页·hzs
      let errorStatus = error.response.status
      if (errorStatus === 401) {
        setToken('')
        var currentName = (router.currentRoute.name === 'login') ? 'home' : router.currentRoute.name
        localSave('currentName', currentName)
        router.push('/login')
      } else {}
      // end
      addErrorLog(errorInfo)
      return Promise.reject(error)
    })
  }
  request (options) {
    const instance = axios.create()
    options = Object.assign(this.getInsideConfig(), options)
    this.interceptors(instance, options.url)
    return instance(options)
  }
}
export default HttpRequest
