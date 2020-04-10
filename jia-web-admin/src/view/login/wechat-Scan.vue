<template>
  <div class="login_div">
    <Button id="wxsys" type="primary" v-on:click="createWxCode()">扫一扫</Button>
  </div>
</template>

<script>
import wxSdk from 'weixin-js-sdk'
import { mapActions } from 'vuex'
import { createJsapi } from '@/api/data'

export default {
  components: {
  },
  methods: {
    ...mapActions([
      'handleLogin',
      'getUserInfo'
    ]),
    initvue () {
      // this.createWxCode()
    },
    // 创建二维码
    createWxCode () {
      this.$constant.getWorkToken().then((data) => {
        var appid = 'wx9faba829e04f348b'
        var url = window.location.href
        const post_arr = {
          'appid': appid,
          'url': url
        }
        var access_token = data.access_token
        createJsapi(post_arr, access_token).then(res => {
          if (res.data.msg === 'ok') {
            var resArr = res.data.data
            wxSdk.config({
              // 开启调试模式,调用的所有api的返回值会在客户端alert出来，若要查看传入的参数，可以在pc端打开，参数信息会通过log打出，仅在pc端时才会打印。
              debug: false,
              // 必填，公众号的唯一标识
              appId: resArr.appId,
              // 必填，生成签名的时间戳
              timestamp: '' + resArr.timestamp,
              // 必填，生成签名的随机串
              nonceStr: resArr.nonceStr,
              // 必填，签名
              signature: resArr.signature,
              // 必填，需要使用的JS接口列表，所有JS接口列表
              jsApiList: ['checkJsApi', 'scanQRCode']
            })
          } else { this.$Message.error(res.data.msg) }
        }).catch(ess => {
          this.$Message.error('请联系管理员')
        })
      })
      wxSdk.error(function (res) {
        alert('出错了：' + res.errMsg)// 这个地方的好处就是wx.config配置错误，会弹出窗口哪里错误，然后根据微信文档查询即可。
      })

      wxSdk.ready(function () {
        wxSdk.checkJsApi({
          jsApiList: ['scanQRCode'],
          success: function (res) {
            console.log(res)
          }
        })

        wxSdk.scanQRCode({
          needResult: 1, // 默认为0，扫描结果由微信处理，1则直接返回扫描结果，
          scanType: ['qrCode'], // 可以指定扫二维码还是一维码，默认二者都有
          success: function (res) {
            var result = res.resultStr // 当needResult 为 1 时，扫码返回的结果
            alert('扫描结果：' + result)
          }
        })
      })
    }
    // 创建二维码end
  },
  mounted () {
    this.initvue()
  }
}
</script>

<style>

</style>
