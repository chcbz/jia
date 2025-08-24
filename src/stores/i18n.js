import { defineStore } from 'pinia'
import { createI18n } from 'vue-i18n'
import applicationLocales from '../i18n/application.yml'

export const useI18nStore = defineStore('i18n', {
  state: () => ({
    locale: 'zh-CN',
    messages: {
      'en': applicationLocales['en'],
      'zh-CN': applicationLocales['zh-CN']
    }
  }),
  actions: {
    setLocale(locale) {
      this.locale = locale
    },
    t(key) {
      return this.messages[this.locale][key] || key
    }
  }
})

export const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'en': applicationLocales['en'],
    'zh-CN': applicationLocales['zh-CN']
  }
})
