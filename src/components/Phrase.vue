<template>
  <div>
    <div v-transfer-dom>
      <x-dialog v-model="addDialogShow" hide-on-blur>
        <group style="margin: 15px;">
          <x-textarea :placeholder="$t('phrase.content_placeholder')" :max="200" v-model="newContent"></x-textarea>
          <x-button type="primary" :disabled="newContent==''" @click.native="addContent">{{ $t('app.submit') }}</x-button>
          <x-button type="default" @click.native="addDialogShow=false">{{ $t('app.cancel') }}</x-button>
        </group>
      </x-dialog>
    </div>
    <div v-transfer-dom>
      <x-dialog v-model="fbDialogShow" hide-on-blur>
        <group style="margin: 15px;">
          <x-input :placeholder="$t('phrase.feedback_title_placeholder')" :max="50" v-model="fbTitle"></x-input>
          <x-textarea :placeholder="$t('phrase.feedback_content_placeholder')" :show-counter="false" :max="500" v-model="fbContent"></x-textarea>
          <x-input :placeholder="$t('phrase.feedback_name_placeholder')" :max="20" v-model="fbName"></x-input>
          <x-input :placeholder="$t('phrase.feedback_phone_placeholder')" v-model="fbPhone" type="tel" is-type="china-mobile"></x-input>
          <x-input :placeholder="$t('phrase.feedback_email_placeholder')" :max="100" v-model="fbEmail" type="email" is-type="email"></x-input>
          <x-button type="primary" :disabled="fbTitle==''" @click.native="feedback">{{ $t('app.submit') }}</x-button>
          <x-button type="default" @click.native="fbDialogShow=false">{{ $t('app.cancel') }}</x-button>
        </group>
      </x-dialog>
    </div>
    <div style="margin:0px 25px; min-height:200px; text-align: center; display: flex;">
      <h2 id="content" style="align-self: center;">{{phrase.content}}</h2>
    </div>
    <span style="margin: 25px; color: #999; display: inline-block;" v-html="$t('phrase.pub_author', {'num': phrase.pv, 'author': author, 'copyright': $store.state.global.copyright, 'copyrightLink': $store.state.global.copyrightLink, 'time': $store.state.util.fromTimeStamp(phrase.createTime, 'YYYY-MM-DD')})"></span>
    <flexbox style="text-align: center;">
      <flexbox-item @click.native="payTips" style="cursor: pointer;">
        <span class="iconfont icon-dashang" style="font-size: 26px;"></span>
        <div>{{ $t('phrase.tips') }}</div>
      </flexbox-item>
      <flexbox-item ref="upvote" @click.native="toTick(1)" style="cursor: pointer;">
        <span class="iconfont icon-zan" style="font-size: 26px;"></span>
        <div>{{ $t('phrase.up') }}{{phrase.up}}</div>
      </flexbox-item>
      <flexbox-item ref="downvote" @click.native="toTick(0)" style="cursor: pointer;">
        <span class="iconfont icon-cai" style="font-size: 26px;"></span>
        <div>{{ $t('phrase.down') }}{{phrase.down}}</div>
      </flexbox-item>
      <flexbox-item @click.native="fbDialogShow=true" style="cursor: pointer;">
        <span class="iconfont icon-taolun" style="font-size: 26px;"></span>
        <div>{{ $t('phrase.say') }}</div>
      </flexbox-item>
    </flexbox>
    <div style="margin:25px 25px; text-align: center;">
      <h5>{{ $t('phrase.others') }}</h5>
    </div>
    <flexbox style="text-align: center;">
      <flexbox-item id="copyBtn" style="cursor: pointer;" data-clipboard-action="copy" data-clipboard-target="#content" @click.native="copyContent">
        <span class="iconfont icon-fuzhi" style="font-size: 26px;"></span>
        <div>{{ $t('phrase.copy') }}</div>
      </flexbox-item>
      <flexbox-item @click.native="refreshPage" style="cursor: pointer;">
        <span class="iconfont icon-xiayibu" style="font-size: 26px;"></span>
        <div>{{ $t('phrase.next') }}</div>
      </flexbox-item>
      <flexbox-item @click.native="addDialogShow=true" style="cursor: pointer;">
        <span class="iconfont icon-tianjia" style="font-size: 26px;"></span>
        <div>{{ $t('phrase.add') }}</div>
      </flexbox-item>
      <flexbox-item @click.native="closeWindow" style="cursor: pointer;">
        <span class="iconfont icon-tuichu" style="font-size: 26px;"></span>
        <div>{{ $t('phrase.close') }}</div>
      </flexbox-item>
    </flexbox>
  </div>
</template>

<script>
import { AlertModule, Flexbox, FlexboxItem, Group, XDialog, XButton, XTextarea, XInput, TransferDom, trim } from 'vux'
import Clipboard from 'clipboard'

export default {
  created: function () {
    this.$store.commit('global/setTitle', this.$t('phrase.title'))
    document.title = this.$t('phrase.title_sub')
    this.$store.commit('global/setShowBack', false)
    this.$store.commit('global/setShowMore', false)
    var baseUrl = this.$store.state.api.baseUrl
    var jiacn = this.$store.state.global.user.jiacn
    var token = this.$store.state.api.token()
    const _this = this
    this.$http.post(baseUrl + '/phrase/get/random', {
      jiacn: jiacn
    }, {
      headers: {
        Authorization: 'Bearer ' + token,
        'Content-Type': 'application/json'
      }
    }).then(res => {
      _this.phrase = res.data.data
      document.title = _this.phrase.content
      this.$http.get(baseUrl + '/phrase/read', {
        params: {
          id: res.data.data.id,
          access_token: token
        }
      }).then(res => {
        _this.phrase.pv++
      }).catch(error => {
        if (error.response.status === 401) {
          _this.$store.commit('cleanToken')
          _this.$router.go(0)
        }
      })
      if (_this.phrase.jiacn) {
        this.$http.get(baseUrl + '/user/get', {
          params: {
            type: 'cn',
            key: _this.phrase.jiacn,
            access_token: token
          }
        }).then(res => {
          if (res.data.code === 'E0') {
            _this.author = res.data.data.nickname
          }
        }).catch(error => {
          if (error.response.status === 401) {
            _this.$store.commit('cleanToken')
            _this.$router.go(0)
          }
        })
      }
    }).catch(error => {
      if (error.response.status === 401) {
        _this.$store.commit('cleanToken')
        _this.$router.go(0)
      }
    })
  },
  methods: {
    onClickOpMenu: function (key, item) {
      console.log(item)
    },
    toTick: function (opt) {
      if (this.hasTick) return false
      this.hasTick = true
      var baseUrl = this.$store.state.api.baseUrl
      var jiacn = this.$store.state.global.user.jiacn
      var token = this.$store.state.api.token()
      const _this = this
      if (!jiacn) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('phrase.subscribe_notify'),
          onHide () {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return false
      }
      this.$http.post(baseUrl + '/phrase/vote', {
        jiacn: jiacn,
        phraseId: this.phrase.id,
        vote: opt
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          if (opt === 1) {
            _this.phrase.up++
            _this.$refs.upvote.style.color = 'red'
          } else {
            _this.phrase.down++
            _this.$refs.downvote.style.color = 'red'
          }
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
    },
    refreshPage: function () {
      window.history.go(0)
    },
    closeWindow: function () {
      this.$store.state.util.closeWindow()
    },
    addContent: function () {
      var baseUrl = this.$store.state.api.baseUrl
      var jiacn = this.$store.state.global.user.jiacn
      var token = this.$store.state.api.token()
      const _this = this
      this.$http.post(baseUrl + '/phrase/create', {
        jiacn: jiacn,
        content: trim(this.newContent),
        tag: '毒鸡汤'
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.newContent = ''
          _this.addDialogShow = false
          AlertModule.show({
            title: _this.$t('app.notify'),
            content: _this.$t('phrase.add_success')
          })
        } else {
          _this.addDialogShow = false
          AlertModule.show({ title: _this.$t('app.alert'), content: res.data.msg })
        }
      }).catch(error => {
        if (error.response.status === 401) {
          _this.$store.commit('cleanToken')
          _this.$router.go(0)
        }
      })
    },
    feedback: function () {
      var baseUrl = this.$store.state.api.baseUrl
      var jiacn = this.$store.state.global.user.jiacn
      var token = this.$store.state.api.token()
      const _this = this
      if (!jiacn) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('phrase.subscribe_notify'),
          onHide () {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return false
      }
      let formData = new FormData()
      formData.append('jiacn', jiacn)
      formData.append('resourceId', 'phrase')
      formData.append('name', _this.fbName)
      formData.append('phone', _this.fbPhone)
      formData.append('email', _this.fbEmail)
      formData.append('title', _this.fbTitle)
      formData.append('content', _this.fbContent)
      this.$http.post(baseUrl + '/kefu/message/create', formData, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'multipart/form-data'
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.fbTitle = ''
          _this.fbContent = ''
          _this.fbDialogShow = false
          AlertModule.show({
            title: _this.$t('app.notify'),
            content: _this.$t('phrase.feedback_success')
          })
        }
      }).catch(error => {
        if (error.response.status === 401) {
          _this.$store.commit('cleanToken')
          _this.$router.go(0)
        }
      })
    },
    payTips: function () {
      var baseUrl = this.$store.state.api.baseUrl
      var jiacn = this.$store.state.global.user.jiacn
      var token = this.$store.state.api.token()
      var appid = this.$store.state.global.user.appid
      const _this = this

      if (!jiacn) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('phrase.subscribe_notify'),
          onHide () {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return false
      }
      this.$http.post(baseUrl + '/tip/create', {
        type: 1,
        entityId: this.phrase.id,
        price: 100,
        jiacn: jiacn,
        status: 0
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          _this.$http.get(baseUrl + '/wx/pay/createOrder', {
            params: {
              outTradeNo: 'TIP' + (Array(7).join('0') + res.data.data.id).slice(-7),
              tradeType: 'JSAPI',
              access_token: token,
              appid: appid
            }
          }).then((res) => {
            if (res.data) {
              _this.weixinPay(res.data)
            } else {
              AlertModule.show({ title: _this.$t('app.alert'), content: res.data.msg })
            }
          }).catch(error => {
            if (error.response.status === 401) {
              _this.$store.commit('cleanToken')
              _this.$router.go(0)
            }
          })
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
    weixinPay: function (data) {
      var vm = this
      if (typeof WeixinJSBridge === 'undefined') { // 微信浏览器内置对象。参考微信官方文档
        if (document.addEventListener) {
          document.addEventListener('WeixinJSBridgeReady', vm.onBridgeReady(data), false)
        } else if (document.attachEvent) {
          document.attachEvent('WeixinJSBridgeReady', vm.onBridgeReady(data))
          document.attachEvent('onWeixinJSBridgeReady', vm.onBridgeReady(data))
        }
      } else {
        vm.onBridgeReady(data)
      }
    },
    onBridgeReady: function (data) {
      var vm = this
      WeixinJSBridge.invoke( // eslint-disable-line
        'getBrandWCPayRequest', {
          debug: true,
          'appId': data.appId, // 公众号名称，由商户传入
          'timeStamp': data.timeStamp, // 时间戳，自1970年以来的秒数
          'nonceStr': data.nonceStr, // 随机串
          'package': data.packageValue,
          'signType': data.signType, // 微信签名方式：
          'paySign': data.paySign, // 微信签名
          // 这里的信息从后台返回的接口获得。
          jsApiList: [
            'chooseWXPay'
          ]
        },
        function (res) {
          // 使用以上方式判断前端返回,微信团队郑重提示：res.err_msg将在用户支付成功后返回ok，但并不保证它绝对可靠。
          if (res.err_msg === 'get_brand_wcpay_request:ok') {
            AlertModule.show({
              title: vm.$t('app.notify'),
              content: vm.$t('phrase.pay_notify')
            })
          } else {
            AlertModule.show({ title: vm.$t('app.alert'), content: vm.$t('phrase.pay_cancel') })
          }
        }
      )
    }
  },
  data () {
    return {
      phrase: {},
      showOpMenu: false,
      hasTick: false,
      addDialogShow: false,
      newContent: '',
      author: '',
      fbDialogShow: false,
      fbTitle: '',
      fbContent: '',
      fbName: '',
      fbPhone: '',
      fbEmail: ''
    }
  },
  directives: {
    TransferDom
  },
  components: {
    AlertModule,
    Flexbox,
    FlexboxItem,
    Group,
    XDialog,
    XButton,
    XTextarea,
    XInput
  }
}
</script>
<style lang="less" scoped>
@import "~vux/src/styles/1px.less";
@import "~vux/src/styles/center.less";
</style>
