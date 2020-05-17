<template>
  <div style="background-color: #FFFFFF;">
    <div>
      <tab v-model="index">
        <tab-item selected>{{ $t('dwz.short') }}</tab-item>
        <tab-item>{{ $t('dwz.restore') }}</tab-item>
      </tab>
    </div>
    <swiper v-model="index"
            :show-dots="false"
            height="480px">
      <swiper-item>
        <group label-width="4.5em" label-margin-right="1em">
          <x-textarea v-model="orgi" :placeholder="$t('dwz.long_url_placeholder')"></x-textarea>
          <popup-picker :title="$t('dwz.expire')" :data="expireList" v-model="expireYear" :columns="1" show-name></popup-picker>
          <x-button action-type="button" style="width:92%;"
                    type="primary"
                    @click.native="toShort">{{$t('dwz.short')}}</x-button>
        </group>
        <div style="text-align:center; margin: 25px 15px;">
          <span id="shortUrl">{{shortUrl}}</span>
          <x-button id="copyBtn" mini @click.native="copyContent" data-clipboard-action="copy" data-clipboard-target="#shortUrl" v-if="shortUrl!=''">{{$t('app.copy')}}</x-button>
          <qrcode style="margin-top: 25px;" :value="shortUrl" v-if="shortUrl!=''"></qrcode>
        </div>
      </swiper-item>
      <swiper-item>
        <group label-width="4.5em" label-margin-right="1em" label-align="right">
          <x-textarea v-model="uri" :placeholder="$t('dwz.short_url_placeholder')"></x-textarea>
          <x-button action-type="button" style="width:92%;"
                    type="primary"
                    @click.native="toLong">{{$t('dwz.restore')}}</x-button>
        </group>
        <div style="text-align:center; margin: 25px 15px;">
          <span style="word-break: break-all;">{{longUrl}}</span>
        </div>
      </swiper-item>
    </swiper>
  </div>
</template>

<script>
import { Tab, TabItem, Swiper, SwiperItem, Qrcode, dateFormat, TransferDom, Actionsheet, ConfirmPlugin, Group, XInput, XButton, XTextarea, AlertModule, PopupPicker } from 'vux'
import Clipboard from 'clipboard'

export default {
  created: function () {
    this.$store.commit('global/setTitle', this.$t('dwz.title'))
    this.$store.commit('global/setShowBack', false)
    this.$store.commit('global/setShowMore', false)
  },
  methods: {
    toShort: function () {
      var baseUrl = this.$store.state.api.baseUrl
      var jiacn = this.$store.state.global.user.jiacn
      var token = this.$store.state.api.token()
      const _this = this
      if (!jiacn) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('dwx.subscribe_notify'),
          onHide () {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
      }
      this.$http.post(baseUrl + '/dwz/gen', {
        jiacn: jiacn,
        orgi: _this.orgi,
        expireTime: _this.$store.state.util.toTimeStamp(new Date()) + _this.expireYear * 365 * 24 * 60 * 60
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.shortUrl = 'https://dwz.chaoyoufan.net/' + res.data.data
        } else {
          AlertModule.show({ title: _this.$t('app.alert'), content: res.data.msg })
        }
      }).catch(error => {
        if (error.response.status === 401) {
          _this.$store.commit('cleanToken')
          _this.$router.go(0)
        }
      })
    },
    toLong: function () {
      var baseUrl = this.$store.state.api.baseUrl
      var jiacn = this.$store.state.global.user.jiacn
      var token = this.$store.state.api.token()
      const _this = this
      if (!jiacn) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('dwx.subscribe_notify'),
          onHide () {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
      }
      var uri = _this.uri
      if (uri.indexOf('/') !== -1) {
        uri = uri.substring(uri.lastIndexOf('/') + 1)
      }
      this.$http.get(baseUrl + '/dwz/restore', {
        params: {
          uri: uri,
          access_token: token
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.longUrl = res.data.data
        } else {
          AlertModule.show({ title: _this.$t('app.alert'), content: res.data.msg })
        }
      }).catch(error => {
        if (error.response.status === 401) {
          _this.$store.commit('cleanToken')
          _this.$router.go(0)
        }
      })
    },
    copyContent: function () {
      var clipboard = new Clipboard('#copyBtn')
      const _this = this
      clipboard.on('success', function (e) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('phrase.copy_success')
        })
        e.clearSelection()
      })
    }
  },
  data () {
    return {
      showOpMenu: false,
      index: 0,
      orgi: '',
      expireList: [{
        name: '一年',
        value: '1'
      }, {
        name: '长期',
        value: '10'
      }],
      expireYear: ['1'],
      uri: '',
      shortUrl: '',
      longUrl: ''
    }
  },
  directives: {
    TransferDom
  },
  components: {
    Tab,
    TabItem,
    Swiper,
    SwiperItem,
    Qrcode,
    dateFormat,
    Actionsheet,
    ConfirmPlugin,
    Group,
    XInput,
    XButton,
    XTextarea,
    PopupPicker
  }
}
</script>
<style lang="less" scoped>
@import "~vux/src/styles/1px.less";
@import "~vux/src/styles/center.less";
</style>
