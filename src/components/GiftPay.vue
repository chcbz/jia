<template>
  <div style="background-color: #FFFFFF;">
    <var-action-sheet
      :actions="opMenu"
      v-model="showOpMenu"
      @select="onClickOpMenu"
    />

    <var-card>
      <template #image>
        <img :src="picUrl" style="width:100%;"/>
      </template>
      <template #title>
        <p style="font-size:14px;line-height:1.2;">{{name}}</p>
      </template>
      <template #description>
        <p style="color:#999;font-size:12px;">{{description}}</p>
      </template>
    </var-card>

    <var-tabs v-model="index">
      <var-tab>{{ $t('gift.pay') }}</var-tab>
      <var-tab>{{ $t('gift.qrcode') }}</var-tab>
    </var-tabs>

    <var-swipe v-model="index" height="280px">
      <var-swipe-item>
        <div style="padding-top:15px;padding-left:3px;text-align:center;">
          <var-radio-group v-model="payMoney">
            <var-radio :checked-value="0">{{point}}{{$t('gift.point')}}</var-radio>
            <var-radio :checked-value="price">￥{{price}}</var-radio>
          </var-radio-group>
        </div>
        <div>
          <var-input
            :label="$t('gift.consignee')"
            :placeholder="$t('gift.consignee_tips')"
            v-model="consignee"
            :rules="[(v) => !!v || $t('gift.consignee_tips')]"
          >
            <template #extra>
              <var-button type="primary" size="small" @click="wxAddress">{{$t('app.select')}}</var-button>
            </template>
          </var-input>
          <var-input
            :label="$t('gift.phone')"
            type="tel"
            :placeholder="$t('gift.phone_tips')"
            v-model="phone"
            :rules="[(v) => !!v || $t('gift.phone_tips')]"
          />
          <var-input
            v-if="virtual != 1"
            type="textarea"
            :label="$t('gift.address')"
            :placeholder="$t('gift.address_tips')"
            v-model="address"
            :rows="2"
            :rules="[(v) => !!v || $t('gift.address_tips')]"
          />
          <var-button
            block
            type="primary"
            @click="toPay"
          >
            {{$t('app.submit')}}
          </var-button>
        </div>
      </var-swipe-item>
      <var-swipe-item>
        <div style="text-align:center;">
          <div style="padding:10px;">￥<font style="font-size:24px;">{{price}}</font></div>
          <img :src="generateQRCode(qrcodeUrl)" width="200" height="200"/>
        </div>
      </var-swipe-item>
    </var-swipe>
  </div>
</template>

<script>
import { Card as VarCard, Tabs as VarTabs, Tab as VarTab, Swipe as VarSwipe, SwipeItem as VarSwipeItem,
         Input as VarInput, Button as VarButton, RadioGroup as VarRadioGroup,
         Radio as VarRadio, ActionSheet as VarActionSheet, Dialog } from '@varlet/ui'
import QRCode from 'qrcode'
import { useGlobalStore } from '@/stores/global'
import { useApiStore } from '@/stores/api'

export default {
  setup() {
    const globalStore = useGlobalStore()
    const apiStore = useApiStore()
    return { globalStore, apiStore }
  },
  created: function () {
    this.globalStore.setMenu({
      menus: [{
        key: 'list',
        value: this.$t('gift.order_list'),
        fn: function () {
          this.$router.push({name: 'OrderList'})
        }
      }],
      event: this
    })
    this.globalStore.setTitle(this.$t('gift.title'))
    this.globalStore.setShowBack(false)
    this.globalStore.setShowMore(true)
    var baseUrl = this.apiStore.baseUrl
    var appid = this.globalStore.user.appid
    this.$http.get(baseUrl + '/gift/get', {
      params: {
        id: this.$route.query.id
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
      document.title = this.name + ' - ' + this.globalStore.title
    })
    // 生成二维码
    this.$http.get(baseUrl + '/wx/pay/scanPay/qrcodeLink', {
      params: {
        productId: 'GIF' + (Array(7).join('0') + this.$route.query.id).slice(-7),
        appid: appid
      }
    }).then(res => {
      this.qrcodeUrl = res.data
    })
  },
  methods: {
    generateQRCode(text) {
      return QRCode.toDataURL(text, { width: 200 })
    },
    onClickOpMenu: function (item) {
      console.log(item)
    },
    toPay: function () {
      var baseUrl = this.apiStore.baseUrl
      var jiacn = this.globalStore.getJiacn
      var appid = this.globalStore.user.appid
      const _this = this
      if (!jiacn) {
        Dialog({
          title: _this.$t('app.notify'),
          message: _this.$t('gift.subscribe_notify'),
          onConfirm: () => {
            window.location.href = 'https://mp.weixin.qq.com/mp/profile_ext?action=home&__biz=MzU2OTU3Njk5MQ==&scene=110#wechat_redirect'
          }
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
      }).then(res => {
        if (res.data.code === 'E0') {
          if (_this.payMoney === 0) {
            Dialog({
              title: _this.$t('app.notify'),
              message: _this.$t('gift.pay_notify'),
              onConfirm: () => {
                _this.$router.go(0)
              }
            })
          } else {
            _this.$http.get(baseUrl + '/wx/pay/createOrder', {
              params: {
                outTradeNo: 'GIF' + (Array(7).join('0') + res.data.data.id).slice(-7),
                tradeType: 'JSAPI',
                appid: appid
              }
            }).then((res) => {
              if (res.data) {
                _this.weixinPay(res.data)
              } else {
                Dialog({
                  title: _this.$t('app.alert'),
                  message: res.data.msg
                })
              }
            })
          }
        } else {
          Dialog({
            title: _this.$t('app.alert'),
            message: res.data.msg
          })
        }
      })
    },
    wxAddress: function (data) {
      var _this = this
      _this.$wechat.openAddress({
        success: function (res) {
          _this.consignee = res.userName
          _this.phone = res.telNumber
          _this.address = res.provinceName + res.cityName + res.countryName + res.detailInfo
        },
        cancel: function (res) {
          console.log('cancel weixin address selecting')
        }
      })
    },
    weixinPay: function (data) {
      var vm = this
      if (typeof WeixinJSBridge === 'undefined') {
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
      WeixinJSBridge.invoke(
        'getBrandWCPayRequest', {
          debug: true,
          'appId': data.appId,
          'timeStamp': data.timeStamp,
          'nonceStr': data.nonceStr,
          'package': data.packageValue,
          'signType': data.signType,
          'paySign': data.paySign,
          jsApiList: [
            'chooseWXPay'
          ]
        },
        function (res) {
          if (res.err_msg === 'get_brand_wcpay_request:ok') {
            Dialog({
              title: vm.$t('app.notify'),
              message: vm.$t('gift.pay_notify'),
              onConfirm: () => {
                vm.$router.go(0)
              }
            })
          } else {
            Dialog({
              title: vm.$t('app.alert'),
              message: vm.$t('gift.pay_cancel')
            })
          }
        }
      )
    }
  },
  data () {
    return {
      list: [],
      opMenu: [
        { key: 'list', name: this.$t('gift.order_list') }
      ],
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
      payMoney: 0
    }
  },
  components: {
    VarCard,
    VarTabs,
    VarTab,
    VarSwipe,
    VarSwipeItem,
    VarInput,
    VarButton,
    VarRadioGroup,
    VarRadio,
    VarActionSheet
  }
}
</script>

<style scoped>
.var-radio {
  width: 43%;
  height: 26px;
  line-height: 26px;
  text-align: center;
  border-radius: 3px;
  border: 1px solid #ccc;
  background-color: #fff;
  margin-right: 6px;
}

.var-radio--checked {
  background: #ffffff url(../assets/checker/active.png) no-repeat right bottom;
  border-color: #ff4a00;
}
</style>
