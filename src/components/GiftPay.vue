<template>
  <div style="background-color: #FFFFFF;">
    <div v-transfer-dom>
      <actionsheet :menus="opMenu"
                   v-model="showOpMenu"
                   @on-click-menu="onClickOpMenu"></actionsheet>
    </div>
    <card>
      <img slot="header" :src="picUrl" style="width:100%;" />
      <div slot="content"
           style="padding: 15px;">
        <p style="font-size:14px;line-height:1.2;">{{name}}</p>
        <p style="color:#999;font-size:12px;">{{description}}</p>
      </div>
    </card>
    <div>
      <tab v-model="index" bar-position="top">
        <tab-item selected>{{ $t('gift.pay') }}</tab-item>
        <tab-item>{{ $t('gift.qrcode') }}</tab-item>
      </tab>
    </div>
    <swiper v-model="index" :show-dots="false" height="280px">
      <swiper-item>
        <div style="padding-top:15px;padding-left:3px;text-align:center;">
          <checker v-model="payMoney" default-item-class="checker-item" selected-item-class="checker-item-selected">
            <checker-item value='0'>{{point}}{{$t('gift.point')}}</checker-item>
            <checker-item :value="price">￥{{price}}</checker-item>
          </checker>
        </div>
        <group label-width="4.5em" label-margin-right="1em" label-align="right">
          <x-input :title="$t('gift.consignee')" is-type="china-name"
                   :placeholder="$t('gift.consignee_tips')" v-model="consignee">
            <x-button slot="right" type="primary" @click.native="wxAddress" mini>{{$t('app.select')}}</x-button>
          </x-input>
          <x-input :title="$t('gift.phone')" keyboard="number" is-type="china-mobile"
                   :placeholder="$t('gift.phone_tips')" v-model="phone"></x-input>
          <x-textarea :title="$t('gift.address')" v-model="address"
                      :placeholder="$t('gift.address_tips')"
                      :show-counter="false"
                      :rows="2"
                      v-show="virtual != 1"></x-textarea>
          <x-button action-type="button" style="width:92%;"
                    type="primary"
                    @click.native="toPay">{{$t('app.submit')}}</x-button>
        </group>
      </swiper-item>
      <swiper-item>
        <div style="text-align:center;">
          <div style="padding:10px;">￥<font style="font-size:24px;">{{price}}</font>
          </div>
          <qrcode :value="qrcodeUrl"></qrcode>
        </div>
      </swiper-item>
    </swiper>
  </div>
</template>

<script>
import { Card, Tab, TabItem, Swiper, SwiperItem, Qrcode, dateFormat, TransferDom, Actionsheet, ConfirmPlugin, Group, XInput, XButton, XTextarea, AlertModule, Checker, CheckerItem } from 'vux'

export default {
  created: function () {
    this.$store.commit('global/setMenu', {
      menus: [{
        key: 'list',
        value: this.$t('gift.order_list'),
        fn: function () {
          this.$router.push({name: 'OrderList'})
        }
      }],
      event: this
    })
    this.$store.commit('global/setTitle', this.$t('gift.title'))
    this.$store.commit('global/setShowBack', false)
    this.$store.commit('global/setShowMore', true)
    var baseUrl = this.$store.state.api.baseUrl
    var appid = this.$store.state.global.user.appid
    var token = this.$store.state.api.token()
    const _this = this
    this.$http.get(baseUrl + '/gift/get', {
      params: {
        id: this.$route.query.id,
        access_token: token
      }
    }).then(res => {
      let data = res.data.data
      this.giftId = data.id
      this.picUrl = data.picUrl
      this.name = data.name
      this.description = data.description
      this.point = data.point
      this.price = data.price / 100
      this.quantity = data.quantity
      this.virtual = data.virtual
      document.title = this.name + ' - ' + this.$store.state.global.title
    }).catch(error => {
      if (error.response.status === 401) {
        _this.$store.commit('cleanToken')
        _this.$router.go(0)
      }
    })
    // 生成二维码
    this.$http.get(baseUrl + '/wx/pay/scanPay/qrcodeLink', {
      params: {
        productId: 'GIF' + (Array(7).join('0') + this.$route.query.id).slice(-7),
        access_token: token,
        appid: appid
      }
    }).then(res => {
      this.qrcodeUrl = res.data
    })
  },
  methods: {
    onClickOpMenu: function (key, item) {
      console.log(item)
    },
    toPay: function () {
      var baseUrl = this.$store.state.api.baseUrl
      var jiacn = this.$store.state.global.user.jiacn
      var token = this.$store.state.api.token()
      var appid = this.$store.state.global.user.appid
      const _this = this
      if (!jiacn) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('gift.subscribe_notify'),
          onHide () {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
        })
        return
      }
      if (!this.consignee) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('gift.consignee_tips')
        })
        return
      }
      if (!this.phone) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('gift.phone_tips')
        })
        return
      }
      if (this.virtual !== 1 && !this.address) {
        AlertModule.show({
          title: _this.$t('app.notify'),
          content: _this.$t('gift.address_tips')
        })
        return
      }
      this.$http.post(baseUrl + '/gift/usage/add', {
        jiacn: jiacn,
        giftId: this.giftId,
        quantity: 1,
        price: this.payMoney * 100,
        consignee: this.consignee,
        phone: this.phone,
        address: this.address,
        status: 1
      }, {
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json'
        }
      }).then(res => {
        if (res.data.code === 'E0') {
          if (_this.payMoney === '0') {
            AlertModule.show({
              title: _this.$t('app.notify'),
              content: _this.$t('gift.pay_notify'),
              onHide () {
                _this.$router.go(0)
              }
            })
          } else {
            _this.$http.get(baseUrl + '/wx/pay/createOrder', {
              params: {
                outTradeNo: 'GIF' + (Array(7).join('0') + res.data.data.id).slice(-7),
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
          }
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
    wxAddress: function (data) {
      var _this = this
      _this.$wechat.openAddress({
        success: function (res) {
          _this.consignee = res.userName
          _this.phone = res.telNumber
          _this.address = res.detailInfo
        },
        cancel: function (res) {
          console.log('cancel weixin address selecting')
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
              content: vm.$t('gift.pay_notify'),
              onHide () {
                vm.$router.go(0)
              }
            })
          } else {
            AlertModule.show({ title: vm.$t('app.alert'), content: vm.$t('gift.pay_cancel') })
          }
        }
      )
    }
  },
  data () {
    return {
      list: [],
      opMenu: {
        del: this.$t('task.del'),
        cancel: this.$t('task.cancel')
      },
      showOpMenu: false,
      index: 0,
      picUrl: '',
      name: '',
      description: '',
      point: 999,
      price: 999,
      quantity: 0,
      qrcodeUrl: '',
      consignee: '',
      phone: '',
      address: '',
      virtual: 1,
      payMoney: '0'
    }
  },
  directives: {
    TransferDom
  },
  components: {
    Card,
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
    Checker,
    CheckerItem
  }
}
</script>
<style lang="less" scoped>
@import "~vux/src/styles/1px.less";
@import "~vux/src/styles/center.less";
.checker-item {
  width: 43%;
  height: 26px;
  line-height: 26px;
  text-align: center;
  border-radius: 3px;
  border: 1px solid #ccc;
  background-color: #fff;
  margin-right: 6px;
}
.checker-item-selected {
  background: #ffffff url(../assets/checker/active.png) no-repeat right bottom;
  border-color: #ff4a00;
}
</style>
