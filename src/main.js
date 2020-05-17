import objectAssign from 'object-assign'
import Vue from 'vue'
import App from './App'
import routes from './router/index'
import Vuex from 'vuex'
import store from './store'
import vuexI18n from 'vuex-i18n'
import VueRouter from 'vue-router'
import { sync } from 'vuex-router-sync'
import vuxLocales from './i18n/vux.yml'
import applicationLocales from './i18n/application.yml'
import './assets/icon/iconfont.css'

import { Group, Cell, DatetimePlugin, CloseDialogsPlugin, ConfigPlugin, BusPlugin, LocalePlugin, DevicePlugin, ToastPlugin, AlertPlugin, ConfirmPlugin, LoadingPlugin, WechatPlugin, AjaxPlugin, AppPlugin } from 'vux'

Vue.config.productionTip = false

Vue.config.devtools = true

Vue.use(VueRouter)
Vue.use(Vuex)

require('es6-promise').polyfill()

store.registerModule('i18n', vuexI18n.store)

Vue.use(vuexI18n.plugin, store)

if (/no-background-color=true/.test(location.href)) {
  document.body.style['background-color'] = '#fff'
}

const finalLocales = {
  'en': objectAssign(vuxLocales['en'], applicationLocales['en']),
  'zh-CN': objectAssign(vuxLocales['zh-CN'], applicationLocales['zh-CN'])
}

for (let i in finalLocales) {
  Vue.i18n.add(i, finalLocales[i])
}

Vue.component('group', Group)
Vue.component('cell', Cell)

Vue.use(LocalePlugin)
const nowLocale = Vue.locale.get()
if (/zh/.test(nowLocale)) {
  Vue.i18n.set('zh-CN')
} else {
  Vue.i18n.set('en')
}

// global VUX config
Vue.use(ConfigPlugin, {
  $layout: 'VIEW_BOX' // global config for VUX, since v2.5.12
})

// plugins
Vue.use(DevicePlugin)
Vue.use(ToastPlugin)
Vue.use(AlertPlugin)
Vue.use(ConfirmPlugin)
Vue.use(LoadingPlugin)
Vue.use(WechatPlugin)
Vue.use(AjaxPlugin)
Vue.use(BusPlugin)
Vue.use(DatetimePlugin)

// test
if (process.env.platform === 'app') {
  Vue.use(AppPlugin, store)
}

const FastClick = require('fastclick')
FastClick.attach(document.body)

const router = new VueRouter({
  mode: 'history',
  routes: routes
})

Vue.use(CloseDialogsPlugin, router)

sync(store, router)

// simple history management
const history = window.sessionStorage
history.clear()
let historyCount = history.getItem('count') * 1 || 0
history.setItem('/', 0)
let isPush = false
let endTime = Date.now()
let methods = ['push', 'go', 'replace', 'forward', 'back']

document.addEventListener('touchend', () => {
  endTime = Date.now()
})
methods.forEach(key => {
  let method = router[key].bind(router)
  router[key] = function (...args) {
    isPush = true
    method.apply(null, args)
  }
})

router.beforeEach(function (to, from, next) {
  console.log(to.path)
  var ua = navigator.userAgent.toLowerCase() // 获取判断用的对象
  if (ua.indexOf('micromessenger') !== -1) { // 在微信中打开
    // 如果是微信公众号授权页面，获取openid
    if (to.query.state && to.query.state.startsWith('wx_snsapi')) {
      var xhr = new XMLHttpRequest()
      xhr.open('GET', store.state.api.baseUrl + '/wx/mp/oauth2/access_token?appid=' + store.state.global.user.appid + '&code=' + to.query.code + '&access_token=' + store.state.api.token(), false)
      xhr.setRequestHeader('Content-Type', 'application/json;charset=UTF-8')
      xhr.send(null)
      var data = JSON.parse(xhr.responseText).data
      var wxToken = data.accessToken
      var openId = data.openId
      store.commit('global/setWxToken', {accessToken: wxToken, expiresIn: data.expiresIn})
      store.commit('global/setOpenid', openId)
      // 获取jiacn
      store.commit('cleanToken')
      xhr = new XMLHttpRequest()
      xhr.open('GET', store.state.api.baseUrl + '/oauth/userinfo?access_token=' + store.state.api.token(), false)
      xhr.setRequestHeader('Content-Type', 'application/json;charset=UTF-8')
      xhr.send(null)
      data = JSON.parse(xhr.responseText).data
      if (data) {
        store.commit('global/setJiacn', data.jiacn)
      } else if (to.query.state === 'wx_snsapi_userinfo') {
        // 获取用户信息
        xhr = new XMLHttpRequest()
        xhr.open('GET', store.state.api.baseUrl + '/wx/mp/oauth2/userinfo?access_token=' + wxToken + '&openid=' + openId + '&lang=zh_CN', false)
        xhr.send(null)
        data = JSON.parse(xhr.responseText).data
        // 保存用户
        var user = {}
        user.nickname = data.nickname
        user.sex = data.sexId
        user.city = data.city
        user.province = data.province
        user.country = data.country
        user.openid = openId
        user.referrer = to.query.referrer
        xhr = new XMLHttpRequest()
        xhr.open('POST', store.state.api.baseUrl + '/user/create', false)
        xhr.setRequestHeader('Content-Type', 'application/json;charset=UTF-8')
        xhr.send(JSON.stringify(user))
        data = JSON.parse(xhr.responseText).data
        store.commit('global/setJiacn', data.jiacn)
      }
      delete to.query.referrer
      delete to.query.state
      delete to.query.code
      next({path: to.path, query: to.query})
    } else if (store.state.global.user.openid === null) {
      window.location.href = 'https://open.weixin.qq.com/connect/oauth2/authorize?appid=' + store.state.global.user.appid + '&redirect_uri=' + encodeURIComponent(window.location.href) + '&response_type=code&scope=snsapi_base&state=wx_snsapi#wechat_redirect'
    }
  }
  store.commit('updateLoadingStatus', { isLoading: true })

  const toIndex = history.getItem(to.path)
  const fromIndex = history.getItem(from.path)

  if (toIndex) {
    if (!fromIndex || parseInt(toIndex, 10) > parseInt(fromIndex, 10) || (toIndex === '0' && fromIndex === '0')) {
      store.commit('updateDirection', { direction: 'forward' })
    } else {
      // 判断是否是ios左滑返回
      if (!isPush && (Date.now() - endTime) < 377) {
        store.commit('updateDirection', { direction: '' })
      } else {
        store.commit('updateDirection', { direction: 'reverse' })
      }
    }
  } else {
    ++historyCount
    history.setItem('count', historyCount)
    to.path !== '/' && history.setItem(to.path, historyCount)
    store.commit('updateDirection', { direction: 'forward' })
  }

  /* 路由发生变化修改页面title */
  if (to.meta.title) {
    document.title = Vue.i18n.translate(to.meta.title)
  }

  if (/\/http/.test(to.path)) {
    let url = to.path.split('http')[1]
    window.location.href = `http${url}`
  } else {
    store.commit('updateLoadingStatus', { isLoading: false })
    next()
  }
})

new Vue({
  store,
  router,
  render: h => h(App)
}).$mount('#app')
