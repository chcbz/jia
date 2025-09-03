<template>
  <div class="short-link-container">
    <var-tabs v-model:active="index">
      <var-tab>{{ $t('dwz.short') }}</var-tab>
      <var-tab>{{ $t('dwz.restore') }}</var-tab>
    </var-tabs>

    <var-tabs-items v-model:active="index" class="tab-items-container">
      <var-tab-item>
        <div>
          <var-input
            type="textarea"
            v-model="originalUrl"
            id="originalUrl"
            :placeholder="$t('dwz.long_url_placeholder')"
            rows="3">
          </var-input>

          <var-select
            :placeholder="$t('dwz.expire')" :options="expireList"
            v-model="expireYear" label-key="name" value-key="value" />

          <var-button
            type="primary"
            block
            @click="toShort">
            {{$t('dwz.short')}}
          </var-button>
        </div>

        <div class="result-container">
          <span id="shortUrl">{{shortUrl}}</span>
          <var-button
            id="copyBtn"
            size="small"
            @click="copyContent"
            v-if="shortUrl!=''"
            class="copy-btn">
            {{$t('app.copy')}}
          </var-button>
          <div class="qrcode-container" v-if="qrcodeUrl">
            <img :src="qrcodeUrl" class="qrcode-img">
          </div>
        </div>
      </var-tab-item>

      <var-tab-item>
        <div>
          <var-input
            type="textarea"
            v-model="uri"
            id="uri"
            :placeholder="$t('dwz.short_url_placeholder')"
            rows="3">
          </var-input>

          <var-button
            type="primary"
            block
            @click="toLong">
            {{$t('dwz.restore')}}
          </var-button>
        </div>

        <div class="result-container">
          <span class="long-url">{{longUrl}}</span>
        </div>
      </var-tab-item>
    </var-tabs-items>
  </div>
</template>

<script>
import QRCode from 'qrcode'
import { Dialog } from '@varlet/ui'
import { useGlobalStore } from '../stores/global'
import { useApiStore } from '../stores/api'
import { useUtilStore } from '../stores/util'

export default {
  created: function () {
    const globalStore = useGlobalStore()
    globalStore.setTitle(this.$t('dwz.title'))
    globalStore.setShowBack(false)
    globalStore.setShowMore(false)
  },
  methods: {
    async generateQRCode(text) {
      console.log('Generating QR code for:', text)
      try {
        return await QRCode.toDataURL(text, { width: 200 })
      } catch (error) {
        console.error('Failed to generate QR code:', error)
        return ''
      }
    },
    toShort: function () {
      const apiStore = useApiStore()
      const globalStore = useGlobalStore()
      const utilStore = useUtilStore()
      var baseUrl = apiStore.baseUrl
      var jiacn = globalStore.getJiacn
      const _this = this

      if (!this.originalUrl) {
        Dialog({
          title: this.$t('app.alert'),
          message: this.$t('dwz.long_url_empty'),
          confirmButtonText: this.$t('app.confirm')
        })
        return
      }

      if (!jiacn) {
        Dialog({
          title: _this.$t('app.notify'),
          message: _this.$t('dwz.subscribe_notify'),
          confirmButtonText: _this.$t('app.confirm'),
          onConfirm: () => {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return
      }

      // Calculate expire time considering leap years
      const now = new Date()
      const futureDate = new Date(now.getFullYear() + parseInt(_this.expireYear), now.getMonth(), now.getDate())
      const expireTime = utilStore.toTimeStamp(futureDate)

      this.$http.post(baseUrl + '/dwz/gen', {
        jiacn: jiacn,
        orig: _this.originalUrl,
        expireTime: expireTime
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.shortUrl = apiStore.dwzDomain + res.data.data
        } else {
          Dialog({
            title: _this.$t('app.alert'),
            message: res.data.msg,
            confirmButtonText: _this.$t('app.confirm')
          })
        }
      }).catch(err => {
        Dialog({
          title: _this.$t('app.alert'),
          message: _this.$t('dwz.network_error'),
          confirmButtonText: _this.$t('app.confirm')
        })
      })
    },
    toLong: function () {
      const apiStore = useApiStore()
      const globalStore = useGlobalStore()
      var baseUrl = apiStore.baseUrl
      var jiacn = globalStore.getJiacn
      const _this = this

      if (!this.uri) {
        Dialog({
          title: this.$t('app.alert'),
          message: this.$t('dwz.short_url_empty'),
          confirmButtonText: this.$t('app.confirm')
        })
        return
      }

      if (!jiacn) {
        Dialog({
          title: _this.$t('app.notify'),
          message: _this.$t('dwz.subscribe_notify'),
          confirmButtonText: _this.$t('app.confirm'),
          onConfirm: () => {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return
      }
      var uri = _this.uri
      if (uri.indexOf('/') !== -1) {
        uri = uri.substring(uri.lastIndexOf('/') + 1)
      }
      this.$http.get(baseUrl + '/dwz/restore', {
        params: {
          uri: uri
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.longUrl = res.data.data
        } else {
          Dialog({
            title: _this.$t('app.alert'),
            message: res.data.msg,
            confirmButtonText: _this.$t('app.confirm')
          })
        }
      }).catch(err => {
        Dialog({
          title: _this.$t('app.alert'),
          message: _this.$t('dwz.network_error'),
          confirmButtonText: _this.$t('app.confirm')
        })
      })
    },
    copyContent: function () {
      if (!navigator.clipboard) {
        // Fallback for older browsers
        const textarea = document.createElement('textarea')
        textarea.value = this.shortUrl
        document.body.appendChild(textarea)
        textarea.select()
        try {
          document.execCommand('copy')
          Dialog({
            title: this.$t('app.notify'),
            message: this.$t('phrase.copy_success'),
            confirmButtonText: this.$t('app.confirm')
          })
        } catch (err) {
          Dialog({
            title: this.$t('app.alert'),
            message: this.$t('phrase.copy_failed'),
            confirmButtonText: this.$t('app.confirm')
          })
        }
        document.body.removeChild(textarea)
        return
      }

      navigator.clipboard.writeText(this.shortUrl).then(() => {
        Dialog({
          title: this.$t('app.notify'),
          message: this.$t('phrase.copy_success'),
          confirmButtonText: this.$t('app.confirm')
        })
      }).catch(err => {
        Dialog({
          title: this.$t('app.alert'),
          message: this.$t('phrase.copy_failed'),
          confirmButtonText: this.$t('app.confirm')
        })
      })
    }
  },
  data () {
    return {
      index: 0, // 默认显示第一个面板
      originalUrl: '',
      expireList: [{
        name: '一年',
        value: '1'
      }, {
        name: '长期',
        value: '10'
      }],
      expireYear: '1',
      uri: '',
      shortUrl: '',
      longUrl: '',
      qrcodeUrl: '' // 存储生成的二维码URL
    }
  },
  watch: {
    async shortUrl(newUrl) {
      if (newUrl) {
        this.qrcodeUrl = await this.generateQRCode(newUrl)
      } else {
        this.qrcodeUrl = ''
      }
    }
  },
}
</script>

<style scoped>
.short-link-container {
  background-color: #FFFFFF;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.tab-items-container {
  height: calc(100% - 44px);
  box-sizing: border-box;
  overflow-y: auto;
  flex: 1;
}

.tab-items-container .var-tab-item {
  width: 100%;
  height: auto;
  min-height: 100%;
}

.tab-items-container .var-tab-item > div {
  pointer-events: auto;
  height: auto;
  display: flex;
  flex-direction: column;
  padding: 0 10px;
  gap: 15px;
}

.tab-items-container .var-button[block] {
  width: 100%;
}

.result-container {
  text-align: center;
  margin: 25px 15px;
}

.copy-btn {
  margin-left: 10px;
}

.qrcode-container {
  margin-top: 25px;
}

.qrcode-img {
  width: 200px;
  height: 200px;
  max-width: 100%;
}

.long-url {
  word-break: break-all;
}
</style>
