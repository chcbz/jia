import { defineStore } from 'pinia'

export const useUtilStore = defineStore('util', {
  state: () => ({
    demoScrollTop: 0,
    isLoading: false,
    direction: !/transition=none/.test(location.href) ? 'forward' : ''
  }),
  actions: {
    getLocalStorage(key) {
      const data = localStorage.getItem(key)
      if (data) {
        const dataObj = JSON.parse(data)
        if (new Date().getTime() > dataObj.expTime) {
          console.log('信息已过期')
          localStorage.removeItem(key)
        } else {
          return dataObj.data
        }
      }
      return null
    },
    setLocalStorage(key, value, expTime) {
      localStorage.setItem(key, JSON.stringify({data: value, expTime}))
    },
    removeLocalStorage(key) {
      localStorage.removeItem(key)
    },
    cleanLocalStorage() {
      localStorage.clear()
    },
    toTimeStamp(date) {
      return parseInt(date.getTime())
    },
    fromTimeStamp(time, fmt) {
      const date = new Date(time)
      if (fmt) {
        const o = {
          'M+': date.getMonth() + 1,
          'D+': date.getDate(),
          'H+': date.getHours(),
          'm+': date.getMinutes(),
          's+': date.getSeconds(),
          'S+': date.getMilliseconds()
        }
        if (/(Y+)/.test(fmt)) {
          fmt = fmt.replace(RegExp.$1, (date.getFullYear() + '').substr(4 - RegExp.$1.length))
        }
        for (const k in o) {
          if (new RegExp('(' + k + ')').test(fmt)) {
            fmt = fmt.replace(RegExp.$1, 
              (RegExp.$1.length === 1) ? (o[k]) : (('00' + o[k]).substr(String(o[k]).length)))
          }
        }
        return fmt
      }
      return date
    },
    getHashCode(str, caseSensitive) {
      if (!caseSensitive) {
        str = str.toLowerCase()
      }
      let hash = 1315423911
      for (let i = str.length - 1; i >= 0; i--) {
        const ch = str.charCodeAt(i)
        hash ^= ((hash << 5) + ch + (hash >> 2))
      }
      return (hash & 0x7FFFFFFF)
    },
    closeWindow() {
      if (window.WeixinJSBridge) {
        window.WeixinJSBridge.call('closeWindow')
      } else if (navigator.userAgent.indexOf('Firefox') !== -1 || 
                 navigator.userAgent.indexOf('Chrome') !== -1) {
        window.location.href = 'about:blank'
      } else {
        window.opener = null
        window.open('', '_self')
        window.close()
      }
    },
    updateDemoPosition(top) {
      this.demoScrollTop = top
    },
    updateLoadingStatus(isLoading) {
      this.isLoading = isLoading
    },
    updateDirection(direction) {
      if (!/transition=none/.test(location.href)) {
        this.direction = direction
      }
    },
    formatDate(dateStr) {
      const date = new Date(dateStr)
      return date.toLocaleString()
    }
  }
})
