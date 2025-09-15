import { defineStore } from 'pinia'
import { useUtilStore } from './util'

export const useGlobalStore = defineStore('global', {
  state: () => ({
    user: {
      appid: import.meta.env.VITE_WXMP_APPID,
      openid: null,
      jiacn: null,
      wxToken: null
    },
    menu: {},
    title: import.meta.env.VITE_APP_TITLE,
    showBack: false,
    showMore: false,
    showSideMenu: false,
    showRightSidebar: false,
    copyright: import.meta.env.VITE_COPYRIGHT,
    copyrightLink: import.meta.env.VITE_COPYRIGHT_LINK
  }),
  getters: {
    getOpenid () {
      const utilStore = useUtilStore()
      return utilStore.getLocalStorage('openid') || this.user.openid
    },
    getJiacn () {
      const utilStore = useUtilStore()
      return utilStore.getLocalStorage('jiacn') || this.user.jiacn
    },
    getWxToken () {
      const utilStore = useUtilStore()
      return utilStore.getLocalStorage('wxToken') || this.user.wxToken
    }
  },
  actions: {
    setOpenid (id) {
      console.log('Setting openid:', id)
      const utilStore = useUtilStore()
      utilStore.setLocalStorage('openid', id, new Date().getTime() + 7 * 24 * 60 * 60 * 1000)
      this.user.openid = id
    },
    setWxToken (payload) {
      const utilStore = useUtilStore()
      utilStore.setLocalStorage('wxToken', payload.accessToken, new Date().getTime() + payload.expiresIn * 1000 - 60000)
      this.user.wxToken = payload.accessToken
    },
    setJiacn (id) {
      console.log('Setting jiacn:', id)
      const utilStore = useUtilStore()
      utilStore.setLocalStorage('jiacn', id, new Date().getTime() + 7 * 24 * 60 * 60 * 1000)
      this.user.jiacn = id
    },
    setMenu (payload) {
      this.menu = {}
      this.showMore = payload.menus.length > 0
      payload.menus.forEach((item) => {
        this.menu[item.key] = item.value
        if (payload.event) {
          // Try both $root.$on and direct $on for compatibility
          if (payload.event.$root?.$on) {
            payload.event.$root.$on('toMenu_' + item.key, item.fn)
          } else if (payload.event.$on) {
            payload.event.$on('toMenu_' + item.key, item.fn)
          }
        }
      })
    },
    toMenu (payload) {
      payload.event.$root.$emit('toMenu_' + payload.key)
    },
    setTitle (title) {
      this.title = title
    },
    setShowBack (showBack) {
      this.showBack = showBack
    },
    setShowMore (showMore) {
      this.showMore = showMore
    },
    toggleSideMenu () {
      this.showSideMenu = !this.showSideMenu
    },
    toggleRightSidebar () {
      this.showRightSidebar = !this.showRightSidebar
    }
  }
})
