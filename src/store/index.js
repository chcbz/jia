import Vue from 'vue'
import Vuex from 'vuex'

Vue.use(Vuex)

// no transitoin in demo site
const shouldUseTransition = !/transition=none/.test(location.href)

const utilStore = {
  state: {
    getLocalStorage: function (key) {
      var data = localStorage.getItem(key)
      if (data) {
        var dataObj = JSON.parse(data)
        if (new Date().getTime() > dataObj.expTime) {
          console.log('信息已过期')
          localStorage.removeItem(key)
        } else {
          return dataObj.data
        }
      } else {
        return null
      }
    },
    setLocalStorage: function (key, value, expTime) {
      localStorage.setItem(key, JSON.stringify({data: value, expTime: expTime}))
    },
    cleanLocalStorage: function (key) {
      localStorage.clear()
    },
    toTimeStamp: function (date) {
      return parseInt(date.getTime() / 1000)
    },
    fromTimeStamp: function (time, fmt) {
      var date = new Date(time * 1000)
      if (fmt) {
        var o = {
          'M+': date.getMonth() + 1,
          'D+': date.getDate(),
          'H+': date.getHours(),
          'm+': date.getMinutes(),
          's+': date.getSeconds(),
          'S+': date.getMilliseconds()
        }
        // 因为date.getFullYear()出来的结果是number类型的,所以为了让结果变成字符串型，下面有两种方法：
        if (/(Y+)/.test(fmt)) {
          // 第一种：利用字符串连接符“+”给date.getFullYear()+''，加一个空字符串便可以将number类型转换成字符串。
          fmt = fmt.replace(RegExp.$1, (date.getFullYear() + '').substr(4 - RegExp.$1.length))
        }
        for (var k in o) {
          if (new RegExp('(' + k + ')').test(fmt)) {
            // 第二种：使用String()类型进行强制数据类型转换String(date.getFullYear())，这种更容易理解。
            fmt = fmt.replace(RegExp.$1, (RegExp.$1.length === 1) ? (o[k]) : (('00' + o[k]).substr(String(o[k]).length)))
          }
        }
        return fmt
      } else {
        return date
      }
    },
    closeWindow: function () {
      if (window.WeixinJSBridge) { // 微信中关闭
        window.WeixinJSBridge.call('closeWindow')
      } else if (navigator.userAgent.indexOf('Firefox') !== -1 || navigator.userAgent.indexOf('Chrome') !== -1) { // Firefox或Chrome中关闭
        window.location.href = 'about:blank'
      } else {
        window.opener = null
        window.open('', '_self')
        window.close()
      }
    },
    demoScrollTop: 0,
    isLoading: false,
    direction: shouldUseTransition ? 'forward' : ''
  },
  mutations: {
    updateDemoPosition (state, payload) {
      state.demoScrollTop = payload.top
    },
    updateLoadingStatus (state, payload) {
      state.isLoading = payload.isLoading
    },
    updateDirection (state, payload) {
      if (!shouldUseTransition) {
        return
      }
      state.direction = payload.direction
    }
  },
  actions: {
    updateDemoPosition ({ commit }, top) {
      commit({ type: 'updateDemoPosition', top: top })
    }
  }
}

const apiStore = {
  state: {
    baseUrl: 'https://api.chaoyoufan.net',
    token: function () {
      var accessToken = utilStore.state.getLocalStorage('api_token')
      if (!accessToken) {
        var xhr = new XMLHttpRequest()
        if (globalStore.state.user.openid) {
          xhr.open('POST', apiStore.state.baseUrl + '/oauth/token?grant_type=password&username=wx-' + globalStore.state.user.openid + '&password=wxpwd', false)
          xhr.setRequestHeader('Content-Type', 'application/json;charset=UTF-8')
          xhr.setRequestHeader('Authorization', 'Basic amlhX2NsaWVudDpqaWFfc2VjcmV0')
        } else {
          xhr.open('GET', apiStore.state.baseUrl + '/oauth/token?grant_type=client_credentials&client_id=jia_client&client_secret=jia_secret', false)
          xhr.setRequestHeader('Content-Type', 'application/json;charset=UTF-8')
        }
        xhr.send(null)
        var data = JSON.parse(xhr.responseText)
        accessToken = data.access_token
        utilStore.state.setLocalStorage('api_token', accessToken, new Date().getTime() + data.expires_in * 1000 - 60000)
      }
      return accessToken
    }
  },
  mutations: {
    cleanToken (state) {
      utilStore.state.setLocalStorage('api_token', null, new Date().getTime())
    }
  }
}

const globalStore = {
  namespaced: true,
  state: {
    user: {
      appid: 'wxd59557202ddff2d5',
      // openid: 'oH2zD1PUPvspicVak69uB4wDaFLg',
      // jiacn: 'oH2zD1PUPvspicVak69uB4wDaFLg',
      openid: utilStore.state.getLocalStorage('openid'),
      jiacn: utilStore.state.getLocalStorage('jiacn'),
      wxToken: utilStore.state.getLocalStorage('wxToken')
    },
    menu: {},
    title: '',
    showBack: false,
    showMore: false,
    copyright: '饭粒保',
    copyrightLink: 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&&scene=110#wechat_redirect'
  },
  mutations: {
    setOpenid (state, id) {
      utilStore.state.setLocalStorage('openid', id, new Date().getTime() + 7 * 24 * 60 * 60 * 1000)
      state.user.openid = id
    },
    setWxToken (state, payload) {
      utilStore.state.setLocalStorage('wxToken', payload.accessToken, new Date().getTime() + payload.expiresIn * 1000 - 60000)
      state.user.wxToken = payload.accessToken
    },
    setJiacn (state, id) {
      utilStore.state.setLocalStorage('jiacn', id, new Date().getTime() + 7 * 24 * 60 * 60 * 1000)
      state.user.jiacn = id
    },
    setMenu (state, payload) {
      state.menu = {}
      state.showMore = payload.menus.length > 0
      payload.menus.forEach((item, index) => {
        state.menu[item.key] = item.value
        payload.event.$root.$on('toMenu_' + item.key, item.fn)
      })
    },
    toMenu (state, payload) {
      payload.event.$root.$emit('toMenu_' + payload.key)
    },
    setTitle (state, title) {
      state.title = title
    },
    setShowBack (state, showBack) {
      state.showBack = showBack
    },
    setShowMore (state, showMore) {
      state.showMore = showMore
    }
  }
}

export default new Vuex.Store({
  modules: {
    util: utilStore,
    api: apiStore,
    global: globalStore
  }
})
